package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Paths

class SessionDeletionCoordinatorTest {
    @Test
    fun `删除严格等待生成与向量屏障后才提交数据库并完成物理清理`() = runTest {
        val events = mutableListOf<String>()
        val target = target()
        val coordinator = coordinator(
            target = target,
            events = events,
        )

        assertThat(coordinator.delete("session-1")).isEqualTo(SessionDeletionResult.Deleted)
        assertThat(events).containsExactly(
            "generation-cancel-join",
            "approval-close",
            "vector-acquire",
            "vector-await",
            "journal-stage",
            "database-delete",
            "vector-commit",
            "journal-complete",
            "post-commit",
        ).inOrder()
    }

    @Test
    fun `数据库失败回滚物理树与屏障且返回可重试失败`() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("injected-db")
        val coordinator = coordinator(target(), events, databaseFailure = failure)

        val result = coordinator.delete("session-1")

        assertThat(result).isInstanceOf(SessionDeletionResult.Failed::class.java)
        assertThat((result as SessionDeletionResult.Failed).error.cause).isSameInstanceAs(failure)
        assertThat(events).containsExactly(
            "generation-cancel-join",
            "approval-close",
            "vector-acquire",
            "vector-await",
            "journal-stage",
            "database-delete",
            "journal-rollback",
            "vector-abort",
        ).inOrder()
    }

    @Test
    fun `取消必须传播且仍回滚已经建立的删除边界`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            target(),
            events,
            databaseFailure = CancellationException("cancel"),
        )

        val thrown = runCatching { coordinator.delete("session-1") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(events.takeLast(2)).containsExactly("journal-rollback", "vector-abort").inOrder()
    }

    @Test
    fun `数据库已提交但物理收尾失败时返回可重试错误且不伪装成功`() = runTest {
        val failure = java.io.IOException("delete staged root failed")
        val result = coordinator(target(), mutableListOf(), completionFailure = failure)
            .delete("session-1")

        assertThat(result).isInstanceOf(SessionDeletionResult.Failed::class.java)
        assertThat((result as SessionDeletionResult.Failed).error.code)
            .isEqualTo(SessionDeletionErrorCode.WORKSPACE)
        assertThat(result.error.cause).isInstanceOf(java.io.IOException::class.java)
        assertThat(result.error.cause).hasMessageThat().isEqualTo(failure.message)
    }

    @Test
    fun `并发双删只提交一次第二次返回已删除`() = runTest {
        val calls = mutableListOf<String>()
        var current: SessionDeletionTarget? = target()
        val firstCommit = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val coordinator = SessionDeletionCoordinator(
            gate = SessionExecutionGate(),
            resolveTarget = { current },
            cancelAndJoinGeneration = { calls += "generation" },
            closePendingExecution = { calls += "approval" },
            acquireVectorBarrier = { NoOpSessionDeletionBarrier },
            journal = object : SessionWorkspaceMutationJournal {
                override suspend fun stage(target: SessionDeletionTarget): StagedSessionWorkspaceMutation =
                    StagedSessionWorkspaceMutation("op", target)
                override suspend fun rollback(staged: StagedSessionWorkspaceMutation) = Unit
                override suspend fun complete(staged: StagedSessionWorkspaceMutation) = Unit
            },
            deleteDatabase = { _, _ ->
                firstCommit.complete(Unit)
                allowCommit.await()
                current = null
                calls += "commit"
            },
        )
        val first = async { coordinator.delete("session-1") }
        firstCommit.await()
        val second = async { coordinator.delete("session-1") }
        allowCommit.complete(Unit)

        assertThat(first.await()).isEqualTo(SessionDeletionResult.Deleted)
        assertThat(second.await()).isEqualTo(SessionDeletionResult.AlreadyDeleted)
        assertThat(calls.count { it == "commit" }).isEqualTo(1)
    }

    @Test
    fun `删除等待既有workspace写租约并使用释放后的最终target提交`() = runTest {
        val gate = SessionExecutionGate()
        var current = target().copy(fileUuids = listOf("root-1"))
        var committed: SessionDeletionTarget? = null
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val write = launch {
            gate.withWorkspaceAdmission("root-1") {
                writeEntered.complete(Unit)
                releaseWrite.await()
                current = current.copy(fileUuids = listOf("root-1", "late-file"))
            }
        }
        writeEntered.await()
        val coordinator = SessionDeletionCoordinator(
            gate = gate,
            resolveTarget = { current },
            cancelAndJoinGeneration = {},
            closePendingExecution = {},
            acquireVectorBarrier = { NoOpSessionDeletionBarrier },
            journal = object : SessionWorkspaceMutationJournal {
                override suspend fun stage(target: SessionDeletionTarget) =
                    StagedSessionWorkspaceMutation("op", target)
                override suspend fun rollback(staged: StagedSessionWorkspaceMutation) = Unit
                override suspend fun complete(staged: StagedSessionWorkspaceMutation) = Unit
            },
            deleteDatabase = { target, _ -> committed = target },
        )
        val deletion = async { coordinator.delete("session-1") }
        while (!gate.isDeleting("session-1")) yield()

        assertThat(committed).isNull()
        releaseWrite.complete(Unit)
        write.join()

        assertThat(deletion.await()).isEqualTo(SessionDeletionResult.Deleted)
        assertThat(committed?.fileUuids).containsExactly("root-1", "late-file")
    }

    @Test
    fun `删除关闭admission后先取消generation再等待其写租约退出`() = runTest {
        val gate = SessionExecutionGate()
        val generationLeaseEntered = CompletableDeferred<Unit>()
        val releaseGenerationLease = CompletableDeferred<Unit>()
        val generation = launch {
            gate.withSessionAdmission("session-1") {
                generationLeaseEntered.complete(Unit)
                releaseGenerationLease.await()
            }
        }
        generationLeaseEntered.await()
        var databaseDeleted = false
        val coordinator = SessionDeletionCoordinator(
            gate = gate,
            resolveTarget = { target() },
            cancelAndJoinGeneration = {
                releaseGenerationLease.complete(Unit)
                generation.join()
            },
            closePendingExecution = {},
            acquireVectorBarrier = { NoOpSessionDeletionBarrier },
            journal = object : SessionWorkspaceMutationJournal {
                override suspend fun stage(target: SessionDeletionTarget) =
                    StagedSessionWorkspaceMutation("op", target)
                override suspend fun rollback(staged: StagedSessionWorkspaceMutation) = Unit
                override suspend fun complete(staged: StagedSessionWorkspaceMutation) = Unit
            },
            deleteDatabase = { _, _ -> databaseDeleted = true },
        )

        assertThat(coordinator.delete("session-1")).isEqualTo(SessionDeletionResult.Deleted)
        assertThat(databaseDeleted).isTrue()
    }

    private fun target() = SessionDeletionTarget(
        sessionId = "session-1",
        workspaceRootUuid = "root-1",
        physicalRoot = Paths.get("/tmp/session-1"),
        rootIdentity = "identity-1",
        fileUuids = listOf("root-1", "file-1"),
    )

    private fun coordinator(
        target: SessionDeletionTarget,
        events: MutableList<String>,
        databaseFailure: Throwable? = null,
        completionFailure: Throwable? = null,
    ) = SessionDeletionCoordinator(
        gate = SessionExecutionGate(),
        resolveTarget = { target },
        cancelAndJoinGeneration = { events += "generation-cancel-join" },
        closePendingExecution = { events += "approval-close" },
        acquireVectorBarrier = {
            events += "vector-acquire"
            object : SessionDeletionBarrier {
                override suspend fun awaitReady() { events += "vector-await" }
                override suspend fun commit() { events += "vector-commit" }
                override suspend fun abort() { events += "vector-abort" }
            }
        },
        journal = object : SessionWorkspaceMutationJournal {
            override suspend fun stage(target: SessionDeletionTarget): StagedSessionWorkspaceMutation {
                events += "journal-stage"
                return StagedSessionWorkspaceMutation("op-1", target)
            }
            override suspend fun rollback(staged: StagedSessionWorkspaceMutation) { events += "journal-rollback" }
            override suspend fun complete(staged: StagedSessionWorkspaceMutation) {
                events += "journal-complete"
                completionFailure?.let { throw it }
            }
        },
        deleteDatabase = { _, _ ->
            events += "database-delete"
            databaseFailure?.let { throw it }
        },
        afterDatabaseCommit = { events += "post-commit" },
    )
}

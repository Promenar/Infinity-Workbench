package com.promenar.infinity.data.secret

import com.promenar.infinity.data.session.*
import com.promenar.infinity.domain.secret.SecretSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SecretSessionDeletionTest {
    private fun coordinator(gate: SessionExecutionGate, vault: SessionSecretVault, fail: Boolean = false) = SessionDeletionCoordinator(
        gate = gate, resolveTarget = { null }, isDatabaseOnlySession = { true },
        deleteDatabaseOnly = { if (fail) throw IllegalStateException("数据库提交失败"); true },
        cancelAndJoinGeneration = {}, closePendingExecution = {},
        acquireVectorBarrier = { error("不需要工作区") },
        journal = object : SessionWorkspaceMutationJournal {
            override suspend fun stage(target: SessionDeletionTarget) = error("不需要工作区")
            override suspend fun rollback(staged: StagedSessionWorkspaceMutation) = Unit
            override suspend fun complete(staged: StagedSessionWorkspaceMutation) = Unit
        },
        deleteDatabase = { _, _ -> error("不需要工作区") },
        cleanupSessionData = { vault.clearSession(it) },
    )

    @Test fun `删除等待提交的写租约排空后清除提交新写入的槽位`() = runTest {
        val gate = SessionExecutionGate()
        val vault = SessionSecretVault()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val submission = launch {
            gate.withSessionMutation("s") {
                entered.complete(Unit)
                release.await()
                vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "synthetic-late-input".toByteArray())
            }
        }
        entered.await()
        val deletion = async { coordinator(gate, vault).delete("s") }
        yield()
        assertTrue(gate.isDeleting("s"))
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        submission.join()
        assertEquals(SessionDeletionResult.Deleted, deletion.await())
        assertNull(vault.revealBytes("s", "TOKEN"))
    }

    @Test fun `删除提交失败保留仍存在会话的内存槽位`() = runTest {
        val vault = SessionSecretVault()
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "synthetic-preserved".toByteArray())
        assertTrue(coordinator(SessionExecutionGate(), vault, fail = true).delete("s") is SessionDeletionResult.Failed)
        assertEquals("synthetic-preserved", vault.revealBytes("s", "TOKEN")!!.decodeToString())
    }
}

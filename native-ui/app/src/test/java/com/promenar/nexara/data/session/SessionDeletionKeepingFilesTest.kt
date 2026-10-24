package com.promenar.nexara.data.session

import com.promenar.nexara.data.storage.JvmBoundRealPathFileSystem
import com.promenar.nexara.data.workspace.PrivateWorkspaceFileRetention
import com.promenar.nexara.data.workspace.RetainedSessionWorkspace
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import io.mockk.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionDeletionKeepingFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `保留副本只在准入排空之后复制并返回工作区显示名`() = runTest {
        val root = temporary.newFolder().canonicalFile
        File(root, "a").writeText("旧内容")
        val projects = temporary.newFolder().canonicalFile
        val gate = SessionExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writing = launch {
            gate.withNewSessionAdmission("s") {
                entered.complete(Unit)
                release.await()
                File(root, "a").writeText("最终内容")
            }
        }
        entered.await()
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), java.time.Clock.systemDefaultZone(), JvmBoundRealPathFileSystem())
        var deleted = false
        val coordinator = coordinator(gate, root, retain = { retention.prepare(it.physicalRoot, " 会话标题 ") },
            delete = { deleted = true })
        val deletion = async { coordinator.delete("s", keepFiles = true) }
        yield()
        assertTrue(gate.isDeleting("s"))
        assertTrue(rows.isEmpty())
        assertFalse(deleted)
        release.complete(Unit)
        writing.join()
        val result = deletion.await() as SessionDeletionResult.DeletedKeepingFiles
        assertEquals("会话标题", result.displayName)
        assertEquals(rows.single().id, result.workspaceId)
        assertEquals("最终内容", File(projects, "${rows.single().location}/a").readText())
        assertTrue(deleted)
    }

    @Test fun `复制失败返回工作区错误且会话和来源保留`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        File(root, "a").writeText("保留")
        var staged = false
        var deleted = false
        val coordinator = coordinator(SessionExecutionGate(), root,
            retain = { throw java.io.IOException("复制失败") }, stage = { staged = true }, delete = { deleted = true })
        val result = coordinator.delete("s", keepFiles = true) as SessionDeletionResult.Failed
        assertEquals(SessionDeletionErrorCode.WORKSPACE, result.error.code)
        assertFalse(staged)
        assertFalse(deleted)
        assertEquals("保留", File(root, "a").readText())
    }

    @Test fun `目录操作持有串行入口时生成启动等待`() = runTest {
        val gate = SessionExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val fileOperation = launch {
            gate.withSessionMutation("s") {
                entered.complete(Unit)
                release.await()
                events += "files"
            }
        }
        entered.await()
        val generation = async { gate.withSessionMutation("s") { events += "generation" } }
        yield()
        assertTrue(events.isEmpty())
        release.complete(Unit)
        fileOperation.join()
        generation.await()
        assertEquals(listOf("files", "generation"), events)
    }

    @Test fun `删除事务失败回滚保留副本与登记且会话未删除`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        File(root, "a").writeText("保留")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), java.time.Clock.systemDefaultZone(), JvmBoundRealPathFileSystem())
        var rolledBack = false
        val coordinator = coordinator(SessionExecutionGate(), root,
            retain = { retention.prepare(it.physicalRoot, "标题") }, rollback = { rolledBack = true },
            delete = { throw java.io.IOException("数据库失败") })
        val result = coordinator.delete("s", true) as SessionDeletionResult.Failed
        assertEquals(SessionDeletionErrorCode.WORKSPACE, result.error.code)
        assertTrue(rolledBack)
        assertTrue(rows.isEmpty())
        assertTrue(projects.list()!!.isEmpty())
        assertEquals("保留", File(root, "a").readText())
    }

    @Test fun `不保留与无现存文件使用原删除结果`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        var deleted = 0
        val coordinator = coordinator(SessionExecutionGate(), root,
            retain = { error("不保留时不得复制") }, delete = { deleted++ })
        assertEquals(SessionDeletionResult.Deleted, coordinator.delete("s"))
        assertEquals(1, deleted)
        assertEquals(SessionDeletionResult.Deleted,
            coordinator(SessionExecutionGate(), root, retain = { null }).delete("s", true))
    }

    @Test fun `数据库提交后清理失败也不删除已保留副本`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        File(root, "a").writeText("唯一副本")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), java.time.Clock.systemDefaultZone(), JvmBoundRealPathFileSystem())
        val coordinator = coordinator(SessionExecutionGate(), root,
            retain = { retention.prepare(it.physicalRoot, "标题") }, complete = { throw java.io.IOException("收尾失败") })
        assertTrue(coordinator.delete("s", true) is SessionDeletionResult.Failed)
        assertEquals("唯一副本", File(projects, "${rows.single().location}/a").readText())
    }

    @Test fun `提交结果不确定且journal拒绝回滚时保留副本`() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        File(root, "a").writeText("唯一副本")
        val projects = temporary.newFolder().canonicalFile
        val rows = mutableListOf<WorkspaceEntity>()
        val retention = PrivateWorkspaceFileRetention(projects, dao(rows), java.time.Clock.systemDefaultZone(), JvmBoundRealPathFileSystem())
        val coordinator = coordinator(SessionExecutionGate(), root,
            retain = { retention.prepare(it.physicalRoot, "标题") },
            rollback = { throw IllegalStateException("DB_COMMITTED不能回滚") },
            delete = { throw kotlinx.coroutines.CancellationException("提交结果不确定") })
        assertTrue(runCatching { coordinator.delete("s", true) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("唯一副本", File(projects, "${rows.single().location}/a").readText())
    }

    private fun coordinator(
        gate: SessionExecutionGate,
        root: File,
        retain: suspend (SessionDeletionTarget) -> RetainedSessionWorkspace?,
        stage: () -> Unit = {},
        rollback: () -> Unit = {},
        complete: () -> Unit = {},
        delete: () -> Unit = {},
    ) = SessionDeletionCoordinator(
        gate = gate,
        resolveTarget = { SessionDeletionTarget("s", "root", root.toPath(), "identity", emptyList()) },
        cancelAndJoinGeneration = {}, closePendingExecution = {},
        acquireVectorBarrier = { NoOpSessionDeletionBarrier },
        journal = object : SessionWorkspaceMutationJournal {
            override suspend fun stage(target: SessionDeletionTarget): StagedSessionWorkspaceMutation {
                stage()
                return StagedSessionWorkspaceMutation("op", target)
            }
            override suspend fun rollback(staged: StagedSessionWorkspaceMutation) = rollback()
            override suspend fun complete(staged: StagedSessionWorkspaceMutation) = complete()
        },
        deleteDatabase = { _, _ -> delete() },
        retainWorkspace = retain,
    )

    private fun dao(rows: MutableList<WorkspaceEntity>): WorkspaceDao = mockk {
        coEvery { getByLocation(any(), any()) } answers { rows.firstOrNull { it.location == secondArg<String>() } }
        coEvery { insert(any()) } coAnswers { rows += firstArg<WorkspaceEntity>(); Unit }
        coEvery { countSessionReferences(any()) } returns 0
        coEvery { countAgentReferences(any()) } returns 0
        coEvery { deleteById(any()) } answers { if (rows.removeAll { it.id == firstArg<String>() }) 1 else 0 }
    }
}

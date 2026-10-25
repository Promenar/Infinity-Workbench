package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupDiagnosticSessionFactoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `专用根建立后才发布可发送草稿且不绑定Agent默认工作区`() = runTest {
        val manager = brokenManager()
        val repository = RecordingSessions()
        val workspace = mockk<IWorkspaceRepository>()
        val root = root(temporaryFolder.newFolder())
        coEvery { workspace.ensureSessionRoot("diagnostic-session") } coAnswers {
            val prepared = repository.sessions.getValue("diagnostic-session")
            assertThat(prepared.workspaceId).isNull()
            assertThat(prepared.workspaceRootUuid).isNull()
            assertThat(prepared.loopStatus).isEqualTo(LoopStatus.PAUSED)
            assertThat(prepared.draft).isNull()
            root
        }
        val issue = manager.issues.value.single()

        val session = factory(manager, repository, workspace).createDiagnosticSession(listOf(issue.id), "agent", "model")

        assertThat(session.agentId).isEqualTo("agent")
        assertThat(session.modelId).isEqualTo("model")
        assertThat(session.workspaceId).isNull()
        assertThat(session.workspacePath).isEqualTo(root.physicalRootPath)
        assertThat(session.workspaceRootUuid).isEqualTo("diagnostic-root")
        assertThat(session.loopStatus).isEqualTo(LoopStatus.IDLE)
        assertThat(session.messages).isEmpty()
        assertThat(session.title).contains(issue.summary)
        assertThat(session.draft).contains(issue.id)
        assertThat(session.draft).contains("app_diagnostics")
        assertThat(session.draft).contains("resolve_startup_issue")
        assertThat(session.draft).doesNotContain("私密用户消息正文")
        assertThat(session.draft).doesNotContain("/private/isolated-root")
        assertThat(repository.sessions[session.id]).isEqualTo(session)
    }

    @Test
    fun `草稿只包含调用方选中的问题且去重`() = runTest {
        val manager = brokenManager()
        manager.recover(StartupStep.TOMBSTONES, StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "other", "其它工作区")) { error("冲突") }
        val selected = manager.issues.value.first()
        val other = manager.issues.value.last()
        val repository = RecordingSessions()
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.ensureSessionRoot(any()) } returns root(temporaryFolder.newFolder())

        val session = factory(manager, repository, workspace).createDiagnosticSession(listOf(selected.id, selected.id), "agent")

        assertThat(session.draft).contains(selected.id)
        assertThat(session.draft).doesNotContain(other.id)
        assertThat(session.draft!!.split(selected.id)).hasSize(2)
        assertThat(session.modelId).isNull()
    }

    @Test
    fun `根创建失败清理准备记录但保留目录中用户文件`() = runTest {
        val repository = RecordingSessions()
        val directory = temporaryFolder.newFolder()
        val userFile = File(directory, "user.txt").apply { writeText("保留") }
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.ensureSessionRoot(any()) } throws IllegalStateException("根创建失败")

        val failure = runCatching { factory(brokenManager(), repository, workspace).createDiagnosticSession(emptyList(), "agent") }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(repository.sessions).isEmpty()
        assertThat(userFile.readText()).isEqualTo("保留")
    }

    @Test
    fun `根建立后的草稿保存失败只删除新会话记录`() = runTest {
        val repository = RecordingSessions().apply { failUpdate = true }
        val directory = temporaryFolder.newFolder()
        val userFile = File(directory, "user.txt").apply { writeText("保留") }
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.ensureSessionRoot(any()) } returns root(directory)

        val failure = runCatching { factory(brokenManager(), repository, workspace).createDiagnosticSession(emptyList(), "agent") }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(repository.sessions).isEmpty()
        assertThat(userFile.readText()).isEqualTo("保留")
    }

    @Test
    fun `取消继续向上传递且清理准备记录`() = runTest {
        val repository = RecordingSessions()
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.ensureSessionRoot(any()) } throws CancellationException("取消")

        val failure = runCatching { factory(brokenManager(), repository, workspace).createDiagnosticSession(emptyList(), "agent") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(repository.sessions).isEmpty()
    }

    @Test
    fun `标识碰撞不能覆盖或删除已有会话`() = runTest {
        val repository = RecordingSessions()
        val existing = Session(id = "diagnostic-session", agentId = "existing", title = "已有会话")
        repository.create(existing)
        val workspace = mockk<IWorkspaceRepository>()

        val failure = runCatching { factory(brokenManager(), repository, workspace).createDiagnosticSession(emptyList(), "agent") }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(repository.sessions.values).containsExactly(existing)
    }

    private fun factory(manager: StartupIssueManager, sessions: ISessionRepository, workspace: IWorkspaceRepository) =
        StartupDiagnosticSessionFactory(
            manager = manager,
            sessionRepository = sessions,
            workspaceRepository = workspace,
            strings = StartupDiagnosticSessionStrings(
                title = { "诊断：$it" },
                draft = { "$it\n使用 app_diagnostics 读取诊断；resolve_startup_issue 每次需批准。" },
                noIssues = "当前没有选中的启动问题。",
            ),
            newSessionId = { "diagnostic-session" },
            clock = { 100L },
        )

    private suspend fun brokenManager() = StartupIssueManager().apply {
        recover(StartupStep.WORKSPACE_JOURNAL,
            StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "isolated-root", "/private/isolated-root"),
        ) { error("私密用户消息正文") }
    }

    private fun root(directory: File) = FileEntry(
        uuid = "diagnostic-root", workspaceRootUuid = "diagnostic-root", parentUuid = null,
        name = "diagnostic", hash = "identity", isDirectory = true,
        physicalRootPath = directory.path, materializedPath = "/", createdAt = 1L, updatedAt = 1L,
    )

    private class RecordingSessions : ISessionRepository {
        val sessions = linkedMapOf<String, Session>()
        var failUpdate = false
        override suspend fun create(session: Session) { sessions[session.id] = session }
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            check(!failUpdate) { "保存失败" }
            sessions[id] = sessions.getValue(id).copy(
                title = updates["title"] as String,
                draft = updates["draft"] as String,
                workspacePath = updates["workspacePath"] as String,
                workspaceRootUuid = updates["workspaceRootUuid"] as String,
                loopStatus = updates["loopStatus"] as LoopStatus,
                updatedAt = updates["updatedAt"] as Long,
            )
        }
        override suspend fun delete(id: String) { sessions.remove(id) }
        override suspend fun getById(id: String) = sessions[id]
        override suspend fun getAll() = sessions.values.toList()
    }
}

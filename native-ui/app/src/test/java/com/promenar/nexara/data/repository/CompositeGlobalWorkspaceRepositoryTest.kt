package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.RenameResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositeGlobalWorkspaceRepositoryTest {

    private val baseRepo: IWorkspaceRepository = mockk(relaxed = true)
    private val sessionDao: SessionDao = mockk(relaxed = true)
    private val fileEntryDao: FileEntryDao = mockk(relaxed = true)

    private val ragRootUuid = "rag-root-uuid"
    private val session1 = SessionEntity(
        id = "session-1",
        agentId = "agent-1",
        title = "旅行规划",
        workspaceRootUuid = "session-1-root-uuid",
        workspacePath = "/data/user/0/app/files/session_workspaces/hash1",
        createdAt = 1000L,
        updatedAt = 2000L,
    )
    private val session2 = SessionEntity(
        id = "session-2",
        agentId = "agent-2",
        title = "代码重构",
        workspaceRootUuid = "session-2-root-uuid",
        workspacePath = "/data/user/0/app/files/session_workspaces/hash2",
        createdAt = 1100L,
        updatedAt = 2100L,
    )
    private val ragSession = SessionEntity(
        id = CompositeWorkspaceConstants.GLOBAL_KNOWLEDGE_SESSION_ID,
        agentId = "system",
        title = "全局知识库",
        workspaceRootUuid = ragRootUuid,
        createdAt = 100L,
        updatedAt = 200L,
    )

    private lateinit var compositeRepo: CompositeGlobalWorkspaceRepository

    @BeforeEach
    fun setUp() {
        compositeRepo = CompositeGlobalWorkspaceRepository(
            baseRepo = baseRepo,
            sessionDao = sessionDao,
            fileEntryDao = fileEntryDao,
        )
    }

    @Test
    fun `知识库根目录包含会话时首项挂载虚拟会话工作区目录`() = runTest {
        val baseDoc = FileEntry(
            uuid = "doc-1",
            workspaceRootUuid = ragRootUuid,
            parentUuid = ragRootUuid,
            name = "知识库文档.md",
            hash = "h1",
            physicalRootPath = "/data/rag",
            materializedPath = "/知识库文档.md",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        every { baseRepo.observeChildren(ragRootUuid, ragRootUuid) } returns flowOf(listOf(baseDoc))
        every { sessionDao.observeAll() } returns flowOf(listOf(ragSession, session1, session2))

        val children = compositeRepo.observeChildren(ragRootUuid, ragRootUuid).first()

        assertThat(children).hasSize(2)
        val firstNode = children.first()
        assertThat(firstNode.uuid).isEqualTo(CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID)
        assertThat(firstNode.name).isEqualTo("会话工作区")
        assertThat(firstNode.isDirectory).isTrue()
        assertThat(children[1].uuid).isEqualTo("doc-1")
    }

    @Test
    fun `展开会话工作区时列出除知识库以外的所有会话`() = runTest {
        every { sessionDao.observeAll() } returns flowOf(listOf(ragSession, session1, session2))

        val children = compositeRepo.observeChildren(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
        ).first()

        assertThat(children).hasSize(2)
        assertThat(children.map { it.name }).containsExactly("旅行规划", "代码重构")
        assertThat(children.map { it.uuid }).containsExactly(
            CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1"),
            CompositeWorkspaceConstants.virtualSessionFolderUuid("session-2"),
        )
        assertThat(children.all { it.isDirectory }).isTrue()
    }

    @Test
    fun `展开具体会话目录时代理至该会话真实工作区根`() = runTest {
        coEvery { sessionDao.getById("session-1") } returns session1
        val sessionFile = FileEntry(
            uuid = "session-file-1",
            workspaceRootUuid = "session-1-root-uuid",
            parentUuid = "session-1-root-uuid",
            name = "行程表.xlsx",
            hash = "h_session",
            physicalRootPath = "/data/session1",
            materializedPath = "/行程表.xlsx",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        every { baseRepo.observeChildren("session-1-root-uuid", "session-1-root-uuid") } returns flowOf(listOf(sessionFile))

        val sessionVirtualUuid = CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1")
        val children = compositeRepo.observeChildren(ragRootUuid, sessionVirtualUuid).first()

        assertThat(children).hasSize(1)
        assertThat(children.first().name).isEqualTo("行程表.xlsx")
        assertThat(children.first().uuid).isEqualTo("session-file-1")
    }

    @Test
    fun `getByUuid精确解析虚拟根与虚拟会话目录`() = runTest {
        coEvery { sessionDao.getById("session-2") } returns session2

        val rootNode = compositeRepo.getByUuid(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
        )
        assertThat(rootNode).isNotNull()
        assertThat(rootNode!!.name).isEqualTo("会话工作区")
        assertThat(rootNode.isDirectory).isTrue()

        val sessionNode = compositeRepo.getByUuid(
            ragRootUuid,
            CompositeWorkspaceConstants.virtualSessionFolderUuid("session-2"),
        )
        assertThat(sessionNode).isNotNull()
        assertThat(sessionNode!!.name).isEqualTo("代码重构")
        assertThat(sessionNode.lastWriteSessionId).isEqualTo("session-2")
    }

    @Test
    fun `系统虚拟目录受写保护禁止删除`() = runTest {
        assertThrows<SecurityException> {
            compositeRepo.moveToRecycleBin(
                ragRootUuid,
                CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
            )
        }

        assertThrows<SecurityException> {
            compositeRepo.permanentDelete(
                ragRootUuid,
                CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1"),
            )
        }
    }

    @Test
    fun `系统虚拟根禁止重命名但会话目录重命名联动更新会话标题`() = runTest {
        val rootRenameResult = compositeRepo.rename(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
            "新名称",
        )
        assertThat(rootRenameResult).isInstanceOf(RenameResult.Conflict::class.java)

        val sessionVirtualUuid = CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1")
        val sessionRenameResult = compositeRepo.rename(
            ragRootUuid,
            sessionVirtualUuid,
            "东京旅行规划",
        )
        assertThat(sessionRenameResult).isInstanceOf(RenameResult.Success::class.java)
        val success = sessionRenameResult as RenameResult.Success
        assertThat(success.name).isEqualTo("东京旅行规划")
        coVerify { sessionDao.updateTitle("session-1", "东京旅行规划", any()) }
    }
}

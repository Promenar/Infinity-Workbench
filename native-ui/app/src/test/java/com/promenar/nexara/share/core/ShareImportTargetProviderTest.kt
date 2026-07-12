package com.promenar.nexara.share.core

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShareImportTargetProviderTest {
    @Test
    fun `当前聊天和全局知识库使用两个显式工作区目标`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { sessionDao.getById(CURRENT) } returns session(CURRENT, "当前聊天")
        coEvery { sessionDao.getById(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID) } returns
            session(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID, "知识库")
        coEvery { workspace.ensureSessionRoot(CURRENT) } returns root("current-root")
        coEvery { workspace.ensureSessionRoot(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID) } returns
            root("knowledge-root")

        val targets = ShareImportTargetProvider(sessionDao, workspace).load(
            currentSessionId = CURRENT,
            currentSessionTitle = "当前聊天",
            knowledgeBaseLabel = "知识库",
        )

        assertThat(targets.map { it.workspaceRootUuid }).containsExactly("current-root", "knowledge-root").inOrder()
        assertThat(targets.map { it.kind }).containsExactly(
            ShareTargetKind.CurrentSession,
            ShareTargetKind.KnowledgeBase,
        ).inOrder()
    }

    @Test
    fun `知识库系统会话缺失时创建后再认领工作区`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { sessionDao.getById(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID) } returns null
        coEvery { sessionDao.insert(any()) } returns Unit
        coEvery { workspace.ensureSessionRoot(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID) } returns
            root("knowledge-root")

        val targets = ShareImportTargetProvider(sessionDao, workspace).load(null, null, "知识库")

        assertThat(targets).hasSize(1)
        assertThat(targets.single().kind).isEqualTo(ShareTargetKind.KnowledgeBase)
        coVerify(exactly = 1) {
            sessionDao.insert(match { it.id == ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID })
        }
    }

    private fun session(id: String, title: String) = SessionEntity(
        id = id,
        agentId = "agent",
        title = title,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = "root",
        hash = "hash",
        isDirectory = true,
        physicalRootPath = "/workspace/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val CURRENT = "current-session"
    }
}

package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.FileReadSkill
import com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class MountedWorkspaceVersionTest {
    @Test fun `读取后来源改变不能把新版本配给旧正文`() = runBlocking {
        val oldBytes = "previous".toByteArray()
        val path = "/mnt/termux/a.txt"
        val uuid = "external:id:L2EudHh0"
        val newer = FileEntry(uuid, "root", null, "a.txt", Sha256Utils.hash("newer"), physicalRootPath = "", materializedPath = path, createdAt = 1, updatedAt = 2)
        val mounts = mockk<ExternalWorkspaceAccess> {
            coEvery { workspaceMount(any()) } returns null
            coEvery { visible("session") } returns emptyList()
            coEvery { entry("session", path) } returns newer
            coEvery { resolveUuid("session", uuid) } returns path
            coEvery { read("session", path, any()) } returns oldBytes
        }
        val workspace = mockk<IWorkspaceRepository>()
        val operations = mockk<IFileOperationRepository>()
        val context = object : SkillExecutionContext {
            override val sessionId = "session"
            override val agentId = "agent"
            override val workspaceRootUuid = "root"
            override val workspacePath: String? = null
        }
        val result = MountedWorkspaceSkill(FileReadSkill(operations, workspace), mounts, workspace, operations, FileIndexEventSink.None)
            .execute(Json.parseToJsonElement("""{"path":"$path"}""").jsonObject, context)
        assertThat(result.content).contains("previous")
        assertThat(result.content.substringAfterLast("sha256: ")).isEqualTo(Sha256Utils.hash(oldBytes))
    }
}

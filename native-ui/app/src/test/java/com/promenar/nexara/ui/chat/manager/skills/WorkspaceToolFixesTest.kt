package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.WorkspaceLifecycleErrorCode
import com.promenar.nexara.data.repository.WorkspaceLifecycleException
import com.promenar.nexara.domain.repository.*
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class WorkspaceToolFixesTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }
    private val workspace = mockk<IWorkspaceRepository>()
    private val files = mockk<IFileOperationRepository>()
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { workspaceMount(any()) } returns null
        coEvery { visible(any()) } returns emptyList()
    }
    private fun entry(uuid: String, path: String, directory: Boolean = false, parent: String = "root") = FileEntry(
        uuid, "root", parent, path.substringAfterLast('/'), "hash", isDirectory = directory,
        physicalRootPath = "/private/hidden", materializedPath = path, createdAt = 1, updatedAt = 1,
    )
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun wrapped(skill: SkillDefinition) = MountedWorkspaceSkill(skill, mounts, workspace, files, FileIndexEventSink.None, captureFileChanges = false)

    @Test fun `两个写工具在私有与挂载路径给出相同的结构化版本缺参指引`() = runTest {
        for (path in listOf("/a.txt", "/mnt/test/a.txt")) {
            coEvery { workspace.getByMaterializedPath("root", path) } returns entry("a", path)
            coEvery { mounts.entry(any(), path) } returns entry("external:a", path)
            for (skill in listOf(FileWriteSkill(files, workspace), FilePatchSkill(files, workspace))) {
                val result = wrapped(skill).execute(args("""{"path":"$path","content":"new","operations":[]}"""), context)
                assertThat(result.status).isEqualTo("error")
                val error = Json.parseToJsonElement(result.content).jsonObject
                assertThat(error["code"]?.jsonPrimitive?.content).isEqualTo("MISSING_VERSION")
                assertThat(error["message"]?.jsonPrimitive?.content).contains("expectedHash 或 expected_sha256")
                assertThat(error["source_tool"]?.jsonPrimitive?.content).isEqualTo("read_file")
                assertThat(error["source_fields"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly("Hash", "sha256")
            }
        }
        coVerify(exactly = 0) { files.writeFileAtomic(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { files.patchFile(any(), any(), any(), any()) }
    }

    @Test fun `写工具描述与两个版本参数均明确调用方必填契约`() {
        for (skill in listOf(FileWriteSkill(files, workspace), FilePatchSkill(files, workspace)).map(::wrapped)) {
            assertThat(skill.description).contains("必须提供 expectedHash 或 expected_sha256 之一")
            val properties = Json.parseToJsonElement(skill.parametersSchema).jsonObject.getValue("properties").jsonObject
            for (name in listOf("expectedHash", "expected_sha256")) {
                assertThat(properties.getValue(name).jsonObject.getValue("description").jsonPrimitive.content).contains("read_file")
            }
        }
    }

    @Test fun `别名版本显式透传且冲突版本不能执行写入`() = runTest {
        coEvery { workspace.getByMaterializedPath("root", "/a.txt") } returns entry("a", "/a.txt")
        coEvery { files.writeFileAtomic("root", "a", "new", "session", "caller-hash") } returns WriteResult.Success("new-hash")
        val skill = wrapped(FileWriteSkill(files, workspace))
        assertThat(skill.execute(args("""{"path":"/a.txt","content":"new","expected_sha256":"caller-hash"}"""), context).status).isEqualTo("success")
        assertThat(skill.execute(args("""{"path":"/a.txt","content":"new","expectedHash":"one","expected_sha256":"two"}"""), context).status).isEqualTo("error")
        coVerify(exactly = 1) { files.writeFileAtomic(any(), any(), any(), any(), any()) }
    }

    @Test fun `同目录改名和无改名不调用父目录变更`() = runTest {
        val source = entry("a", "/probe/a.txt", parent = "probe")
        coEvery { workspace.getByMaterializedPath("root", "/probe/a.txt") } returns source
        coEvery { workspace.getByMaterializedPath("root", "/probe") } returns entry("probe", "/probe", true)
        coEvery { workspace.getByUuid("root", "a") } returns source
        coEvery { workspace.updateParent(any(), any(), any()) } throws FileAlreadyExistsException("/private/hidden/a.txt")
        coEvery { workspace.rename("root", "a", "b.txt", "a.txt") } returns RenameResult.Success("b.txt", "hash", 2, true)
        val skill = MoveFileSkill(workspace, FileIndexEventSink.None)
        for (name in listOf("", ",\"newName\":\"a.txt\"", ",\"newName\":\"b.txt\"")) {
            assertThat(skill.execute(args("""{"path":"/probe/a.txt","newParentPath":"/probe"$name}"""), context).status).isEqualTo("success")
        }
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
        coVerify(exactly = 1) { workspace.rename(any(), any(), any(), any()) }
    }

    @Test fun `私有工作区同目录无操作不发布虚假文件变更摘要`() = runTest {
        val source = entry("a", "/probe/a.txt", parent = "probe")
        coEvery { workspace.getByMaterializedPath("root", "/probe/a.txt") } returns source
        coEvery { workspace.getByMaterializedPath("root", "/probe") } returns entry("probe", "/probe", true)
        coEvery { workspace.getByUuid("root", "a") } returns source
        val skill = MountedWorkspaceSkill(MoveFileSkill(workspace, FileIndexEventSink.None), mounts, workspace, files, FileIndexEventSink.None)
        val result = skill.execute(args("""{"path":"/probe/a.txt","newParentPath":"/probe"}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.fileChanges.orEmpty()).isEmpty()
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
        coVerify(exactly = 0) { workspace.rename(any(), any(), any(), any()) }
    }

    @Test fun `跨目录移动后改名且具体文件异常不回显物理路径`() = runTest {
        val source = entry("a", "/a.txt")
        coEvery { workspace.getByMaterializedPath("root", "/a.txt") } returns source
        coEvery { workspace.getByMaterializedPath("root", "/docs") } returns entry("docs", "/docs", true)
        coEvery { workspace.getByUuid("root", "a") } returns source.copy(name = "b.txt", materializedPath = "/docs/b.txt")
        coEvery { workspace.updateParent("root", "a", "docs") } returns Unit
        coEvery { workspace.rename("root", "a", "b.txt", "a.txt") } returns RenameResult.Success("b.txt", "hash", 2, true)
        val skill = MoveFileSkill(workspace, FileIndexEventSink.None)
        val arguments = args("""{"path":"/a.txt","newParentPath":"/docs","newName":"b.txt"}""")
        assertThat(skill.execute(arguments, context).content).contains("/docs/b.txt")
        for ((failure, reason) in listOf(
            FileAlreadyExistsException("/private/hidden/a.txt") to "目标已存在",
            NoSuchFileException("/private/hidden/a.txt") to "条目不存在",
            AccessDeniedException("/private/hidden/a.txt") to "权限不足",
            IllegalStateException("/private/hidden state") to "状态冲突",
            WorkspaceLifecycleException(WorkspaceLifecycleErrorCode.TARGET_COLLISION, "目标路径已被占用，未执行移动。") to "目标路径已被占用，未执行移动。",
            UnsupportedOperationException("/private/hidden unknown") to "UnsupportedOperationException",
        )) {
            coEvery { workspace.updateParent("root", "a", "docs") } throws failure
            val result = skill.execute(arguments, context)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains(reason)
            assertThat(result.content).doesNotContain("/private/hidden")
        }
    }
}

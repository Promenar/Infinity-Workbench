package com.promenar.nexara.data.workspace

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.data.generation.resolveSessionToolTarget
import com.promenar.nexara.data.linux.LinuxFileSystemImpl
import com.promenar.nexara.data.linux.TestLinuxFileMetadata
import com.promenar.nexara.data.linux.deleteLinuxFixture
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.SecureWorkspaceFileOps
import com.promenar.nexara.data.repository.TestWorkspaceFileOps
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.domain.repository.*
import com.promenar.nexara.domain.storage.*
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.registry.*
import com.promenar.nexara.ui.chat.manager.skills.*
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test

class LinuxSessionFileSkillTest {
    private val temp = Files.createTempDirectory("linux-session-files").toFile().canonicalFile
    private val privateFileOps = TestWorkspaceFileOps()
    private val roots = listOf("one", "two").associateWith { session ->
        val directory = File(temp, session).apply { mkdirs() }
        val identity = privateFileOps.ensureRoot(directory.toPath())
        entry("root-$session", "/", directory.path, directory = true, hash = identity)
    }
    private val entries = roots.values.associateBy { it.uuid }.toMutableMap()
    private val workspace = mockk<IWorkspaceRepository> {
        coEvery { ensureSessionRoot(any()) } answers { roots.getValue(firstArg()) }
        coEvery { getByUuid(any(), any()) } answers { entries[secondArg<String>()]?.takeIf { it.workspaceRootUuid == firstArg<String>() } }
        coEvery { getByMaterializedPath(any(), any()) } answers { entries.values.singleOrNull { it.workspaceRootUuid == firstArg<String>() && it.materializedPath == secondArg<String>() } }
        every { observeChildren(any(), any()) } answers { flowOf(entries.values.filter { it.workspaceRootUuid == firstArg<String>() && it.parentUuid == secondArg<String>() }) }
    }
    private val files = mockk<IFileOperationRepository>()
    private val skills = ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, "skills", ExternalMountPaths.SKILLS_SOURCE, "agent_skills", "one", 0)
    private var mountId = "docs-id"
    private val externalDirectory = File(temp, "external").apply { mkdirs() }
    private var selected: SessionWorkspace = SessionWorkspace.Private
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { visible(any()) } answers { listOf(ExternalMount(mountId, "docs", "real_path", externalDirectory.path, firstArg(), 1), ExternalMount("saf-id", "saf", "saf_tree", "content://test", firstArg(), 1), skills) }
        coEvery { workspaceMount(any()) } answers { (selected as? SessionWorkspace.Directory)?.let { ExternalMount("ws_${it.workspace.id}", "", "workspace", it.workspace.id, firstArg(), 0) } }
        coEvery { entry(any(), any()) } answers {
            val path = secondArg<String>()
            val content = externalContent(path)
            val mount = if (path.startsWith("/skills")) skills else if (path.startsWith("/mnt/docs")) ExternalMount(mountId, "docs", "real_path", "/test/docs", firstArg(), 1)
                else ExternalMount("ws_project", "", "workspace", "project", firstArg(), 0)
            val relative = if (path.startsWith("/mnt/docs")) path.removePrefix("/mnt/docs").ifEmpty { "/" } else if (path.startsWith("/skills")) path.removePrefix("/skills").ifEmpty { "/" } else path
            entry(ExternalMountPaths.uuid(mount, relative), path, "", directory = content == null, hash = content?.let(Sha256Utils::hash).orEmpty())
        }
        coEvery { read(any(), any(), any()) } answers { externalContent(secondArg())!!.toByteArray() }
        coEvery { list(any(), any()) } returns emptyList()
    }
    private val rootfsDirectory = File(temp, "rootfs").apply { mkdirs() }
    private val fs = LinuxFileSystemImpl(rootfsDirectory, { true }, TestLinuxFileMetadata)
    private val access = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs))
    private var mode: SessionRunMode? = SessionRunMode.LINUX

    init {
        // macOS 不提供 SecureDirectoryStream；此夹具只验证路由，FD 安全由现有 Android 夹具覆盖。
        mockkConstructor(SecureWorkspaceFileOps::class)
        every { anyConstructed<SecureWorkspaceFileOps>().readLimited(any(), any(), any()) } answers {
            privateFileOps.readLimited(firstArg(), secondArg(), thirdArg())
        }
        privateFile("one", "same.txt", "one-private")
        privateFile("two", "same.txt", "two-private")
        File(rootfsDirectory, "etc/config").apply { parentFile!!.mkdirs(); writeText("rootfs-data") }
    }

    @After fun cleanup() { unmockkConstructor(SecureWorkspaceFileOps::class); deleteLinuxFixture(temp) }

    @Test fun `所有Linux路径读取返回Linux路径且保持私有会话隔离`() = runBlocking {
        val read = skill(FileReadSkill(files, workspace))
        mapOf("same.txt" to "one-private", "/workspace/same.txt" to "one-private", "/home/session/same.txt" to "one-private", "/mnt/docs/a.txt" to "external-data", "/mnt/skills/a.md" to "skill-data", "/etc/config" to "rootfs-data").forEach { (path, content) ->
            val result = read.execute(json("path" to path), context())
            assertWithMessage("路径=%s，结果=%s", path, result.content).that(result.status).isEqualTo("success")
            assertThat(result.content).contains(content)
            assertThat(result.content).contains("路径: ${LinuxSessionPaths.normalize(path)}")
        }
        val other = read.execute(json("path" to "/home/session/same.txt"), context("two"))
        assertThat(other.content).contains("two-private")
        val foreign = read.execute(json("uuid" to "file-two-same.txt"), context())
        assertThat(foreign.status).isEqualTo("error")
        coVerify(exactly = 0) { files.readFileRange(any(), any(), any(), any()) }
    }

    @Test fun `项目workspace与home session分别访问项目及Room树`() = runBlocking {
        selectProject()
        val read = skill(FileReadSkill(files, workspace))
        assertThat(read.execute(json("path" to "/workspace/same.txt"), context()).content).contains("project-data")
        assertThat(read.execute(json("path" to "/home/session/same.txt"), context()).content).contains("one-private")
        val rawUuid = read.execute(json("uuid" to "file-one-same.txt"), context())
        assertThat(rawUuid.content).contains("路径: /home/session/same.txt")
        assertThat(access.list("one", "/mnt").map { it.name }).containsExactly("docs", "skills")
        assertThat(read.execute(json("path" to "/mnt/saf/a.txt"), context()).status).isEqualTo("error")
        coVerify(exactly = 0) { mounts.read(any(), match { it.startsWith("/mnt/saf") }, any()) }
    }

    @Test fun `私有覆盖写经原事务且已批准Linux路径不被换成其它目标`() = runBlocking {
        val original = FileWriteSkill(files, workspace)
        val args = json("path" to "/home/session/same.txt", "content" to "new", "expectedHash" to Sha256Utils.hash("one-private"))
        val target = access.executionTarget("one", original.toProtocolTool(), args)!!
        coEvery { files.writeFileAtomicExact("root-one", "file-one-same.txt", "new", "one", Sha256Utils.hash("one-private")) } answers {
            File(roots.getValue("one").physicalRootPath, "same.txt").writeText("new")
            entries["file-one-same.txt"] = entries.getValue("file-one-same.txt").copy(hash = Sha256Utils.hash("new"), sizeBytes = 3)
            WriteResult.Success(Sha256Utils.hash("new"))
        }
        val result = skill(original).execute(args, context(approved = target))
        assertThat(result.status).isEqualTo("success")
        coVerify(exactly = 1) { files.writeFileAtomicExact("root-one", "file-one-same.txt", "new", "one", Sha256Utils.hash("one-private")) }
        val different = skill(original).execute(JsonObject(args + json("path" to "/workspace/same.txt")), context(approved = target))
        assertThat(different.status).isEqualTo("error")
        assertThat(different.content).contains("CONFLICT")
    }

    @Test fun `审批绑定命名空间工作区与挂载身份且rootfs变更携带命令策略标记`() = runBlocking {
        val write = FileWriteSkill(files, workspace)
        val args = json("path" to "/etc/config", "content" to "updated", "expectedHash" to Sha256Utils.hash("rootfs-data"))
        val target = access.executionTarget("one", write.toProtocolTool(), args)!!
        assertThat(target).contains("\"linuxRootfs\":true")
        assertThat(target).contains("\"oneShot\":true")
        val written = skill(write).execute(args, context(approved = target))
        assertWithMessage("结果=%s", written.content).that(written.status).isEqualTo("success")
        assertThat(File(rootfsDirectory, "etc/config").readText()).isEqualTo("updated")
        val read = FileReadSkill(files, workspace)
        val mountedArgs = json("path" to "/mnt/docs/a.txt")
        val mountedTarget = access.executionTarget("one", read.toProtocolTool(), mountedArgs)!!
        mountId = "other-docs-id"
        assertThat(skill(read).execute(mountedArgs, context(approved = mountedTarget)).status).isEqualTo("error")
        val privateArgs = json("path" to "/workspace/same.txt")
        val privateTarget = access.executionTarget("one", read.toProtocolTool(), privateArgs)!!
        selectProject()
        assertThat(skill(read).execute(privateArgs, context(approved = privateTarget)).status).isEqualTo("error")
    }

    @Test fun `严格拒绝跨命名空间点片段和符号链接`() = runBlocking {
        Files.createSymbolicLink(File(rootfsDirectory, "etc/escape").toPath(), File("../../outside").toPath())
        Files.createSymbolicLink(File(rootfsDirectory, "etc/bound").toPath(), File("/home/session/same.txt").toPath())
        Files.createSymbolicLink(File(roots.getValue("one").physicalRootPath, "escape").toPath(), File(roots.getValue("two").physicalRootPath, "same.txt").toPath())
        entries["escape"] = entry("escape", "/escape", roots.getValue("one").physicalRootPath, root = "root-one", hash = "untrusted")
        val read = skill(FileReadSkill(files, workspace))
        listOf("/etc/escape", "/etc/bound", "/home/session/escape", "/workspace/../etc/config", "/mnt/docs/../../etc/config").forEach { path ->
            assertThat(read.execute(json("path" to path), context()).status).isEqualTo("error")
        }
    }

    @Test fun `技能逐次审批及BUILTIN原始参数与结果完整委派`() = runBlocking {
        val write = FileWriteSkill(files, workspace)
        val args = json("path" to "/mnt/skills/a.md", "content" to "new", "expectedHash" to Sha256Utils.hash("skill-data"))
        assertThat(access.executionTarget("one", write.toProtocolTool(), args)).contains("\"linuxSkills\":true")
        assertThat(skill(write).execute(args, context()).content).contains("APPROVAL_REQUIRED")
        val original = mockk<SkillDefinition> {
            every { id } returns "read_file"
            every { description } returns "原始工具"
            every { parametersSchema } returns "{}"
            coEvery { execute(any(), any()) } returns ToolResult("builtin", "原始返回")
        }
        mode = SessionRunMode.BUILTIN
        val context = context()
        val builtinArgs = json("path" to "unchanged/../path")
        assertThat(skill(original).execute(builtinArgs, context)).isEqualTo(ToolResult("builtin", "原始返回"))
        coVerify(exactly = 1) { original.execute(builtinArgs, context) }
        mode = null
        assertThat(skill(original).execute(builtinArgs, context).status).isEqualTo("error")
        coVerify(exactly = 1) { original.execute(builtinArgs, context) }
    }

    @Test fun `会话审批解析器共享同一Linux目标且缺少适配时拒绝回退`() = runBlocking {
        val session = Session(id = "one", agentId = "agent", runMode = SessionRunMode.LINUX)
        val tool = FileReadSkill(files, workspace).toProtocolTool()
        val args = json("path" to "same.txt")
        assertThat(resolveSessionToolTarget(session, tool, args.toString(), workspace, mounts, linuxFiles = access))
            .isEqualTo(access.executionTarget("one", tool, args))
        assertThat(resolveSessionToolTarget(session, tool, args.toString(), workspace, mounts)).isNull()
    }

    @Test fun `同路径重建真实挂载和当前项目目录均不能继承已批准目标`() = runBlocking {
        val read = FileReadSkill(files, workspace)
        val mountedArgs = json("path" to "/mnt/docs/a.txt")
        val mountedTarget = access.executionTarget("one", read.toProtocolTool(), mountedArgs)!!
        Files.move(externalDirectory.toPath(), File(temp, "external-original").toPath())
        externalDirectory.mkdir()
        assertThat(skill(read).execute(mountedArgs, context(approved = mountedTarget)).status).isEqualTo("error")
        coVerify(exactly = 0) { mounts.read(any(), "/mnt/docs/a.txt", any()) }
        selectProject()
        val projectArgs = json("path" to "/workspace/same.txt")
        val projectTarget = access.executionTarget("one", read.toProtocolTool(), projectArgs)!!
        val project = (selected as SessionWorkspace.Directory).root
        Files.move(project.toPath(), File(temp, "project-original").toPath())
        project.mkdir()
        assertThat(skill(read).execute(projectArgs, context(approved = projectTarget)).status).isEqualTo("error")
        coVerify(exactly = 0) { mounts.read(any(), "/same.txt", any()) }
    }

    @Test fun `私有文件的workspace与home别名共享版本快照`() = runBlocking {
        val old = access.read("one", "/workspace/same.txt")
        File(roots.getValue("one").physicalRootPath, "same.txt").writeText("new-private")
        entries["file-one-same.txt"] = entries.getValue("file-one-same.txt").copy(hash = Sha256Utils.hash("new-private"))
        assertThat(access.version("one", "/home/session/same.txt", Sha256Utils.hash(old))?.toString(Charsets.UTF_8)).isEqualTo("one-private")
    }

    @Test fun `模式快照已改变时不执行任何文件访问`() = runBlocking {
        val original = mockk<SkillDefinition> {
            every { id } returns "read_file"
            every { description } returns "原始工具"
            every { parametersSchema } returns "{}"
        }
        mode = SessionRunMode.BUILTIN
        val result = skill(original).execute(json("path" to "/etc/config"), context(expectedMode = SessionRunMode.LINUX))
        assertThat(result.content).contains("EXEC_MODE_CHANGED")
        coVerify(exactly = 0) { original.execute(any(), any()) }
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test fun `新命名空间实例不依赖RAM快照而使用Room持久basisHash`() = runBlocking {
        val basis = Sha256Utils.hash("persisted-old-version")
        val result = DiffResult("file-one-same.txt", basis, Sha256Utils.hash("one-private"), listOf(DiffHunk(1, 1, 1, 1, listOf(DiffLine("-", "persisted-old-version"), DiffLine("+", "one-private")))))
        coEvery { files.diffFile("root-one", "file-one-same.txt", basis) } returns result
        val fresh = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs))
        val skill = LinuxSessionFileSkill(FileDiffSkill(files, workspace), { mode }, fresh, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
        val diff = skill.execute(json("path" to "/home/session/same.txt", "basisHash" to basis), context())
        assertWithMessage("结果=%s", diff.content).that(diff.status).isEqualTo("success")
        assertThat(diff.content).contains("persisted-old-version")
        coVerify(exactly = 1) { files.diffFile("root-one", "file-one-same.txt", basis) }
    }

    @Test fun `同一私有树跨Linux别名移动保留Room UUID与历史`() = runBlocking {
        val root = roots.getValue("one")
        entries["docs-one"] = entry("docs-one", "/docs", root.physicalRootPath, root = root.uuid, directory = true)
        coEvery { workspace.relocate("root-one", "file-one-same.txt", "docs-one", "renamed.txt", "same.txt", null) } answers {
            val previous = entries.getValue("file-one-same.txt")
            entries["file-one-same.txt"] = previous.copy(parentUuid = "docs-one", name = "renamed.txt", materializedPath = "/docs/renamed.txt")
            RenameResult.Success("renamed.txt", previous.hash, previous.updatedAt, true)
        }
        val moved = skill(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(json("path" to "/workspace/same.txt", "newParentPath" to "/home/session/docs", "newName" to "renamed.txt"), context())
        assertWithMessage("结果=%s", moved.content).that(moved.status).isEqualTo("success")
        assertThat(moved.content).contains("/home/session/docs/renamed.txt")
        assertThat(entries.getValue("file-one-same.txt").materializedPath).isEqualTo("/docs/renamed.txt")
        coVerify(exactly = 1) { workspace.relocate("root-one", "file-one-same.txt", "docs-one", "renamed.txt", "same.txt", null) }
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
        coVerify(exactly = 0) { workspace.rename(any(), any(), any(), any()) }
        coVerify(exactly = 0) { workspace.moveToRecycleBin(any(), any()) }
        val basis = Sha256Utils.hash("old-version")
        coEvery { files.diffFile("root-one", "file-one-same.txt", basis) } returns DiffResult("file-one-same.txt", basis, entries.getValue("file-one-same.txt").hash, emptyList())
        assertThat(skill(FileDiffSkill(files, workspace)).execute(json("path" to "/home/session/docs/renamed.txt", "basisHash" to basis), context()).status).isEqualTo("success")
        coVerify(exactly = 1) { files.diffFile("root-one", "file-one-same.txt", basis) }
    }

    @Test fun `无扩展rootfs配置可读取但NUL与非法UTF8仍拒绝`() = runBlocking {
        File(rootfsDirectory, "etc/profile").writeText("export PATH=/usr/bin")
        File(rootfsDirectory, "etc/binary").writeBytes(byteArrayOf(65, 0, 66))
        File(rootfsDirectory, "etc/invalid").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val read = skill(FileReadSkill(files, workspace))
        val profile = read.execute(json("path" to "/etc/profile"), context())
        assertWithMessage("结果=%s", profile.content).that(profile.status).isEqualTo("success")
        assertThat(profile.content).contains("export PATH=/usr/bin")
        listOf("/etc/binary", "/etc/invalid").forEach { path ->
            val result = read.execute(json("path" to path), context())
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("UTF-8")
        }
    }

    @Test fun `私有文本新建在Room登记文本MIME避免后续写入被误判二进制`() = runBlocking {
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val sink = java.io.ByteArrayOutputStream()
            arg<(java.io.OutputStream) -> Unit>(7).invoke(sink)
            val content = sink.toByteArray()
            val uuid = secondArg<String>()
            val created = entry(uuid, arg(5), roots.getValue("one").physicalRootPath, root = "root-one", hash = Sha256Utils.hash(content))
                .copy(mimeType = arg(3), sizeBytes = content.size.toLong())
            entries[uuid] = created
            File(created.physicalRootPath, created.materializedPath.removePrefix("/")).writeBytes(content)
            created
        }
        coEvery { workspace.confirmCreatedEntry(any(), any()) } returns Unit
        val result = skill(CreateFileSkill(workspace, files)).execute(json("name" to "profile", "content" to "export A=1"), context())
        assertWithMessage("结果=%s", result.content).that(result.status).isEqualTo("success")
        val created = entries.values.single { it.materializedPath == "/profile" }
        assertThat(created.mimeType).isEqualTo("text/plain")
        assertThat(created.hash).isEqualTo(Sha256Utils.hash("export A=1"))
    }

    private fun skill(original: SkillDefinition) = LinuxSessionFileSkill(original, { mode }, access, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
    private fun context(session: String = "one", approved: String? = null, expectedMode: SessionRunMode? = null) = object : SkillExecutionContext {
        override val sessionId = session
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-$session"
        override val approvedExecutionTarget = approved
        override val isApprovalBoundExecution = approved != null
        override val runMode = expectedMode
    }
    private fun selectProject() {
        selected = SessionWorkspace.Directory(WorkspaceEntity("project", "project", "项目", "project", 1), File(temp, "project").apply { mkdirs() })
    }
    private fun privateFile(session: String, name: String, content: String) {
        val root = roots.getValue(session)
        File(root.physicalRootPath, name).writeText(content)
        entries["file-$session-$name"] = entry("file-$session-$name", "/$name", root.physicalRootPath, root = root.uuid, hash = Sha256Utils.hash(content)).copy(sizeBytes = content.toByteArray().size.toLong())
    }
    private fun externalContent(path: String): String? = when (path) {
        "/mnt/docs/a.txt" -> "external-data"
        "/skills/a.md" -> "skill-data"
        "/same.txt" -> "project-data"
        else -> null
    }
    private fun json(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })
    private fun entry(uuid: String, path: String, physical: String, root: String = uuid, directory: Boolean = false, hash: String = "") = FileEntry(
        uuid, root, if (path == "/") null else root, path.substringAfterLast('/'), hash, isDirectory = directory,
        physicalRootPath = physical, materializedPath = path, createdAt = 1, updatedAt = 1,
    )
}

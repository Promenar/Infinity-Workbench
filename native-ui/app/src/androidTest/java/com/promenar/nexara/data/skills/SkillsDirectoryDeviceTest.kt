package com.promenar.nexara.data.skills

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.data.exec.AndroidProcessGroupSignaller
import com.promenar.nexara.data.exec.BuiltinCommandRunner
import com.promenar.nexara.data.exec.BuiltinExecEnvironment
import com.promenar.nexara.data.exec.BuiltinExecService
import com.promenar.nexara.data.exec.PackagedPython
import com.promenar.nexara.data.exec.SystemCaBundle
import com.promenar.nexara.data.generation.resolveSessionToolTarget
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.FileOperationRepository
import com.promenar.nexara.data.repository.WorkspaceDeletionTransaction
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.data.storage.ExternalFolderManager
import com.promenar.nexara.data.workspace.AndroidSharedWorkspaceAccess
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.toProtocolTool
import com.promenar.nexara.ui.chat.manager.skills.CreateDirectorySkill
import com.promenar.nexara.ui.chat.manager.skills.CreateFileSkill
import com.promenar.nexara.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.nexara.ui.chat.manager.skills.FileListSkill
import com.promenar.nexara.ui.chat.manager.skills.FileReadSkill
import com.promenar.nexara.ui.chat.manager.skills.MountedWorkspaceSkill
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用户级技能目录位于 filesDir（/data/user/0 系统符号链接之下）：文件工具经真实 fd 目录后端访问 /skills，
 * 技能目录仓库在设备上扫描与读取，shell 通过 NEXARA_SKILLS 与 ~/.agents/skills 看到同一目录。
 */
@RunWith(AndroidJUnit4::class)
class SkillsDirectoryDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: NexaraDatabase
    private lateinit var base: File
    private lateinit var skillsRoot: File
    private lateinit var privateRoot: FileEntry
    private lateinit var repository: WorkspaceRepository
    private lateinit var resolver: SessionWorkspaceResolver
    private lateinit var manager: ExternalFolderManager

    private val execution = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid: String get() = privateRoot.uuid
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        val id = UUID.randomUUID()
        base = File(context.filesDir, "skills-device-$id").apply { mkdirs() }
        // 故意使用未规范化的 filesDir 路径，与应用构造技能目录的方式相同。
        skillsRoot = File(context.filesDir, "skills-device-root-$id")
        val projects = File(base, "projects")
        File(projects, "demo").mkdirs()
        repository = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        val now = System.currentTimeMillis()
        db.workspaceDao().register(WorkspaceEntity("ws", WorkspaceEntity.KIND_PROJECT, "演示", "demo", now))
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = File(base, "private").apply { mkdirs() }.path, workspaceId = "ws", createdAt = now, updatedAt = now))
        privateRoot = repository.ensureSessionRoot("s")
        resolver = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), projects, AndroidSharedWorkspaceAccess(context))
        manager = ExternalFolderManager(context, db.externalMountDao(), repository, FileIndexEventSink.None, resolver, userSkillsDirectory = skillsRoot)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
        skillsRoot.deleteRecursively()
    }

    private fun skill(original: SkillDefinition) = Mounted(original)
    private fun args(json: String) = json

    /** 技能目录变更逐次审批：按生成运行时相同的方式解析审批目标，再以审批绑定的上下文执行。 */
    private inner class Mounted(private val original: SkillDefinition) {
        suspend fun execute(raw: String, context: SkillExecutionContext): com.promenar.nexara.data.model.ToolResult {
            val session = Session("s", "agent", workspaceRootUuid = privateRoot.uuid)
            val target = resolveSessionToolTarget(session, original.toProtocolTool(), raw, repository, manager)
            val approved = object : SkillExecutionContext by context {
                override val approvedExecutionTarget = target
                override val isApprovalBoundExecution = true
            }
            return MountedWorkspaceSkill(original, manager, repository, FileOperationRepository(db.fileEntryDao(), db.fileVersionDao()), FileIndexEventSink.None)
                .execute(Json.parseToJsonElement(raw).jsonObject, approved)
        }
    }

    @Test
    fun fileToolsAndCatalogUseSkillsDirectoryUnderFilesDir() = runBlocking<Unit> {
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())
        val directory = skill(CreateDirectorySkill(repository)).execute(args("""{"parentPath":"/skills","name":"demo"}"""), execution)
        assertWithMessage(directory.content).that(directory.status).isEqualTo("success")
        val markdown = "---\\nname: demo\\ndescription: 设备测试技能\\n---\\n\\n设备正文\\n"
        val created = skill(CreateFileSkill(repository, files)).execute(
            args("""{"parentPath":"/skills/demo","name":"SKILL.md","content":"$markdown"}"""), execution,
        )
        assertWithMessage(created.content).that(created.status).isEqualTo("success")
        assertThat(File(skillsRoot, "demo/SKILL.md").readText()).contains("name: demo")
        assertThat(skill(FileReadSkill(files, repository)).execute(args("""{"path":"/skills/demo/SKILL.md"}"""), execution).content)
            .contains("设备正文")
        assertThat(skill(FileListSkill(repository)).execute(args("""{"path":"/"}"""), execution).content).contains("/skills")
        assertThat(skill(DeleteFileSkill(repository)).execute(args("""{"path":"/skills"}"""), execution).status).isEqualTo("error")
        assertThat(skillsRoot.isDirectory).isTrue()

        val prefs = context.getSharedPreferences("skills-device-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val catalog = FileSkillCatalog(skillsRoot, prefs, File(base, "skill_state"))
        val found = catalog.scan(null).skills.single()
        assertWithMessage(found.brokenReason.toString()).that(found.status).isEqualTo(SkillStatus.DISABLED)
        catalog.setEnabled(found, true)
        assertThat(catalog.scan(null).skills.single().status).isEqualTo(SkillStatus.ENABLED)
        assertThat(catalog.readFile(found, "SKILL.md")).contains("设备正文")
        prefs.edit().clear().commit()
    }

    @Test
    fun shellReachesSkillsThroughEnvironmentAndHomeLink() = runBlocking<Unit> {
        File(skillsRoot, "demo").apply { mkdirs() }.resolve("SKILL.md").writeText("---\nname: demo\ndescription: d\n---\n")
        val service = BuiltinExecService(
            workspace = repository,
            runner = BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(base, "scratch") },
            ),
            environment = BuiltinExecEnvironment(
                PackagedPython.from(context), File(base, "home"), File(base, "tmp"), SystemCaBundle(File(base, "ca"))::ensure,
                skillsDirectory = skillsRoot,
            ),
            sessionWorkspace = resolver::resolve,
        )
        val result = Json.parseToJsonElement(service.runShell(
            privateRoot.uuid, "s",
            "head -2 \"\$NEXARA_SKILLS/demo/SKILL.md\" | tail -1; head -2 ~/.agents/skills/demo/SKILL.md | tail -1",
            null, 60,
        ).toJson().toString()).jsonObject
        assertWithMessage(result.toString()).that(result.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(result.getValue("stdout").jsonPrimitive.content.lines().take(2)).containsExactly("name: demo", "name: demo")
    }
}

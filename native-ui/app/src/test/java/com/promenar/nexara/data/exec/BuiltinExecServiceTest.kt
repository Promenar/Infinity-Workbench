package com.promenar.nexara.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.repository.WorkspaceChangeReport
import com.promenar.nexara.data.repository.WorkspaceCommandHost
import com.promenar.nexara.data.repository.WorkspaceCommandOutcome
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.PythonExecSkill
import com.promenar.nexara.ui.chat.manager.skills.ShellExecSkill
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuiltinExecServiceTest {
    private lateinit var work: File
    private lateinit var workspace: File
    private var changes: WorkspaceChangeReport? = WorkspaceChangeReport()
    private var reconcileError: String? = null
    private val sessions = mutableListOf<String?>()

    private val host = object : WorkspaceCommandHost {
        override suspend fun <T> runCommandInWorkspace(
            workspaceRootUuid: String,
            sessionId: String?,
            block: suspend (File) -> T,
        ): WorkspaceCommandOutcome<T> {
            sessions += sessionId
            if (workspaceRootUuid == "missing") throw SecurityException("工作区根不存在或无效")
            return WorkspaceCommandOutcome(block(workspace), changes, reconcileError)
        }
    }

    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    @Before
    fun setUp() {
        assumeTrue("需要 /usr/bin/perl 建立进程组", File("/usr/bin/perl").canExecute())
        work = Files.createTempDirectory("builtin-exec").toFile()
        workspace = File(work, "workspace").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::work.isInitialized) work.deleteRecursively()
    }

    private fun service(pythonDir: File = File(work, "no-python")) = BuiltinExecService(
        workspace = host,
        runner = BuiltinCommandRunner(
            groupLauncher = listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"),
            shell = "/bin/sh",
            signaller = { group, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$group").start().waitFor() },
            scratchDirectory = { File(work, "scratch") },
            killGraceMillis = 300,
        ),
        environment = BuiltinExecEnvironment(
            python = PackagedPython(pythonDir),
            homeDirectory = File(work, "home"),
            tempDirectory = File(work, "tmp"),
            caBundle = { null },
            inherited = mapOf("PATH" to "/usr/bin:/bin", "SECRET_TOKEN" to "x"),
        ),
        shell = "/bin/sh",
        unavailablePythonAbi = { "x86" },
    )

    private fun json(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject
    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (key, value) ->
        key to if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString())
    })

    @Test
    fun `shell_exec 在工作区根运行并返回统一结果格式`() = runBlocking<Unit> {
        changes = WorkspaceChangeReport(added = listOf("/out.txt"))
        val result = ShellExecSkill(service()).execute(
            args("command" to "pwd; echo \"\$NEXARA_BACKEND:\${SECRET_TOKEN:-unset}:\$HOME\" ; cat; echo err >&2; exit 4", "stdin" to "in"),
            context,
        )
        val value = json(result.content)
        assertThat(result.status).isEqualTo("error")
        assertThat(value.keys.take(4)).containsExactly("error_code", "guidance", "exit_code", "timed_out").inOrder()
        assertThat(value.getValue("exit_code").jsonPrimitive.int).isEqualTo(4)
        val stdout = value.getValue("stdout").jsonPrimitive.content.lines()
        assertThat(stdout[0]).isEqualTo("\$NEXARA_WORKSPACE")
        assertThat(stdout[1]).isEqualTo("builtin:unset:${File(work, "home").path}")
        assertThat(stdout[2]).isEqualTo("in")
        assertThat(value.getValue("stderr").jsonPrimitive.content).isEqualTo("err\n")
        assertThat(value.getValue("truncation").jsonPrimitive.content).isEqualTo("none")
        val workspaceChanges = value.getValue("workspace_changes").jsonObject
        assertThat(workspaceChanges.getValue("added").jsonArray.map { it.jsonPrimitive.content }).containsExactly("/out.txt")
        assertThat(sessions).containsExactly("session")
    }

    @Test
    fun `退出码为 0 记为成功，命令中的位置参数不含命令本身`() = runBlocking<Unit> {
        val result = ShellExecSkill(service()).execute(args("command" to "echo \"[\$#][\$0]\""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(json(result.content).getValue("stdout").jsonPrimitive.content).isEqualTo("[0][sh]\n")
    }

    @Test
    fun `没有内置 Python 时 shell 中的 python3 给出指引，python_exec 返回不可用错误`() = runBlocking<Unit> {
        val shell = json(ShellExecSkill(service()).execute(args("command" to "python3 -V"), context).content)
        assertThat(shell.getValue("exit_code").jsonPrimitive.int).isEqualTo(127)
        assertThat(shell.getValue("stderr").jsonPrimitive.content).contains("termux_exec")
        val python = PythonExecSkill(service()).execute(args("code" to "print(1)"), context)
        assertThat(python.status).isEqualTo("error")
        val value = json(python.content)
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_PYTHON_UNAVAILABLE")
        assertThat(value.getValue("guidance").jsonPrimitive.content).contains("x86")
    }

    @Test
    fun `超时返回 EXEC_TIMEOUT 且退出码为空`() = runBlocking<Unit> {
        val value = json(ShellExecSkill(service()).execute(args("command" to "sleep 30", "timeout_seconds" to 1), context).content)
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_TIMEOUT")
        assertThat(value.getValue("timed_out").jsonPrimitive.boolean).isTrue()
        assertThat(value.getValue("exit_code").jsonPrimitive.content).isEqualTo("null")
    }

    @Test
    fun `对账失败与工作区不可用分别给出错误码`() = runBlocking<Unit> {
        changes = null
        reconcileError = "工作区索引对账失败（IOException）"
        val failed = json(ShellExecSkill(service()).execute(args("command" to "true"), context).content)
        assertThat(failed.getValue("error_code").jsonPrimitive.content).isEqualTo("WORKSPACE_RECONCILE_FAILED")
        assertThat(failed.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(failed.getValue("workspace_changes").toString()).isEqualTo("null")

        val missing = object : SkillExecutionContext by context { override val workspaceRootUuid = "missing" }
        val unavailable = json(ShellExecSkill(service()).execute(args("command" to "true"), missing).content)
        assertThat(unavailable.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_WORKSPACE_UNAVAILABLE")
    }

    @Test
    fun `参数校验失败不启动进程`() = runBlocking<Unit> {
        listOf(
            args("command" to " "),
            args("command" to "ls", "timeout_seconds" to 601),
            args("command" to "x".repeat(32_001)),
            args("command" to "ls", "workdir" to "/"),
        ).forEach { invalid ->
            val value = json(ShellExecSkill(service()).execute(invalid, context).content)
            assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_INVALID_ARGUMENTS")
        }
        val tooLong = json(PythonExecSkill(service()).execute(args("code" to "中".repeat(40_001)), context).content)
        assertThat(tooLong.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_INVALID_ARGUMENTS")
        assertThat(sessions).isEmpty()
    }

    @Test
    fun `变化列表超过 200 项时截断并给出计数，保留路径与特殊条目给出警告`() {
        val report = WorkspaceChangeReport(
            added = (1..150).map { "/a$it" },
            modified = (1..100).map { "/m$it" },
            reservedChanges = listOf("/.recycle_bin"),
            unsupported = listOf("/link"),
        )
        val fields = BuiltinExecService.workspaceFields(report)
        val changesJson = fields.getValue("workspace_changes").jsonObject
        assertThat(changesJson.getValue("added").jsonArray).hasSize(150)
        assertThat(changesJson.getValue("modified").jsonArray).hasSize(50)
        assertThat(changesJson.getValue("counts").jsonObject.getValue("modified").jsonPrimitive.int).isEqualTo(100)
        assertThat(changesJson.getValue("truncated").jsonPrimitive.boolean).isTrue()
        val warnings = fields.getValue("workspace_warnings").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content }
        assertThat(warnings).containsExactly("WORKSPACE_RESERVED_PATH", "WORKSPACE_UNSUPPORTED_ENTRY").inOrder()
    }

    @Test
    fun `输出中的工作区物理路径替换为环境变量`() {
        val root = File(work, "ws")
        assertThat(BuiltinExecService.virtualize("${root.path}/a.txt and ${root.path}", root))
            .isEqualTo("\$NEXARA_WORKSPACE/a.txt and \$NEXARA_WORKSPACE")
    }
}

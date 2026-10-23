package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.exec.BuiltinCommandRunner
import com.promenar.nexara.data.exec.BuiltinExecService
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.CommandToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID

private const val WORKSPACE_NOTE = "background:true 立即返回后台任务号，任务不随生成取消；max_runtime_minutes 默认120、上限1440，后台不使用 timeout_seconds；on_exit 为 none/notify/wake，默认 notify，长任务优先 wake。私有根后台任务在快照中运行，结束合入，冲突保留到 jobs/；运行中日志通过状态查询发布。Play版只在应用前台保证运行。" +
    "命令直接读写会话工作区：删除或覆盖的文件不进回收站、无法恢复；结束后自动对账，结果的 workspace_changes 列出新增、修改、删除与移动的工作区路径。" +
    "工作目录即工作区根，命令中请用相对路径或 \$NEXARA_WORKSPACE；/mnt/<别名> 挂载对命令不可见，需要时先用文件工具复制到工作区。" +
    "文件工具中的 /skills 在 shell 与 Python 中对应环境变量 NEXARA_SKILLS（shell 用 \$NEXARA_SKILLS，Python 用 os.environ['NEXARA_SKILLS']），亦可通过 ~/.agents/skills 符号链接访问；已有同名条目冲突时保留原条目，请用环境变量。" +
    "项目级技能位于工作区内 .agents/skills/，请用相对路径访问。" +
    "会话使用自选工作区（项目文件夹或共享文件夹）时，命令直接在该目录运行，不对账，结果不含 workspace_changes，可用 git status 查看改动。" +
    "timeout_seconds 默认 60，最多 600；超时或结束时结束整个进程组（含后台进程）；Nexara 被系统暂停的时间不计入超时（结果给出 paused_ms）。" +
    "用户可在通知栏停止命令（error_code 为 EXEC_STOPPED，不要自动重试）。stdout、stderr 各保留尾部 32000 字符。"

/** 内置后端的 sh 命令，附带 python3、git（dulwich）与侧载渠道的 pip 函数；需要 apt 包、编译工具链或原生 Python 扩展时改用 Termux 或 SSH。 */
class ShellExecSkill(private val service: BuiltinExecService, private val jobs: com.promenar.nexara.data.jobs.BackgroundJobManager? = null) : SkillDefinition {
    override val id = "shell_exec"
    override val name = id
    override val risk = ToolRisk.PROCESS
    override val description = "在本机用系统 /system/bin/sh 执行命令（POSIX sh 与系统 toybox，可用命令随设备不同），无需 Termux；" +
        "python3/python 调用内置 Python 3.14（仅 arm64 设备）；git 为内置 dulwich 包装，支持 clone/status/add/commit/log/diff/branch/checkout/pull/push 等常用子命令，" +
        "远端只支持 HTTPS，提交前需 git config user.name 与 user.email，不支持的子命令返回 GIT_UNSUPPORTED。" +
        (if (service.pipAvailable) "pip install 可安装纯 Python 包到应用用户目录（含原生扩展的包即使装上也无法导入），安装后可直接 import。" else "") +
        "python3、git" + (if (service.pipAvailable) "、pip" else "") + " 是本次 shell 中的函数，子 sh 脚本与 subprocess 中不可见" +
        "（可改用 python3 -m nexara_git" + (if (service.pipAvailable) " / python3 -m nexara_pip" else "") + "，或用 . 脚本 在当前 shell 执行）。" + WORKSPACE_NOTE +
        "需要 apt 包、编译工具链、SSH 远端 git 或原生 Python 扩展时改用 termux_exec 或 ssh_exec。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"command":{"type":"string","description":"在 sh -c 中执行的命令"},"timeout_seconds":{"type":"integer","description":"1–600 秒，默认 60"},"stdin":{"type":"string","description":"可选标准输入"},${BACKGROUND_EXEC_SCHEMA}},"required":["command"],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (args.backgroundRequested()) return runBackgroundExec(id, parametersSchema, args, context, jobs, com.promenar.nexara.domain.jobs.BackgroundJobRunner.SHELL, "command")
        return runBuiltin(id, validator, parametersSchema, args, ::invalid) { timeout, stdin ->
            val command = (args.getValue("command") as JsonPrimitive).content
            if (command.isBlank()) return@runBuiltin invalid("command 不能为空")
            if (command.length > MAX_COMMAND_CHARS) return@runBuiltin invalid("command 超过 $MAX_COMMAND_CHARS 字符，请把脚本写入工作区文件后执行")
            service.runShell(context.workspaceRootUuid, context.sessionId, command, stdin, timeout, context.liveOutput)
        }

    }

    private fun invalid(reason: String?) = CommandToolResult(
        errorCode = "EXEC_INVALID_ARGUMENTS",
        guidance = (reason?.take(200)?.let { "参数无效：$it。" } ?: "") +
            "command 必填（不超过 32000 字符）；timeout_seconds 为 1–600 的整数；stdin 可选。",
    )

    private companion object {
        const val MAX_COMMAND_CHARS = 32_000
    }
}

/** 内置 Python 3.14：标准库完整（已裁剪 test、tkinter 等），附带 dulwich；侧载渠道可用 pip 安装纯 Python 包。 */
class PythonExecSkill(private val service: BuiltinExecService, private val jobs: com.promenar.nexara.data.jobs.BackgroundJobManager? = null) : SkillDefinition {
    override val id = "python_exec"
    override val name = id
    override val risk = ToolRisk.PROCESS
    override val description = "用内置 Python 3.14 以 python -c 执行代码（仅 arm64 设备；含 ssl、sqlite3、json、csv、zipfile 等标准库与 dulwich（import dulwich.porcelain 操作 git 仓库）；" +
        (if (service.pipAvailable) "其它第三方包需先用 shell_exec 运行 pip install（仅纯 Python 包）），" else "无 numpy/pandas 等其它第三方包），") +
        "sys.path 包含工作区根，可导入工作区中的模块。" + WORKSPACE_NOTE +
        "需要原生扩展包时改用 termux_exec 或 ssh_exec。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"code":{"type":"string","description":"Python 源码"},"timeout_seconds":{"type":"integer","description":"1–600 秒，默认 60"},"stdin":{"type":"string","description":"可选标准输入"},${BACKGROUND_EXEC_SCHEMA}},"required":["code"],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (args.backgroundRequested()) return runBackgroundExec(id, parametersSchema, args, context, jobs, com.promenar.nexara.domain.jobs.BackgroundJobRunner.PYTHON, "code")
        return runBuiltin(id, validator, parametersSchema, args, ::invalid) { timeout, stdin ->
            val code = (args.getValue("code") as JsonPrimitive).content
            when {
                code.isBlank() -> invalid("code 不能为空")
                code.length > MAX_CODE_CHARS -> invalid("code 超过 $MAX_CODE_CHARS 字符，请把代码写入工作区文件后用 shell_exec 运行 python3 文件")
                // 单个 execve 参数上限 128 KiB。
                code.toByteArray(Charsets.UTF_8).size > MAX_CODE_BYTES -> invalid("code 的 UTF-8 编码超过 $MAX_CODE_BYTES 字节，请把代码写入工作区文件后用 shell_exec 运行 python3 文件")
                else -> service.runPython(context.workspaceRootUuid, context.sessionId, code, stdin, timeout, context.liveOutput)
            }
        }

    }

    private fun invalid(reason: String?) = CommandToolResult(
        errorCode = "EXEC_INVALID_ARGUMENTS",
        guidance = (reason?.take(200)?.let { "参数无效：$it。" } ?: "") +
            "code 必填（不超过 40000 字符）；timeout_seconds 为 1–600 的整数；stdin 可选。",
    )

    private companion object {
        const val MAX_CODE_CHARS = 40_000
        const val MAX_CODE_BYTES = 120_000
    }
}

private suspend fun runBuiltin(
    id: String,
    validator: ToolSchemaValidator,
    schema: String,
    args: JsonObject,
    invalid: (String?) -> CommandToolResult,
    body: suspend (timeoutSeconds: Int, stdin: String?) -> CommandToolResult,
): ToolResult {
    val result = try {
        when (val validation = validator.validate(schema, args)) {
            is ToolSchemaValidation.Invalid -> invalid("${validation.error.path}：${validation.error.message}")
            else -> {
                val timeout = (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeout !in 1..BuiltinCommandRunner.MAX_TIMEOUT_SECONDS) invalid("timeout_seconds 超出范围")
                else body(timeout, (args["stdin"] as? JsonPrimitive)?.content)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalArgumentException) {
        invalid(failure.message)
    } catch (failure: Exception) {
        CommandToolResult(errorCode = "EXEC_FAILED", guidance = "内置执行后端内部错误（${failure::class.java.simpleName}）；可改用 termux_exec 或 ssh_exec。")
    }
    return ToolResult(id = "${id}_${UUID.randomUUID()}", content = result.toJson().toString(), status = result.toolStatus)
}

private const val DEFAULT_TIMEOUT_SECONDS = 60

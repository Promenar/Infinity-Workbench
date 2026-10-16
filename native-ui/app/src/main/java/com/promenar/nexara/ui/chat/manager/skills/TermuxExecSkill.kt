package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.termux.TermuxCommandRequest
import com.promenar.nexara.data.termux.TermuxCommandResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.CancellationException
import java.util.UUID

class TermuxExecSkill(
    private val runner: suspend (TermuxCommandRequest) -> TermuxCommandResult,
    private val enabled: () -> Boolean,
) : SkillDefinition {
    override val id = "termux_exec"
    override val name = id
    override val risk = ToolRisk.PROCESS
    override val description = "在同机 Termux 的 bash 中通过 -c 后台执行命令；workdir 默认为 Termux 主目录，可传绝对路径、~/ 或 \$PREFIX/ 路径。timeout_seconds 默认60，最多600；支持stdin。超时只停止本地等待，命令可能仍在运行，重试前请检查原任务。输出可能被Termux从开头截断，结果含原始长度。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"command":{"type":"string","description":"在 bash -c 中执行的非空命令"},"workdir":{"type":"string","description":"可选绝对路径，默认 Termux 主目录"},"timeout_seconds":{"type":"integer","description":"1–600 秒，默认 60"},"stdin":{"type":"string","description":"可选标准输入"}},"required":["command"],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val result = try {
            when {
                !enabled() -> TermuxCommandResult(errorCode = "TERMUX_INTEGRATION_DISABLED", guidance = "请先在工具设置中启用 Termux 集成。")
                validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid -> invalidArguments()
                else -> runner(TermuxCommandRequest(
                    command = (args.getValue("command") as JsonPrimitive).content,
                    workdir = (args["workdir"] as? JsonPrimitive)?.content,
                    timeoutSeconds = (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 60,
                    stdin = (args["stdin"] as? JsonPrimitive)?.content,
                ))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IllegalArgumentException) { invalidArguments()
        } catch (_: Exception) {
            TermuxCommandResult(errorCode = "TERMUX_EXEC_FAILED", guidance = "Termux 调用失败；请打开工具设置中的 Termux 接入与诊断检查状态。")
        }
        return ToolResult(
            id = "termux_${UUID.randomUUID()}", content = result.toJson().toString(),
            status = if (result.errorCode == null && !result.timedOut && result.exitCode == 0) "success" else "error",
        )
    }
    private fun invalidArguments() = TermuxCommandResult(errorCode = "TERMUX_INVALID_ARGUMENTS", guidance = "请检查command、workdir、stdin和1–600秒的timeout_seconds；不接受host参数。")
}

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
    override val description = "在同机 Termux 的 bash 中通过 -c 后台执行命令；workdir 可省略，默认为 Termux 主目录；也可传绝对路径、~/ 或 \$PREFIX/ 路径。timeout_seconds 默认60，最多600；支持stdin。超时只停止本地等待，命令可能仍在运行，重试前请检查原任务。输出可能被Termux从开头截断，结果含原始长度。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"command":{"type":"string","description":"在 bash -c 中执行的非空命令"},"workdir":{"type":"string","description":"可省略，默认 Termux 主目录；或绝对路径、~/、${'$'}PREFIX/ 路径"},"timeout_seconds":{"type":"integer","description":"1–600 秒，默认 60"},"stdin":{"type":"string","description":"可选标准输入"}},"required":["command"],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val result = try {
            when {
                !enabled() -> TermuxCommandResult(errorCode = "TERMUX_INTEGRATION_DISABLED", guidance = "请先在工具设置中启用 Termux 集成。")
                else -> when (val validation = validator.validate(parametersSchema, args)) {
                    is ToolSchemaValidation.Invalid -> invalidArguments("${validation.error.path}：${validation.error.message}")
                    // Termux 结果由广播一次性回传，没有增量输出。
                    else -> {
                        context.liveOutput.unsupported()
                        runner(TermuxCommandRequest(
                            command = (args.getValue("command") as JsonPrimitive).content,
                            workdir = normalizeWorkdir((args["workdir"] as? JsonPrimitive)?.content),
                            timeoutSeconds = (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 60,
                            stdin = (args["stdin"] as? JsonPrimitive)?.content,
                        ))
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (invalid: IllegalArgumentException) { invalidArguments(invalid.message)
        } catch (_: Exception) {
            TermuxCommandResult(errorCode = "TERMUX_EXEC_FAILED", guidance = "Termux 调用失败；请打开工具设置中的 Termux 接入与诊断检查状态。")
        }
        return ToolResult(
            id = "termux_${UUID.randomUUID()}", content = result.toJson().toString(), status = result.toCommandResult().toolStatus,
        )
    }
    private fun invalidArguments(reason: String?) = TermuxCommandResult(
        errorCode = "TERMUX_INVALID_ARGUMENTS",
        guidance = (reason?.take(200)?.let { "参数无效：$it。" } ?: "") +
            "command 必填；workdir 可省略（默认 Termux 主目录）或传绝对路径、~/、\$PREFIX/ 路径；timeout_seconds 为 1–600 的整数；不接受 host 参数。",
    )

    // 模型常为可选参数填空串或主目录简写；按省略处理，避免整次调用被拒。
    private fun normalizeWorkdir(raw: String?): String? = raw?.trim()?.takeUnless { it.isEmpty() || it == "~" || it == "\$HOME" }
}

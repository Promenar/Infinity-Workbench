package com.promenar.nexara.startup

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.startup.diagnostics.AppDiagnostics
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun startupDiagnosticTools(manager: StartupIssueManager): List<SkillDefinition> = listOf(
    AppDiagnosticsSkill(manager),
    ResolveStartupIssueSkill(manager),
)

private class AppDiagnosticsSkill(private val manager: StartupIssueManager) : SkillDefinition {
    override val id = "app_diagnostics"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "读取当前启动问题和最近的脱敏诊断记录，不修改应用或工作区。" +
        " Read current startup issues and recent redacted diagnostics without changing application or workspace state."
    override val parametersSchema = """{"type":"object","properties":{},"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return result("INVALID_ARGUMENTS", failed = true)
        return try {
            val content = buildJsonObject {
                put("issues", manager.formatIssues())
                put("recentDiagnostics", AppDiagnostics.recent())
            }
            result(content.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result("DIAGNOSTICS_UNAVAILABLE", failed = true)
        }
    }
}

private class ResolveStartupIssueSkill(private val manager: StartupIssueManager) : SkillDefinition {
    override val id = "resolve_startup_issue"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val description = "处理指定启动问题：retry 重试恢复；abandon 放弃受支持的未完成操作并检查一致性，保留用户文件。" +
        " 每次调用都必须获得用户批准，所有运行模式及已记忆授权均不能豁免；拒绝后不得自行重复。" +
        " Resolve a startup issue with retry, or abandon a supported incomplete operation while preserving user files and checking consistency." +
        " Every call requires user approval in every execution mode; remembered grants cannot bypass approval. Do not repeat a rejected call."
    override val parametersSchema = """{"type":"object","properties":{"issueId":{"type":"string","description":"app_diagnostics 返回的问题 id / Issue id returned by app_diagnostics"},"action":{"type":"string","enum":["retry","abandon"],"description":"重试或放弃 / Retry or abandon"}},"required":["issueId","action"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        // 即使绕过正常工具分发直接调用技能，也不能用参数中的自我确认替代审批。
        if (!context.isApprovalBoundExecution) return result("APPROVAL_REQUIRED", failed = true)
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return result("INVALID_ARGUMENTS", failed = true)
        val issueId = (args["issueId"] as JsonPrimitive).content
        if (issueId.isBlank()) return result("INVALID_ARGUMENTS", failed = true)
        val action = when ((args["action"] as JsonPrimitive).content) {
            "retry" -> StartupIssueAction.RETRY
            "abandon" -> StartupIssueAction.ABANDON
            else -> return result("INVALID_ARGUMENTS", failed = true)
        }
        return try {
            when (val resolution = manager.resolve(issueId, action, confirmed = true)) {
                StartupIssueResolution.Resolved -> result("RESOLVED")
                StartupIssueResolution.ConfirmationRequired -> result("CONFIRMATION_REQUIRED", failed = true)
                StartupIssueResolution.NotFound -> result("ISSUE_NOT_FOUND", failed = true)
                StartupIssueResolution.UnsupportedAction -> result("ACTION_NOT_SUPPORTED", failed = true)
                is StartupIssueResolution.Failed -> result(buildJsonObject {
                    put("code", "RESOLUTION_FAILED")
                    put("summary", resolution.summary)
                }.toString(), failed = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result("RESOLUTION_FAILED", failed = true)
        }
    }
}

private val validator = ToolSchemaValidator()
private fun result(content: String, failed: Boolean = false) = ToolResult(
    id = UUID.randomUUID().toString(),
    content = content,
    status = if (failed) "error" else "success",
)

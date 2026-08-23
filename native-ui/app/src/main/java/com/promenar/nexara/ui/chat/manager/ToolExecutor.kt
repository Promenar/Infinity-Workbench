package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolExecutionOutcome
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.data.repository.ToolRegistrationResult
import com.promenar.nexara.ui.chat.ChatStore

import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.encodeToString
import com.promenar.nexara.utils.SensitiveDataRedactor
import com.promenar.nexara.utils.HttpsUrlValidator
import java.util.Base64

class ToolExecutor(
    private val store: ChatStore,
    private val messageManager: MessageManager,
    private val skillRegistry: SkillRegistry?,
    private val taskRepository: ITaskRepository? = null,
    private val ledger: ToolExecutionLedger? = null,
) {
    suspend fun executeTools(
        sessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        allowedToolCallIds: Set<String> = toolCalls.mapTo(mutableSetOf()) { it.id },
    ) {
        val session = store.getSession(sessionId) ?: return
        val targetMsgId = assistantMessageId

        val targetMsg = session.messages.find { it.id == targetMsgId } ?: return

        if (!session.options.toolsEnabled) {
            for (tc in toolCalls) {
                val syntheticContent = "[SYSTEM WARNING]: Tool usage is currently DISABLED by the user configuration.\nYou CANNOT use tools in this turn.\nPlease STOP trying to use tools and answer the user's request directly using your internal knowledge."

                val toolMessage = Message(
                    id = "tool_shield_${System.currentTimeMillis()}_${tc.id}",
                    role = MessageRole.TOOL,
                    toolCallId = tc.id,
                    parentMessageId = targetMsg.id,
                    content = syntheticContent,
                    name = tc.name,
                    thoughtSignature = targetMsg.thoughtSignature,
                    createdAt = System.currentTimeMillis()
                )
                messageManager.addMessage(sessionId, toolMessage)
            }
            return
        }

        val activeLedger = ledger ?: return

        val registeredToolCallIds = mutableSetOf<String>()
        toolCalls.distinctBy { it.id }.forEach { toolCall ->
            val key = ToolExecutionKey(sessionId, targetMsgId, toolCall.id)
            val persistedIdentity = activeLedger.invocationIdentity(key)
            val identity = ToolInvocationIdentityFactory.fromLegacyToolCall(
                toolCall,
                persistedIdentity?.requiresApproval ?: (toolCall.id !in allowedToolCallIds),
            )
            if (identity !is ToolInvocationIdentityResolution.Valid) return@forEach
            val registration = activeLedger.register(
                key = key,
                identity = identity.identity,
            )
            if (registration != ToolRegistrationResult.Conflict) registeredToolCallIds += toolCall.id
        }

        for (tc in toolCalls.distinctBy { it.id }) {
            if (tc.name.isEmpty()) continue
            if (tc.id !in allowedToolCallIds) continue
            if (tc.id !in registeredToolCallIds) continue
            val key = ToolExecutionKey(sessionId, targetMsgId, tc.id)
            if (!activeLedger.claim(key)) continue

            val stepId = "step_${System.currentTimeMillis()}_${tc.id}"
            val currentFocusId = session.activeTask?.currentFocusStepId

            appendStep(sessionId, targetMsgId, ExecutionStep(
                id = stepId,
                type = "tool_call",
                toolName = tc.name,
                toolArgs = tc.arguments,
                toolCallId = tc.id,
                timestamp = System.currentTimeMillis(),
                taskStepId = currentFocusId
            ))

            val result: ToolResult = executeSkill(tc, session)
            val failed = result.status != "success"
            val safeResultData = sanitizeResultData(result.data, failed)

            // 解析并合并 active search 的 citations 注入消息体，高保真呈现在 UI 上
            val latestSession = store.getSession(sessionId)
            val latestMsg = latestSession?.messages?.find { it.id == targetMsgId }
            if (latestMsg != null) {
                var skillCitations: List<com.promenar.nexara.data.model.Citation>? = null
                if (!safeResultData.isNullOrBlank() && !safeResultData.startsWith("data:image/")) {
                    try {
                        skillCitations = kotlinx.serialization.json.Json.decodeFromString<List<com.promenar.nexara.data.model.Citation>>(safeResultData)
                    } catch (_: Exception) {}
                }
                if (!skillCitations.isNullOrEmpty()) {
                    val mergedCitations = ((latestMsg.citations ?: emptyList()) + skillCitations).distinctBy { it.url }
                    messageManager.updateMessageContent(
                        sessionId, targetMsgId, latestMsg.content,
                        UpdateMessageOptions(citations = mergedCitations)
                    )
                }
            }

            val finalContent = if (failed) {
                "工具执行失败，请检查工具参数与配置后重试。"
            } else {
                result.content
            }

            appendStep(sessionId, targetMsgId, ExecutionStep(
                id = "res_$stepId",
                type = if (!failed) "tool_result" else "error",
                toolName = tc.name,
                toolCallId = tc.id,
                content = finalContent,
                data = safeResultData,
                timestamp = System.currentTimeMillis(),
                taskStepId = currentFocusId
            ))

            val toolMessage = activeLedger.finishWithResult(
                key = key,
                toolName = tc.name,
                content = finalContent,
                thoughtSignature = targetMsg.thoughtSignature,
                outcome =
                if (failed) {
                    ToolExecutionOutcome.Failed("工具执行失败")
                } else {
                    ToolExecutionOutcome.Succeeded
                },
                images = safeResultData?.takeIf { it.startsWith("data:image/") },
            )
            if (toolMessage != null) {
                messageManager.mirrorPersistedMessage(sessionId, toolMessage)
            }
        }
    }

    private suspend fun executeSkill(tc: ToolCall, session: com.promenar.nexara.data.model.Session): ToolResult {
        if (skillRegistry == null) {
            return ToolResult(id = tc.id, content = "Error: SkillRegistry not configured", status = "error")
        }

        val skill = skillRegistry.getSkill(tc.name)
        if (skill == null) {
            return ToolResult(id = tc.id, content = "Error: Skill ${tc.name} not found", status = "error")
        }

        return try {
            skill.execute(
                parseArgs(tc.arguments),
                object : SkillExecutionContext {
                    override val sessionId = session.id
                    override val agentId = session.agentId
                    override val workspacePath = session.workspacePath
                    override val workspaceRootUuid = session.workspaceRootUuid
                        ?: throw SecurityException("Session workspace root is missing")
                }
            )
        } catch (_: Exception) {
            ToolResult(id = tc.id, content = "工具执行失败", status = "error")
        }
    }

    private suspend fun appendStep(sessionId: String, targetMsgId: String, newStep: ExecutionStep) {
        val currentSession = store.getSession(sessionId) ?: return
        val currentMsg = currentSession.messages.find { it.id == targetMsgId } ?: return
        val currentSteps = currentMsg.executionSteps ?: emptyList()

        val index = currentSteps.indexOfFirst { s ->
            s.id == newStep.id || (newStep.toolCallId != null && s.toolCallId == newStep.toolCallId && s.type == newStep.type)
        }

        val updatedSteps = if (index > -1) {
            currentSteps.toMutableList().apply { set(index, newStep) }
        } else {
            currentSteps + newStep
        }

        messageManager.updateMessageContent(
            sessionId, targetMsgId, currentMsg.content,
            UpdateMessageOptions(executionSteps = updatedSteps)
        )
    }

    private fun parseArgs(argsJson: String): Map<String, Any> {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            if (element is kotlinx.serialization.json.JsonObject) {
                val result = mutableMapOf<String, Any>()
                for ((key, v) in element) {
                    when (v) {
                        is kotlinx.serialization.json.JsonPrimitive -> {
                            when {
                                v.isString -> result[key] = v.content
                                else -> {
                                    val content = v.content
                                    when {
                                        content == "true" -> result[key] = true
                                        content == "false" -> result[key] = false
                                        else -> {
                                            val long = content.toLongOrNull()
                                            if (long != null) result[key] = long
                                            else result[key] = content
                                        }
                                    }
                                }
                            }
                        }
                        else -> result[key] = v.toString()
                    }
                }
                result
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun sanitizeResultData(data: String?, failed: Boolean): String? {
        if (failed || data.isNullOrBlank()) return null
        val trimmed = data.trim()
        sanitizeImageData(trimmed)?.let { return it }
        if (trimmed.length > MAX_SAFE_RESULT_DATA_CHARS) return null
        return runCatching {
            val citations = kotlinx.serialization.json.Json.decodeFromString<
                List<com.promenar.nexara.data.model.Citation>
            >(trimmed)
            val sanitized = citations.take(MAX_SAFE_CITATIONS).mapNotNull { citation ->
                val redactedUrl = SensitiveDataRedactor.redactUrl(citation.url)
                if (!HttpsUrlValidator.isAllowed(redactedUrl)) return@mapNotNull null
                val uri = java.net.URI(redactedUrl)
                val safePath = sanitizeUrlPath(uri.path)
                val safeUrl = java.net.URI(
                    "https",
                    null,
                    uri.host,
                    uri.port,
                    safePath,
                    null,
                    null,
                ).toString()
                com.promenar.nexara.data.model.Citation(
                    title = sanitizeCitationText(citation.title),
                    url = safeUrl,
                    source = citation.source?.let(::sanitizeCitationText),
                )
            }
            sanitized.takeIf { it.isNotEmpty() }?.let {
                kotlinx.serialization.json.Json.encodeToString(it)
            }
        }.getOrNull()?.takeIf { it.length <= MAX_SAFE_RESULT_DATA_CHARS }
    }

    private fun sanitizeImageData(value: String): String? {
        val match = SAFE_IMAGE_DATA_REGEX.matchEntire(value) ?: return null
        val subtype = match.groupValues[1]
        val encoded = match.groupValues[2]
        if (encoded.length > MAX_IMAGE_BASE64_CHARS) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.size > MAX_IMAGE_BYTES || !hasExpectedImageMagic(subtype, bytes)) return null
        val binaryProbe = bytes.toString(Charsets.ISO_8859_1)
        if (SENSITIVE_BINARY_MARKER_REGEX.containsMatchIn(binaryProbe)) return null
        return value
    }

    private fun hasExpectedImageMagic(subtype: String, bytes: ByteArray): Boolean = when (subtype) {
        "png" -> bytes.size >= 8 && bytes.take(8) == listOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ).map { it.toByte() }
        "jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        else -> false
    }

    private fun sanitizeCitationText(value: String): String = SensitiveDataRedactor.redactMessage(value)
        .replace(BARE_SECRET_REGEX, "[REDACTED]")
        .replace(LOCAL_ABSOLUTE_PATH_REGEX, "[REDACTED]")
        .take(MAX_CITATION_FIELD_CHARS)

    private fun sanitizeUrlPath(path: String?): String {
        if (path.isNullOrBlank()) return ""
        if (LOCAL_ABSOLUTE_PATH_REGEX.containsMatchIn(path)) return "/REDACTED"
        return path.split('/').joinToString("/") { segment ->
            if (segment.length >= 32 || BARE_SECRET_REGEX.containsMatchIn(segment)) "REDACTED" else segment
        }
    }

    private companion object {
        const val MAX_SAFE_RESULT_DATA_CHARS = 256 * 1024
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_IMAGE_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4 + 1024
        const val MAX_SAFE_CITATIONS = 50
        const val MAX_CITATION_FIELD_CHARS = 512
        val SAFE_IMAGE_DATA_REGEX = Regex(
            "^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=\\r\\n]+)$",
        )
        val BARE_SECRET_REGEX = Regex(
            "(?i)(?:bearer\\s+[^\\s]+|sk-[A-Za-z0-9_-]{8,}|api[_-]?key\\s*[:=]\\s*[^\\s,;]+|authorization\\s*[:=]\\s*[^\\s,;]+)",
        )
        val LOCAL_ABSOLUTE_PATH_REGEX = Regex(
            "(?i)(?:/(?:Users|home|private|var|tmp)/[^\\s,;]+|[A-Z]:\\\\[^\\s,;]+)",
        )
        val SENSITIVE_BINARY_MARKER_REGEX = Regex(
            "(?i)(?:authorization|bearer\\s|sk-|api[_-]?key|/(?:Users|home|private|var|tmp)/|[A-Z]:\\\\)",
        )
    }
}

package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.generation.SessionToolResolver
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolExecutionOutcome
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.data.repository.ToolInvocationIdentity
import com.promenar.nexara.data.repository.ToolInvocationIdentityErrorCode
import com.promenar.nexara.data.repository.ToolDefinitionDigestResolution
import com.promenar.nexara.data.repository.ToolRegistrationResult
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.CancellationException
import com.promenar.nexara.data.session.SessionExecutionGate

import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.toProtocolTool
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import com.promenar.nexara.utils.SensitiveDataRedactor
import com.promenar.nexara.utils.HttpsUrlValidator
import java.util.Base64
import java.security.MessageDigest

class ToolExecutor(
    private val store: ChatStore,
    private val messageManager: MessageManager,
    private val skillRegistry: SkillRegistry?,
    private val toolResolver: SessionToolResolver,
    private val taskRepository: ITaskRepository? = null,
    private val ledger: ToolExecutionLedger? = null,
    private val executionGate: SessionExecutionGate? = null,
) {
    suspend fun executeTools(
        sessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        allowedToolCallIds: Set<String> = toolCalls.mapTo(mutableSetOf()) { it.id },
        preparedTools: List<ProtocolTool>? = null,
    ) {
        val gate = executionGate
        if (gate == null) {
            executeToolsAdmitted(sessionId, assistantMessageId, toolCalls, allowedToolCallIds, preparedTools)
        } else {
            gate.withNewSessionAdmission(sessionId) {
                executeToolsAdmitted(sessionId, assistantMessageId, toolCalls, allowedToolCallIds, preparedTools)
            }
        }
    }

    private suspend fun executeToolsAdmitted(
        sessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        allowedToolCallIds: Set<String> = toolCalls.mapTo(mutableSetOf()) { it.id },
        preparedTools: List<ProtocolTool>? = null,
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

        for (tc in toolCalls.distinctBy { it.id }) {
            val key = ToolExecutionKey(sessionId, targetMsgId, tc.id)
            val persistedIdentity = activeLedger.invocationIdentity(key)
            val preflight = preflight(
                call = tc,
                persistedIdentity = persistedIdentity,
                preparedTools = preparedTools,
                requiresApproval = tc.id !in allowedToolCallIds,
            )
            if (preflight is ToolPreflight.Invalid) {
                finishInvalidCall(activeLedger, key, targetMsg, preflight)
                continue
            }
            preflight as ToolPreflight.Valid
            val registration = activeLedger.register(key, preflight.identity)
            if (registration == ToolRegistrationResult.Conflict) continue
            if (tc.id !in allowedToolCallIds) continue
            val expectedIdentity = activeLedger.invocationIdentity(key)
                ?.takeIf { it == preflight.identity }
                ?: continue
            if (!activeLedger.claim(key, expectedIdentity)) continue
            val currentSession = store.getSession(sessionId)
            val currentResolvedTool = currentSession?.let(toolResolver::resolve)
                ?.singleOrNull { tool ->
                    tool.runtimeToolId == expectedIdentity.runtimeToolId &&
                        tool.function.name == expectedIdentity.toolName
                }
            val currentResolvedDigest = currentResolvedTool
                ?.let(ToolInvocationIdentityFactory::definitionDigest)
            val currentSkill = skillRegistry?.getSkillByRuntimeToolId(expectedIdentity.runtimeToolId)
            val currentDefinition = currentSkill?.toProtocolTool()
                ?.let(ToolInvocationIdentityFactory::definitionDigest)
            if (currentSession == null || currentResolvedTool == null ||
                currentResolvedDigest !is ToolDefinitionDigestResolution.Valid ||
                currentResolvedDigest.digest != expectedIdentity.definitionDigest ||
                currentSkill == null || currentSkill.name != expectedIdentity.toolName ||
                currentDefinition !is ToolDefinitionDigestResolution.Valid ||
                currentDefinition.digest != expectedIdentity.definitionDigest
            ) {
                finishClaimedFailure(
                    activeLedger = activeLedger,
                    key = key,
                    toolName = tc.name,
                    thoughtSignature = targetMsg.thoughtSignature,
                )
                continue
            }

            val stepId = "step_${System.currentTimeMillis()}_${tc.id}"
            val currentFocusId = currentSession.activeTask?.currentFocusStepId

            appendStep(sessionId, targetMsgId, ExecutionStep(
                id = stepId,
                type = "tool_call",
                toolName = tc.name,
                toolArgs = tc.arguments,
                toolCallId = tc.id,
                timestamp = System.currentTimeMillis(),
                taskStepId = currentFocusId
            ))

            val result: ToolResult = executeSkill(currentSkill, preflight.arguments, tc, currentSession)
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

    private suspend fun executeSkill(
        skill: SkillDefinition,
        arguments: JsonObject,
        tc: ToolCall,
        session: com.promenar.nexara.data.model.Session,
    ): ToolResult {
        return try {
            skill.execute(
                arguments,
                object : SkillExecutionContext {
                    override val sessionId = session.id
                    override val agentId = session.agentId
                    override val workspacePath = session.workspacePath
                    override val workspaceRootUuid = session.workspaceRootUuid
                        ?: throw SecurityException("Session workspace root is missing")
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ToolResult(id = tc.id, content = "工具执行失败", status = "error")
        }
    }

    private suspend fun finishInvalidCall(
        activeLedger: ToolExecutionLedger,
        key: ToolExecutionKey,
        targetMessage: Message,
        preflight: ToolPreflight.Invalid,
    ) {
        preflight.persistedIdentity?.let { persistedIdentity ->
            activeLedger.failAwaitingIdentityConflict(
                key,
                persistedIdentity,
                targetMessage.thoughtSignature,
            )?.let { messageManager.mirrorPersistedMessage(key.sessionId, it) }
            return
        }
        val identity = preflight.identity
        val registration = activeLedger.register(key, identity)
        if (registration == ToolRegistrationResult.Conflict) return
        if (!activeLedger.claim(key, identity)) return
        val terminal = activeLedger.finishWithResult(
            key = key,
            toolName = "invalid_tool",
            content = INVALID_TOOL_CALL_CONTENT,
            thoughtSignature = targetMessage.thoughtSignature,
            outcome = ToolExecutionOutcome.Failed("工具调用预检失败"),
        )
        terminal?.let { messageManager.mirrorPersistedMessage(key.sessionId, it) }
    }

    private suspend fun finishClaimedFailure(
        activeLedger: ToolExecutionLedger,
        key: ToolExecutionKey,
        toolName: String,
        thoughtSignature: String?,
    ) {
        val terminal = activeLedger.finishWithResult(
            key = key,
            toolName = toolName,
            content = "工具执行失败：工具定义已变化，已安全终止。",
            thoughtSignature = thoughtSignature,
            outcome = ToolExecutionOutcome.Failed("工具定义身份不一致"),
        )
        terminal?.let { messageManager.mirrorPersistedMessage(key.sessionId, it) }
    }

    private fun preflight(
        call: ToolCall,
        persistedIdentity: ToolInvocationIdentity?,
        preparedTools: List<ProtocolTool>?,
        requiresApproval: Boolean,
    ): ToolPreflight {
        if (persistedIdentity != null) {
            val arguments = ToolArgumentsValidator().validate(call.arguments)
                as? ToolArgumentsValidation.Valid
            if (arguments == null || call.name != persistedIdentity.toolName ||
                arguments.sha256 != persistedIdentity.argumentsDigest ||
                (requiresApproval && !persistedIdentity.requiresApproval)
            ) {
                return invalidPreflight(
                    call,
                    ToolInvocationIdentityErrorCode.MALFORMED_ARGUMENTS,
                    persistedIdentity,
                )
            }
            return ToolPreflight.Valid(persistedIdentity, arguments.arguments)
        }

        val matchingDefinitions = preparedTools.orEmpty().filter { it.function.name == call.name }
        if (matchingDefinitions.size != 1) {
            return invalidPreflight(call, ToolInvocationIdentityErrorCode.INVALID_DEFINITION)
        }
        return when (val resolved = ToolInvocationIdentityFactory.fromPreparedToolCall(
            call = call,
            tool = matchingDefinitions.single(),
            requiresApproval = requiresApproval,
        )) {
            is ToolInvocationIdentityResolution.Valid -> ToolPreflight.Valid(
                resolved.identity,
                resolved.arguments,
            )
            is ToolInvocationIdentityResolution.Invalid -> invalidPreflight(call, resolved.code)
        }
    }

    private fun invalidPreflight(
        call: ToolCall,
        code: ToolInvocationIdentityErrorCode,
        persistedIdentity: ToolInvocationIdentity? = null,
    ): ToolPreflight.Invalid = ToolPreflight.Invalid(
        identity = ToolInvocationIdentity(
            runtimeToolId = "invalid_tool",
            toolName = "invalid_tool",
            argumentsDigest = sha256(
                "nexara:invalid-tool-arguments:v1\u0000${call.name}\u0000${call.arguments}",
            ),
            definitionDigest = sha256("nexara:invalid-tool-definition:v1\u0000${code.name}"),
            requiresApproval = false,
        ),
        persistedIdentity = persistedIdentity,
    )

    private sealed interface ToolPreflight {
        data class Valid(
            val identity: ToolInvocationIdentity,
            val arguments: JsonObject,
        ) : ToolPreflight

        data class Invalid(
            val identity: ToolInvocationIdentity,
            val persistedIdentity: ToolInvocationIdentity?,
        ) : ToolPreflight
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
        const val INVALID_TOOL_CALL_CONTENT = "工具调用校验失败，已安全终止。"
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

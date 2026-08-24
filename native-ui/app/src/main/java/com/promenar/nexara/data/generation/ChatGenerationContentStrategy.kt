package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.FullContextDocumentFormatter
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.remote.parser.DsmlStreamParser
import com.promenar.nexara.data.remote.protocol.ImageInput
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.domain.model.ExecutionModeCodec
import com.promenar.nexara.domain.tool.ToolExecutionPolicy
import com.promenar.nexara.domain.tool.ToolRisk
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class GenerationContentNormalization(
    val content: String,
    val syntheticSteps: List<ExecutionStep> = emptyList(),
)

interface ChatGenerationContentStrategy {
    fun buildProtocolMessages(
        session: Session,
        systemPrompt: String,
        pinnedUserMessageId: String? = null,
        excludedMessageIds: Set<String> = emptySet(),
    ): List<ProtocolMessage>
    fun buildTools(session: Session): List<ProtocolTool>
    fun safeActiveWindow(messages: List<Message>, windowSize: Int): List<Message>
    fun normalize(content: String, toolCalls: List<ToolCall>): GenerationContentNormalization
    fun extractFallbackToolCalls(content: String): List<ToolCall>
    fun stripToolCallMarkup(content: String): String
    fun mergeToolCalls(existing: List<ToolCall>, incoming: List<ToolCall>): List<ToolCall>
    fun pendingApprovalIds(toolCalls: List<ToolCall>, executionMode: String): List<String>
    fun riskForTool(toolName: String): ToolRisk
}

/** 不依赖 ViewModel 的纯内容与工具协议策略，可由应用级生成协调器长期持有。 */
class DefaultChatGenerationContentStrategy(
    private val settings: SharedPreferences,
    private val skillRegistry: SkillRegistry?,
    private val toolResolver: SessionToolResolver = DefaultSessionToolResolver(settings, skillRegistry),
) : ChatGenerationContentStrategy {
    @Volatile
    private var knownToolNames: Set<String>? = null
    @Volatile
    private var knownToolRisks: Map<String, ToolRisk> = emptyMap()

    override fun buildProtocolMessages(
        session: Session,
        systemPrompt: String,
        pinnedUserMessageId: String?,
        excludedMessageIds: Set<String>,
    ): List<ProtocolMessage> = buildList {
        if (systemPrompt.isNotBlank()) add(ProtocolMessage(role = "system", content = systemPrompt))
        val windowSize = session.inferenceParams?.activeContextWindow ?: 10
        val eligibleMessages = session.messages.filterNot { it.id in excludedMessageIds }
        val activeMessages = safeActiveWindow(eligibleMessages, windowSize)
        val pinnedMessage = pinnedUserMessageId?.let { id ->
            eligibleMessages.firstOrNull { it.id == id && it.role == MessageRole.USER }
        }
        val protocolMessages = if (pinnedMessage != null && activeMessages.none { it.id == pinnedMessage.id }) {
            listOf(pinnedMessage) + activeMessages
        } else {
            activeMessages
        }
        protocolMessages.forEach { message ->
            add(
                when (message.role) {
                    MessageRole.USER -> ProtocolMessage(
                        role = "user",
                        content = FullContextDocumentFormatter.appendToUserContent(
                            message.content,
                            message.userDocuments.orEmpty(),
                        ),
                        imageUrls = message.userImages?.map { dataUrl ->
                            val base64Index = dataUrl.indexOf("base64,")
                            val mimeEnd = dataUrl.indexOf(';')
                            ImageInput(
                                url = dataUrl,
                                base64 = if (base64Index >= 0) dataUrl.substring(base64Index + 7) else "",
                                mimeType = if (mimeEnd > 5) dataUrl.substring(5, mimeEnd) else "image/jpeg",
                            )
                        },
                    )
                    MessageRole.ASSISTANT -> ProtocolMessage(
                        role = "assistant",
                        content = message.content,
                        reasoning = message.reasoning,
                        toolCalls = message.toolCalls?.map {
                            ProtocolToolCall(id = it.id, name = it.name, arguments = it.arguments)
                        },
                    )
                    MessageRole.SYSTEM -> ProtocolMessage(role = "system", content = message.content)
                    MessageRole.TOOL -> ProtocolMessage(
                        role = "tool",
                        content = message.content,
                        toolCallId = message.toolCallId,
                        name = message.name,
                    )
                },
            )
        }
    }

    override fun buildTools(session: Session): List<ProtocolTool> {
        knownToolRisks = emptyMap()
        knownToolNames = emptySet()
        val resolved = toolResolver.resolve(session)
        knownToolNames = resolved.mapTo(mutableSetOf()) { it.function.name }
        return resolved.also { tools ->
            knownToolRisks = tools.associate { it.function.name to it.risk }
        }
    }

    override fun safeActiveWindow(messages: List<Message>, windowSize: Int): List<Message> {
        if (messages.size <= windowSize) return messages
        var startIndex = messages.size - windowSize
        while (startIndex > 0 && messages[startIndex].role == MessageRole.TOOL) startIndex--
        return messages.drop(startIndex)
    }

    override fun normalize(content: String, toolCalls: List<ToolCall>): GenerationContentNormalization {
        if (toolCalls.isNotEmpty()) return GenerationContentNormalization(content)
        val match = TOOL_RESULT_SEPARATOR_PATTERN.find(content) ?: return GenerationContentNormalization(content)
        val separatorIndex = content.indexOf(match.value)
        return GenerationContentNormalization(
            content = content.substring(0, separatorIndex).trimEnd(),
            syntheticSteps = listOf(
                ExecutionStep(
                    id = "stream-sniff-${System.currentTimeMillis()}",
                    type = "tool_result",
                    content = content.substring(separatorIndex).take(500),
                ),
            ),
        )
    }

    override fun extractFallbackToolCalls(content: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        val dsmlParser = DsmlStreamParser()
        val outputText = StringBuilder()
        val dsmlCalls = dsmlParser.process(content, outputText)
        dsmlParser.flush(outputText)
        dsmlCalls.mapTo(results) { call ->
            val arguments = buildJsonObject {
                call.args.forEach { (key, value) ->
                    put(
                        key,
                        when (value) {
                            is String -> JsonPrimitive(value)
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            is JsonElement -> value
                            else -> JsonPrimitive(value.toString())
                        },
                    )
                }
            }.toString()
            ToolCall(stableId("dsml", call.toolName, arguments, results.size), call.toolName, arguments)
        }
        results.filter(::isKnownTool).takeIf { it.isNotEmpty() }?.let { return it }
        results.clear()

        XML_TOOL_CALL_PATTERN.findAll(content).forEach { match ->
            val attributes = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) return@forEach
            val attributeName = Regex("""name\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(attributes)?.groupValues?.get(1)
            if (body.startsWith("{") || body.startsWith("[")) {
                val elements = buildList {
                    runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let(::add)
                    scanBalancedJsonSegments(body).forEach { segment ->
                        runCatching { Json.parseToJsonElement(segment) }.getOrNull()?.let(::add)
                    }
                }
                elements.firstNotNullOfOrNull { parseToolCall(it, results.size) }?.let { call ->
                    if (results.none { it.name == call.name }) results += call
                }
            } else {
                val name = attributeName ?: body.lineSequence().first().trim()
                if (name.isNotEmpty() && name.none { it == '{' || it == '<' } && isKnownTool(name)) {
                    results += ToolCall(stableId("xml", name, "{}", results.size), name, "{}")
                }
            }
        }
        return results
    }

    override fun stripToolCallMarkup(content: String): String = content
        .replace(XML_TOOL_PATTERN, "")
        .replace(TOOL_RESULT_SEPARATOR_PATTERN, "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    override fun mergeToolCalls(existing: List<ToolCall>, incoming: List<ToolCall>): List<ToolCall> {
        val merged = linkedMapOf<String, ToolCall>()
        existing.forEach { merged.putIfAbsent(it.id, it) }
        incoming.forEach { call ->
            val previous = merged[call.id]
            merged[call.id] = previous?.copy(
                name = call.name.ifBlank { previous.name },
                arguments = call.arguments.ifBlank { previous.arguments },
            ) ?: call
        }
        return merged.values.toList()
    }

    override fun pendingApprovalIds(toolCalls: List<ToolCall>, executionMode: String): List<String> {
        val mode = ExecutionModeCodec.parseOrSemi(executionMode)
        return toolCalls.filter { call ->
            ToolExecutionPolicy.requiresApproval(mode, riskForTool(call.name))
        }.map { it.id }
    }

    override fun riskForTool(toolName: String): ToolRisk = knownToolRisks[toolName] ?: ToolRisk.UNKNOWN

    private fun parseToolCall(element: JsonElement, index: Int): ToolCall? {
        if (element !is JsonObject) return null
        val name = element["name"]?.jsonPrimitive?.content
            ?: element["tool"]?.jsonPrimitive?.content
            ?: element["tool_name"]?.jsonPrimitive?.content
            ?: element["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: return null
        if (!isKnownTool(name)) return null
        val rawArguments: Any? = element["arguments"] ?: element["parameters"] ?: element["input"]
            ?: element["args"] ?: element["function"]?.jsonObject?.get("arguments")
        val arguments = when (rawArguments) {
            is JsonObject -> rawArguments.toString()
            is JsonPrimitive -> rawArguments.content
            is String -> rawArguments
            else -> "{}"
        }
        return ToolCall(stableId("fallback", name, arguments, index), name, arguments)
    }

    private fun isKnownTool(call: ToolCall): Boolean = isKnownTool(call.name)

    private fun isKnownTool(name: String): Boolean {
        val known = knownToolNames ?: skillRegistry?.getAllTools()?.map { it.function.name }?.toSet().orEmpty()
            .also { knownToolNames = it }
        return name in known
    }

    private fun stableId(prefix: String, name: String, arguments: String, index: Int): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$prefix\u0000$name\u0000$arguments\u0000$index".toByteArray())
        return "${prefix}_" + bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun scanBalancedJsonSegments(text: String): List<String> {
        val segments = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('{', index)
            if (start < 0) break
            val end = findMatchingCloseBrace(text, start)
            if (end >= 0) {
                text.substring(start, end + 1).takeIf { candidate ->
                    listOf("\"name\"", "\"tool\"", "\"tool_name\"", "\"function\"")
                        .any(candidate::contains)
                }?.let(segments::add)
                index = end + 1
            } else {
                index = start + 1
            }
        }
        return segments
    }

    private fun findMatchingCloseBrace(text: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (escaped) {
                escaped = false
            } else when (character) {
                '\\' -> escaped = true
                '"' -> quoted = !quoted
                '{' -> if (!quoted) depth++
                '}' -> if (!quoted && --depth == 0) return index
            }
        }
        return -1
    }

    private companion object {
        val XML_TOOL_CALL_PATTERN = Regex(
            """<\s*(?:FunctionCall|tool_call|function_call|func_call|tool-call|function-call)\s*([^>]*)>([\s\S]*?)</\s*(?:FunctionCall|tool_call|function_call|func_call|tool-call|function-call)\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val XML_TOOL_PATTERN = Regex(
            """<\s*(?:FunctionCall|tool_call|function_call|function_name|func_call|tool-call|function-call)[^>]*/?>[\s\S]*?</\s*(?:FunctionCall|tool_call|function_call|func_call|tool-call|function-call)\s*>""",
            RegexOption.IGNORE_CASE,
        )
        val TOOL_RESULT_SEPARATOR_PATTERN = Regex(
            """(?:^|\n)---(?!-{2,})\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]\s*[\s\S]*?(?=\n\n|\n?$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
    }
}

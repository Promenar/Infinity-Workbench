package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.remote.ThinkingDetector
import com.promenar.nexara.data.remote.parser.ErrorNormalizer
import com.promenar.nexara.data.remote.parser.HttpStatusException
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*

class OpenAIProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val id: ProtocolId = ProtocolId.OPENAI

    private val httpClient: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeChannel: ByteReadChannel? = null

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null
        val thinkingDetector = ThinkingDetector()
        val toolCallAccumulator = mutableMapOf<Int, AccumulatedToolCall>()

        val response: HttpResponse
        try {
            response = httpClient.post(buildUrl()) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("Accept", "text/event-stream")
                setBody(buildRequestBody(request, stream = true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(normalizeError(e))
            return@channelFlow
        }

        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "" }
            val normalized = ErrorNormalizer.normalize(
                HttpStatusException(response.status.value, errorBody)
            )
            send(StreamChunk.Error(normalized.message, normalized.retryable, normalized.category.name))
            return@channelFlow
        }

        response.contentType()?.let { ct ->
            if (ct.match(ContentType.Text.Html)) {
                send(StreamChunk.Error(
                    "Received HTML response instead of JSON stream. " +
                        "Check your Base URL settings."
                ))
                return@channelFlow
            }
        }

        val channel = response.body<ByteReadChannel>()
        activeChannel = channel

        try {
            val sb = StringBuilder()
            while (!channel.isClosedForRead) {
                sb.clear()
                if (!channel.readUTF8LineTo(sb, 1_048_576)) break

                val line = sb.toString()
                if (line.isEmpty()) continue

                if (line.trimStart().startsWith('<')) {
                    send(StreamChunk.Error(
                        "Received HTML response instead of JSON stream. " +
                            "Check your Base URL settings."
                    ))
                    return@channelFlow
                }

                val data = extractSseData(line) ?: continue
                if (data == "[DONE]") break

                try {
                    val chunkJson = json.parseToJsonElement(data).jsonObject
                    processStreamChunk(chunkJson, thinkingDetector, toolCallAccumulator)
                } catch (_: Exception) {
                }
            }

            flushRemaining(thinkingDetector, toolCallAccumulator)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(normalizeError(e))
        } finally {
            activeChannel = null
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val response: HttpResponse
        try {
            response = httpClient.post(buildUrl()) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(buildRequestBody(request, stream = false))
            }
        } catch (e: Exception) {
            throw Exception(ErrorNormalizer.normalize(e).message)
        }

        val responseText = try { response.bodyAsText() } catch (_: Exception) { "" }

        if (!response.status.isSuccess()) {
            val normalized = ErrorNormalizer.normalize(
                HttpStatusException(response.status.value, responseText)
            )
            throw Exception(normalized.message)
        }

        return parseSyncResponse(responseText)
    }

    override fun cancel() {
        activeChannel?.cancel()
    }

    private fun buildUrl(): String {
        val cleanBase = baseUrl.trimEnd('/')
        return "$cleanBase/chat/completions"
    }

    private fun buildRequestBody(request: PromptRequest, stream: Boolean): String {
        val body = buildJsonObject {
            put("model", request.model.ifEmpty { this@OpenAIProtocol.model })
            put("stream", stream)

            put("messages", JsonArray(request.messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content.ifEmpty { "" })

                    msg.name?.let { put("name", it) }

                    if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", JsonArray(msg.toolCalls.map { tc ->
                            buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            }
                        }))
                    }

                    if (msg.role == "tool") {
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.name?.let { put("name", it) }
                    }
                }
            }))

            request.temperature?.let { put("temperature", it) }
            request.topP?.let { if (it < 1.0) put("top_p", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            request.frequencyPenalty?.let { if (it != 0.0) put("frequency_penalty", it) }
            request.presencePenalty?.let { if (it != 0.0) put("presence_penalty", it) }

            if (stream) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            if (request.tools != null && request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map { tool ->
                    buildJsonObject {
                        put("type", tool.type)
                        put("function", buildJsonObject {
                            put("name", tool.function.name)
                            put("description", tool.function.description)
                            put("parameters", json.parseToJsonElement(tool.function.parameters))
                        })
                    }
                }))
                put("tool_choice", "auto")
            }
        }

        return body.toString()
    }

    private suspend fun SendChannel<StreamChunk>.processStreamChunk(
        chunk: JsonObject,
        thinkingDetector: ThinkingDetector,
        toolCallAccumulator: MutableMap<Int, AccumulatedToolCall>
    ) {
        val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
        val delta = choice["delta"]?.jsonObject ?: return

        var content = delta.stringField("content")
        var reasoning = delta.stringField("reasoning_content")

        val thinkingResult = thinkingDetector.process(content)
        content = thinkingResult.content
        reasoning += thinkingResult.reasoning

        content = cleanSpecialTokens(content)
        reasoning = cleanSpecialTokens(reasoning)

        val deltaToolCalls = delta["tool_calls"]?.jsonArray
        if (deltaToolCalls != null) {
            for (tcElement in deltaToolCalls) {
                val tc = tcElement.jsonObject
                val index = tc["index"]?.jsonPrimitive?.intOrNull ?: continue

                val existing = toolCallAccumulator[index]
                if (existing == null) {
                    toolCallAccumulator[index] = AccumulatedToolCall(
                        id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                        arguments = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                    )
                } else {
                    val newId = tc["id"]?.jsonPrimitive?.contentOrNull
                    val newName = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val newArgs = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull

                    toolCallAccumulator[index] = existing.copy(
                        id = if (newId.isNullOrEmpty()) existing.id else newId,
                        name = if (newName.isNullOrEmpty()) existing.name else newName,
                        arguments = existing.arguments + (newArgs ?: "")
                    )
                }
            }
        }

        if (content.isNotEmpty() || reasoning.isNotEmpty()) {
            send(StreamChunk.TextDelta(content, reasoning.ifEmpty { null }))
        }

        val usageRaw = chunk["usage"]?.jsonObject
        if (usageRaw != null) {
            val usage = ProtocolUsage(
                input = usageRaw["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                output = usageRaw["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                total = usageRaw["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            )
            send(StreamChunk.Usage(usage))
        }
    }

    private suspend fun SendChannel<StreamChunk>.flushRemaining(
        thinkingDetector: ThinkingDetector,
        toolCallAccumulator: MutableMap<Int, AccumulatedToolCall>
    ) {
        val remaining = thinkingDetector.flush()
        if (remaining.content.isNotEmpty() || remaining.reasoning.isNotEmpty()) {
            send(StreamChunk.TextDelta(remaining.content, remaining.reasoning.ifEmpty { null }))
        }

        for ((index, tc) in toolCallAccumulator) {
            if (tc.id.isNotEmpty() && tc.name.isNotEmpty()) {
                send(StreamChunk.ToolCallDelta(tc.id, tc.name, tc.arguments, index))
            }
        }

        send(StreamChunk.Done)
    }

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val parsed = json.parseToJsonElement(responseText).jsonObject
        val message = parsed["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?: return PromptResponse(content = "")

        var content = message.stringField("content")
        var reasoning = message.stringField("reasoning_content")

        if (content.contains("<think")) {
            val match = Regex("""<think[^>]*>([\s\S]*?)</think\s*>""", RegexOption.IGNORE_CASE)
                .find(content)
            if (match != null) {
                reasoning = match.groupValues[1] + reasoning
                content = content.replace(
                    Regex("""<think[^>]*>[\s\S]*?</think\s*>""", RegexOption.IGNORE_CASE),
                    ""
                ).trim()
            }
        }

        content = cleanSpecialTokens(content)
        reasoning = cleanSpecialTokens(reasoning)

        val toolCalls = message["tool_calls"]?.jsonArray?.mapNotNull { tcElement ->
            val tc = tcElement.jsonObject
            val function = tc["function"]?.jsonObject ?: return@mapNotNull null
            ProtocolToolCall(
                id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = function["name"]?.jsonPrimitive?.contentOrNull ?: "",
                arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            )
        }

        val usageRaw = parsed["usage"]?.jsonObject
        val usage = if (usageRaw != null) {
            ProtocolUsage(
                input = usageRaw["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                output = usageRaw["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                total = usageRaw["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            )
        } else null

        return PromptResponse(
            content = content,
            reasoning = reasoning.ifEmpty { null },
            toolCalls = toolCalls,
            usage = usage
        )
    }

    private fun extractSseData(line: String): String? {
        if (!line.startsWith("data: ")) return null
        return line.substring(6).trim()
    }

    private fun cleanSpecialTokens(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace("<|end_of_thinking|>", "")
            .replace("< | end__of__thinking | >", "")
            .replace("<|endofthinking|>", "")
            .trim()
    }

    private fun normalizeError(e: Exception): StreamChunk.Error {
        val normalized = ErrorNormalizer.normalize(e)
        return StreamChunk.Error(normalized.message, normalized.retryable, normalized.category.name)
    }

    private fun JsonObject.stringField(key: String): String {
        val el = this[key] ?: return ""
        return if (el is JsonNull) "" else el.jsonPrimitive.contentOrNull ?: ""
    }

    private data class AccumulatedToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )
}

package com.promenar.nexara.data.remote.protocol

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

class AnthropicProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val anthropicVersion: String = "2023-06-01",
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val id: ProtocolId = ProtocolId.ANTHROPIC

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

        val response: HttpResponse
        try {
            response = httpClient.post(buildUrl()) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", anthropicVersion)
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
            var currentEvent = ""
            var currentData = ""

            while (!channel.isClosedForRead) {
                sb.clear()
                if (!channel.readUTF8LineTo(sb, 1_048_576)) break

                val line = sb.toString()
                if (line.isEmpty()) {
                    if (currentData.isNotEmpty()) {
                        processSseEvent(currentEvent, currentData)
                        currentEvent = ""
                        currentData = ""
                    }
                    continue
                }

                if (line.trimStart().startsWith('<')) {
                    send(StreamChunk.Error(
                        "Received HTML response instead of JSON stream. " +
                            "Check your Base URL settings."
                    ))
                    return@channelFlow
                }

                when {
                    line.startsWith("event: ") -> {
                        currentEvent = line.substring(7).trim()
                    }
                    line.startsWith("data: ") -> {
                        currentData = line.substring(6).trim()
                        if (currentEvent.isEmpty()) {
                            currentEvent = "message"
                        }
                    }
                    line.startsWith("data:") -> {
                        currentData = line.substring(5).trim()
                        if (currentEvent.isEmpty()) {
                            currentEvent = "message"
                        }
                    }
                }
            }

            if (currentData.isNotEmpty()) {
                processSseEvent(currentEvent, currentData)
            }

            send(StreamChunk.Done)
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
                header("x-api-key", apiKey)
                header("anthropic-version", anthropicVersion)
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
        return "$cleanBase/v1/messages"
    }

    private fun buildRequestBody(request: PromptRequest, stream: Boolean): String {
        val body = buildJsonObject {
            put("model", request.model.ifEmpty { this@AnthropicProtocol.model })
            put("stream", stream)
            put("max_tokens", request.maxTokens ?: 4096)

            val systemMessage = request.messages.find { it.role == "system" }
            if (systemMessage != null) {
                put("system", systemMessage.content)
            }

            val nonSystemMessages = request.messages.filter { it.role != "system" }
            put("messages", JsonArray(nonSystemMessages.map { msg ->
                buildJsonObject {
                    put("role", msg.role)
                    if (msg.content.isNotEmpty()) {
                        put("content", msg.content)
                    } else {
                        put("content", "")
                    }

                    if (msg.role == "assistant" && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                        put("content", JsonArray(msg.toolCalls.map { tc ->
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", try {
                                    json.parseToJsonElement(tc.arguments)
                                } catch (_: Exception) {
                                    buildJsonObject {}
                                })
                            }
                        }))
                    }

                    if (msg.role == "tool") {
                        msg.toolCallId?.let { put("tool_use_id", it) }
                        put("content", msg.content.ifEmpty { "" })
                    }
                }
            }))

            request.temperature?.let { put("temperature", it) }
            request.topP?.let { if (it < 1.0) put("top_p", it) }

            if (request.reasoning == true) {
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", 10000)
                })
            }

            if (request.tools != null && request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map { tool ->
                    buildJsonObject {
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        put("input_schema", try {
                            json.parseToJsonElement(tool.function.parameters)
                        } catch (_: Exception) {
                            buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            }
                        })
                    }
                }))
            }
        }

        return body.toString()
    }

    private var inputTokenCount = 0

    private suspend fun SendChannel<StreamChunk>.processSseEvent(event: String, data: String) {
        if (data.isEmpty()) return

        val eventData = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            return
        }

        val type = eventData["type"]?.jsonPrimitive?.contentOrNull ?: event

        when (type) {
            "content_block_delta" -> processContentBlockDelta(eventData)
            "message_delta" -> processMessageDelta(eventData)
            "message_start" -> processMessageStart(eventData)
            "message_stop" -> { }
            "content_block_start", "content_block_stop", "ping" -> { }
            "error" -> {
                val errorMsg = eventData["error"]?.jsonObject?.let { errObj ->
                    errObj["message"]?.jsonPrimitive?.contentOrNull ?: data
                } ?: data
                send(StreamChunk.Error(errorMsg))
            }
        }
    }

    private suspend fun SendChannel<StreamChunk>.processContentBlockDelta(eventData: JsonObject) {
        val delta = eventData["delta"]?.jsonObject ?: return
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (deltaType) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: return
                if (text.isNotEmpty()) {
                    send(StreamChunk.TextDelta(content = text))
                }
            }
            "thinking_delta" -> {
                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull ?: return
                if (thinking.isNotEmpty()) {
                    send(StreamChunk.Thinking(content = thinking))
                }
            }
        }
    }

    private suspend fun SendChannel<StreamChunk>.processMessageDelta(eventData: JsonObject) {
        val usage = eventData["usage"]?.jsonObject
        if (usage != null) {
            val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            if (outputTokens > 0 && inputTokenCount > 0) {
                send(StreamChunk.Usage(
                    ProtocolUsage(
                        input = inputTokenCount,
                        output = outputTokens,
                        total = inputTokenCount + outputTokens
                    )
                ))
            }
        }
    }

    private fun processMessageStart(eventData: JsonObject) {
        inputTokenCount = eventData["message"]?.jsonObject
            ?.get("usage")?.jsonObject
            ?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
    }

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val parsed = json.parseToJsonElement(responseText).jsonObject

        val error = parsed["error"]
        if (error != null) {
            val errorMsg = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                ?: responseText
            throw Exception(errorMsg)
        }

        val contentArray = parsed["content"]?.jsonArray
        var textContent = ""
        var thinkingContent = ""

        if (contentArray != null) {
            for (block in contentArray) {
                val blockObj = block.jsonObject
                when (blockObj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> {
                        textContent += blockObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    "thinking" -> {
                        thinkingContent += blockObj["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                }
            }
        }

        val usageRaw = parsed["usage"]?.jsonObject
        val usage = if (usageRaw != null) {
            val inputTokens = usageRaw["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val outputTokens = usageRaw["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            ProtocolUsage(
                input = inputTokens,
                output = outputTokens,
                total = inputTokens + outputTokens
            )
        } else null

        val stopReason = parsed["stop_reason"]?.jsonPrimitive?.contentOrNull

        val toolCalls = contentArray?.mapNotNull { block ->
            val blockObj = block.jsonObject
            if (blockObj["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                ProtocolToolCall(
                    id = blockObj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = blockObj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = blockObj["input"]?.let { input ->
                        json.encodeToString(JsonElement.serializer(), input)
                    } ?: "{}"
                )
            } else null
        }

        return PromptResponse(
            content = textContent,
            reasoning = thinkingContent.ifEmpty { null },
            toolCalls = toolCalls?.ifEmpty { null },
            usage = usage
        )
    }

    private fun normalizeError(e: Exception): StreamChunk.Error {
        val normalized = ErrorNormalizer.normalize(e)
        return StreamChunk.Error(normalized.message, normalized.retryable, normalized.category.name)
    }

    private fun JsonObject.stringField(key: String): String {
        val el = this[key] ?: return ""
        return if (el is JsonNull) "" else el.jsonPrimitive.contentOrNull ?: ""
    }
}

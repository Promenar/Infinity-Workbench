package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.remote.ThinkingDetector
import com.promenar.nexara.data.remote.parser.ErrorNormalizer
import com.promenar.nexara.data.remote.parser.HttpStatusException
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * Generic OpenAI-compatible protocol for Ollama, vLLM, LiteLLM, LocalAI etc.
 */
class GenericOpenAICompatProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Generic_OpenAI_Compat

    private val httpClient: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
        // 禁用默认的解压处理器，避免对 SSE 响应进行全量缓存
        expectSuccess = false
        followRedirects = true
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeChannel: ByteReadChannel? = null

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null
        val thinkingDetector = ThinkingDetector()
        val toolCallAccumulator = mutableMapOf<Int, AccumulatedToolCall>()

        try {
            httpClient.preparePost(buildUrl()) {
                contentType(ContentType.Application.Json)
                header("Accept", "text/event-stream")
                header("Accept-Encoding", "identity") // 强制禁用压缩，防止 Gzip 导致流式输出攒块
                header("Cache-Control", "no-cache")
                header("Connection", "keep-alive")
                if (apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(buildRequestBody(request, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (_: Exception) { "" }
                    val normalized = ErrorNormalizer.normalize(
                        HttpStatusException(response.status.value, errorBody)
                    )
                    send(StreamChunk.Error(normalized.message, normalized.retryable, normalized.category.name))
                    return@execute
                }

                // HTML 响应前置检测（CDN/Nginx 错误页拦截）
                response.contentType()?.let { ct ->
                    if (ct.match(ContentType.Text.Html)) {
                        send(StreamChunk.Error(
                            "Received HTML response instead of JSON stream. Check your Base URL settings."
                        ))
                        return@execute
                    }
                }

                val channel = response.body<ByteReadChannel>()
                activeChannel = channel
                val sb = StringBuilder()

                while (!channel.isClosedForRead) {
                    sb.clear()
                    val timeoutMs = request.streamTimeout ?: 120000L
                    val readSuccess = withTimeoutOrNull(timeoutMs) {
                        channel.readUTF8LineTo(sb, 1_048_576)
                    }

                    if (readSuccess == null) {
                        send(StreamChunk.Error("Streaming timeout after ${timeoutMs / 1000}s of inactivity."))
                        break
                    }
                    if (!readSuccess) break

                    val line = sb.toString().trim()
                    if (line.isEmpty()) continue

                    // HTML 行级检测（部分服务端在 SSE 流内返回 HTML 错误页）
                    if (line.trimStart().startsWith('<')) {
                        send(StreamChunk.Error("Received HTML response instead of JSON stream."))
                        break
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
            }
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
                if (apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(buildRequestBody(request, stream = false))
            }
        } catch (e: CancellationException) {
            throw e // 结构化并发：CancellationException 必须透传，否则 withTimeoutOrNull 失效
        } catch (e: Exception) {
            val normalized = ErrorNormalizer.normalize(e)
            throw Exception("[${normalized.category}] ${normalized.technicalMessage}")
        }

        val responseText = try { response.bodyAsText() } catch (_: Exception) { "" }

        if (!response.status.isSuccess()) {
            val normalized = ErrorNormalizer.normalize(
                HttpStatusException(response.status.value, responseText)
            )
            throw Exception("[HTTP ${response.status.value}][${normalized.category}] ${responseText.take(300)}")
        }

        return parseSyncResponse(responseText)
    }

    override suspend fun listModels(): List<String> {
        val response: HttpResponse
        try {
            response = httpClient.get(baseUrl.trimEnd('/') + "/models") {
                if (apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        if (!response.status.isSuccess()) return emptyList()

        val responseText = response.bodyAsText()
        return try {
            val root = json.parseToJsonElement(responseText).jsonObject
            root["data"]?.jsonArray?.mapNotNull {
                it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun cancel() {
        activeChannel?.cancel()
    }

    private fun buildUrl(): String {
        val cleanBase = baseUrl.trimEnd('/')
        // 如果 baseUrl 已经包含完整路径（如智谱 /v4/chat/completions），直接使用
        return if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"
    }

    private fun buildRequestBody(request: PromptRequest, stream: Boolean): String {
        val body = buildJsonObject {
            put("model", request.model.ifEmpty { this@GenericOpenAICompatProtocol.model })
            put("stream", stream)

            put("messages", JsonArray(request.messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role)
                    
                    val hasMultimodal = msg.imageUrls?.isNotEmpty() == true || msg.audioData?.isNotEmpty() == true
                    if (!hasMultimodal) {
                        put("content", msg.content.ifEmpty { "" })
                    } else {
                        val contentParts = buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            })
                            msg.imageUrls?.forEach { img ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", img.url ?: "data:${img.mimeType};base64,${img.base64}")
                                    })
                                })
                            }
                            msg.audioData?.forEach { aud ->
                                add(buildJsonObject {
                                    put("type", "input_audio")
                                    put("input_audio", buildJsonObject {
                                        put("data", aud.base64)
                                        put("format", if (aud.mimeType.contains("wav")) "wav" else "mp3")
                                    })
                                })
                            }
                        }
                        put("content", contentParts)
                    }

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

            val hasImages = request.messages.any { it.imageUrls?.isNotEmpty() == true }
            val hasAudio = request.messages.any { it.audioData?.isNotEmpty() == true }
            if (hasImages || hasAudio) {
                put("modalities", buildJsonArray {
                    add(JsonPrimitive("text"))
                    if (hasImages) add(JsonPrimitive("image"))
                    if (hasAudio) add(JsonPrimitive("audio"))
                })
            }

            ProtocolParamAdapter.mapCommonParams(this, request)
            ProtocolParamAdapter.mapPenaltyParams(this, request, protocolType)
            ProtocolParamAdapter.mapSamplingParams(this, request)

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
                // OpenAI SSE streaming 中 function.arguments 字段携带的是增量片段 (partial)
                // 协议层在本地累积完整 JSON，但发送给 ViewModel 的 ToolCallDelta 必须只携带
                // 本次新增的 fragment。否则 ViewModel 二次累积会导致参数膨胀/损坏。
                val fragment = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                if (existing == null) {
                    toolCallAccumulator[index] = AccumulatedToolCall(
                        id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                        arguments = fragment
                    )
                } else {
                    val newId = tc["id"]?.jsonPrimitive?.contentOrNull
                    val newName = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

                    toolCallAccumulator[index] = existing.copy(
                        id = if (newId.isNullOrEmpty()) existing.id else newId,
                        name = if (newName.isNullOrEmpty()) existing.name else newName,
                        arguments = existing.arguments + fragment
                    )
                }

                // 增量流式发送：与 OpenAIProtocol 对齐，每个 delta chunk 携带增量 fragment
                send(StreamChunk.ToolCallDelta(
                    id = toolCallAccumulator[index]!!.id,
                    name = toolCallAccumulator[index]!!.name,
                    arguments = fragment,
                    index = index
                ))
            }
        }

        // 无条件发送 TextDelta（同 OpenAIProtocol 修复：防止流式假死）
        send(StreamChunk.TextDelta(content, reasoning.ifEmpty { null }))

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

        // 工具调用已在流式过程中通过 ToolCallDelta(incremental fragment) 发送完毕
        // ViewModel 侧已完成累积，此处不再重复发送，避免双重累积
        // flushRemaining 仅负责清理 ThinkingDetector 残余 + 发送 Done 信号

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
        if (line.startsWith("data: ")) return line.substring(6).trim()
        if (line.startsWith("data:")) return line.substring(5).trim()
        return null
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

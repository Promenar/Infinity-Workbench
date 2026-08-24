package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.remote.parser.ErrorNormalizer
import com.promenar.nexara.data.remote.parser.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readUTF8LineTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.promenar.nexara.domain.generation.CompletionReason

private data class ResponsesToolAccumulator(
    var itemId: String = "",
    var callId: String = "",
    var name: String = "",
    var arguments: String = "",
)

class OpenAIResponsesProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.OpenAI_Responses

    private val httpClient: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeChannel: ByteReadChannel? = null

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null
        try {
            httpClient.preparePost(inferenceUrl()) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.CacheControl, "no-cache")
                setBody(buildRequestBody(request, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try {
                        response.bodyAsText()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ""
                    }
                    send(classifyProtocolError(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        retryAfterHeader = response.headers[HttpHeaders.RetryAfter],
                    ))
                    return@execute
                }

                val channel = response.body<ByteReadChannel>()
                activeChannel = channel
                val sb = StringBuilder()
                val toolCalls = mutableMapOf<Int, ResponsesToolAccumulator>()
                var sawTerminal = false
                while (!channel.isClosedForRead) {
                    sb.clear()
                    val timeoutMs = request.streamTimeout ?: 120000L
                    val readSuccess = withTimeoutOrNull(timeoutMs) {
                        channel.readUTF8LineTo(sb, 1_048_576)
                    }
                    if (readSuccess == null) {
                        send(StreamChunk.Error(
                            code = com.promenar.nexara.domain.generation.GenerationFailureCode.TIMEOUT,
                            retryable = true,
                            technical = "Streaming timeout after ${timeoutMs / 1000}s of inactivity.",
                        ))
                        sawTerminal = true
                        break
                    }
                    if (!readSuccess) break

                    val data = extractSseData(sb.toString().trim()) ?: continue
                    if (data.trim() == "[DONE]") continue
                    val chunk = try {
                        json.parseToJsonElement(data).jsonObject
                    } catch (_: Exception) {
                        send(streamContractError("Malformed OpenAI Responses stream event"))
                        sawTerminal = true
                        break
                    }
                    val terminal = processResponsesStreamChunk(chunk, toolCalls)
                    if (terminal != null) {
                        send(terminal)
                        sawTerminal = true
                        break
                    }
                }
                if (!sawTerminal) {
                    send(truncatedStreamError("OpenAI Responses stream ended before response.completed"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ErrorNormalizer.normalize(e).toStreamChunkError())
        } finally {
            activeChannel = null
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val response: HttpResponse = try {
            httpClient.post(inferenceUrl()) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(buildRequestBody(request, stream = false))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val normalized = ErrorNormalizer.normalize(e)
            throw Exception("[${normalized.category}] ${normalized.technicalMessage}")
        }

        val responseText = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        if (!response.status.isSuccess()) {
            val normalized = ErrorNormalizer.normalize(HttpStatusException(response.status.value, responseText))
            throw Exception("[HTTP ${response.status.value}][${normalized.category}] ${responseText.take(300)}")
        }

        return parseSyncResponse(responseText)
    }

    override suspend fun listModels(): List<String> {
        val endpoint = ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointOperation.MODELS,
        )
        val response = try {
            httpClient.get(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        if (!response.status.isSuccess()) return emptyList()
        val responseText = response.bodyAsText()
        return try {
            val root = json.parseToJsonElement(responseText).jsonObject
            root["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun cancel() {
        activeChannel?.cancel()
    }

    private fun inferenceUrl(): String = ProviderEndpointResolver.resolve(
        protocolType,
        baseUrl,
        ProviderEndpointOperation.INFERENCE,
    )

    private fun buildRequestBody(request: PromptRequest, stream: Boolean): String {
        return buildJsonObject {
            put("model", request.model.ifEmpty { this@OpenAIResponsesProtocol.model })
            put("stream", stream)
            put("input", JsonArray(request.messages.flatMap { msg -> msg.toResponsesInputItems() }))
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            request.maxTokens?.let { put("max_output_tokens", it) }
            if (!request.tools.isNullOrEmpty()) {
                put("tools", JsonArray(request.tools.map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        put("parameters", json.parseToJsonElement(tool.function.parameters))
                    }
                }))
                put("tool_choice", "auto")
            }
        }.toString()
    }

    private fun ProtocolMessage.toResponsesInputItems(): List<JsonObject> {
        if (role == "tool") {
            val callId = toolCallId.orEmpty()
            return listOf(buildJsonObject {
                put("type", "function_call_output")
                put("call_id", callId)
                put("output", content)
            })
        }
        val items = mutableListOf<JsonObject>()
        if (content.isNotEmpty() || toolCalls.isNullOrEmpty()) {
            items += buildJsonObject {
                put("role", if (role == "system") "developer" else role)
                val hasMultimodal = imageUrls?.isNotEmpty() == true || audioData?.isNotEmpty() == true
                if (!hasMultimodal) {
                    put("content", content)
                } else {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", content)
                        })
                        imageUrls?.forEach { img ->
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", img.url ?: "data:${img.mimeType};base64,${img.base64}")
                            })
                        }
                    })
                }
            }
        }
        if (role == "assistant") {
            toolCalls.orEmpty().forEach { call ->
                items += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                }
            }
        }
        return items
    }

    private suspend fun SendChannel<StreamChunk>.processResponsesStreamChunk(
        chunk: JsonObject,
        toolCalls: MutableMap<Int, ResponsesToolAccumulator>,
    ): StreamChunk? {
        when (val type = chunk.stringField("type")) {
            "response.output_text.delta" -> {
                send(StreamChunk.TextDelta(chunk.stringField("delta")))
            }
            "response.output_item.added", "response.output_item.done" -> {
                val index = chunk["output_index"]?.jsonPrimitive?.intOrNull
                    ?: return streamContractError("Responses function_call missing output_index")
                val item = chunk["item"] as? JsonObject ?: return null
                if (item.stringField("type") == "function_call") {
                    mergeResponsesToolCall(index, item, toolCalls)
                }
            }
            "response.function_call_arguments.delta" -> {
                val index = chunk["output_index"]?.jsonPrimitive?.intOrNull
                    ?: return streamContractError("Responses arguments delta missing output_index")
                val accumulator = toolCalls[index]
                    ?: return streamContractError("Responses arguments delta missing function_call")
                val delta = chunk.stringField("delta")
                accumulator.arguments += delta
                send(StreamChunk.ToolCallDelta(
                    accumulator.callId,
                    accumulator.name,
                    delta,
                    index,
                ))
            }
            "response.function_call_arguments.done" -> {
                val index = chunk["output_index"]?.jsonPrimitive?.intOrNull
                    ?: return streamContractError("Responses arguments done missing output_index")
                val accumulator = toolCalls[index]
                    ?: return streamContractError("Responses arguments done missing function_call")
                val finalArguments = chunk.stringField("arguments")
                if (finalArguments.isNotEmpty() && finalArguments != accumulator.arguments) {
                    if (!finalArguments.startsWith(accumulator.arguments)) {
                        return streamContractError("Responses argument fragments do not match done event")
                    }
                    val remainder = finalArguments.removePrefix(accumulator.arguments)
                    accumulator.arguments = finalArguments
                    if (remainder.isNotEmpty()) {
                        send(StreamChunk.ToolCallDelta(
                            accumulator.callId,
                            accumulator.name,
                            remainder,
                            index,
                        ))
                    }
                }
            }
            "response.completed" -> {
                val response = chunk["response"]?.jsonObject
                    ?: return streamContractError("Responses completed event missing response")
                if (response.stringField("status") != "completed") {
                    return streamContractError("Responses completed event has unknown status")
                }
                (response["output"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
                    val item = element as? JsonObject ?: return@forEachIndexed
                    if (item.stringField("type") == "function_call") {
                        mergeResponsesToolCall(index, item, toolCalls)
                    }
                }
                val usage = response?.get("usage")?.jsonObject?.toResponsesUsage()
                if (usage != null) send(StreamChunk.Usage(usage))
                val calls = toolCalls.toSortedMap().values.map {
                    ProtocolToolCall(it.callId, it.name, it.arguments)
                }
                return validatedCompletion(
                    if (calls.isEmpty()) CompletionReason.END_TURN else CompletionReason.TOOL_CALLS,
                    calls,
                )
            }
            "response.failed", "response.incomplete", "error" -> {
                val errorObject = when (type) {
                    "response.failed", "response.incomplete" ->
                        (chunk["response"] as? JsonObject)?.get("error") as? JsonObject
                        ?: chunk["error"] as? JsonObject
                    else -> chunk["error"] as? JsonObject
                }
                return ProtocolErrorClassifier.classify(
                    type = errorObject?.stringField("type"),
                    code = errorObject?.stringField("code"),
                    technical = errorObject?.stringField("message")
                        ?.ifBlank { chunk.stringField("message") }
                        ?.ifBlank { null }
                        ?: "OpenAI Responses request failed.",
                )
            }
            else -> {
                val delta = chunk.stringField("delta")
                if (delta.isNotEmpty() && type.contains("output_text")) {
                    send(StreamChunk.TextDelta(delta))
                }
            }
        }
        return null
    }

    private fun mergeResponsesToolCall(
        index: Int,
        item: JsonObject,
        toolCalls: MutableMap<Int, ResponsesToolAccumulator>,
    ) {
        val accumulator = toolCalls.getOrPut(index) { ResponsesToolAccumulator() }
        item.stringField("id").takeIf(String::isNotEmpty)?.let { accumulator.itemId = it }
        item.stringField("call_id").takeIf(String::isNotEmpty)?.let { accumulator.callId = it }
        item.stringField("name").takeIf(String::isNotEmpty)?.let { accumulator.name = it }
        val arguments = item.stringField("arguments")
        if (arguments.isNotEmpty()) {
            if (accumulator.arguments.isNotEmpty() && arguments != accumulator.arguments) {
                check(arguments.startsWith(accumulator.arguments)) {
                    "Responses function_call arguments mismatch"
                }
            }
            accumulator.arguments = arguments
        }
    }

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val root = json.parseToJsonElement(responseText).jsonObject
        val toolCalls = root["output"]?.jsonArray
            ?.mapNotNull { outputItem ->
                val item = outputItem as? JsonObject ?: return@mapNotNull null
                if (item.stringField("type") != "function_call") return@mapNotNull null
                ProtocolToolCall(
                    id = item.stringField("call_id"),
                    name = item.stringField("name"),
                    arguments = item.stringField("arguments"),
                )
            }
            .orEmpty()
        if (toolCalls.isNotEmpty()) {
            val ids = toolCalls.map { it.id }
            check(
                ids.all(String::isNotBlank) &&
                    ids.distinct().size == ids.size &&
                    toolCalls.all { call ->
                        call.name.isNotBlank() &&
                            runCatching { json.parseToJsonElement(call.arguments) is JsonObject }
                                .getOrDefault(false)
                    },
            ) { "Responses sync function_call is incomplete" }
        }
        val outputText = root.stringField("output_text").ifBlank {
            root["output"]?.jsonArray
                ?.flatMap { outputItem ->
                    outputItem.jsonObject["content"]?.jsonArray?.mapNotNull { contentItem ->
                        val contentObject = contentItem.jsonObject
                        if (contentObject.stringField("type") == "output_text") contentObject.stringField("text") else null
                    } ?: emptyList()
                }
                ?.joinToString("")
                .orEmpty()
        }
        val usage = root["usage"]?.jsonObject?.toResponsesUsage()
        return PromptResponse(
            content = outputText,
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage,
        )
    }

    private fun JsonObject.toResponsesUsage(): ProtocolUsage {
        val input = this["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val output = this["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val total = this["total_tokens"]?.jsonPrimitive?.intOrNull ?: (input + output)
        return ProtocolUsage(input = input, output = output, total = total)
    }

    private fun extractSseData(line: String): String? {
        if (line.startsWith("data: ")) return line.substring(6)
        if (line.startsWith("data:")) return line.substring(5)
        return null
    }

    private fun classifyProtocolError(
        statusCode: Int,
        responseBody: String,
        retryAfterHeader: String?,
    ): StreamChunk.Error {
        val errorObject = runCatching {
            json.parseToJsonElement(responseBody).jsonObject["error"] as? JsonObject
        }.getOrNull()
        return ProtocolErrorClassifier.classify(
            statusCode = statusCode,
            type = errorObject?.stringField("type"),
            code = errorObject?.stringField("code"),
            retryAfterHeader = retryAfterHeader,
            technical = errorObject?.stringField("message")?.ifBlank { responseBody } ?: responseBody,
        )
    }

    private fun JsonObject.stringField(key: String): String {
        val el = this[key] ?: return ""
        return if (el is JsonNull) "" else el.jsonPrimitive.contentOrNull ?: ""
    }
}

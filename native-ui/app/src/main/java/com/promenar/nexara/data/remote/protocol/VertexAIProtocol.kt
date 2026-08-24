package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.remote.provider.ParsedVertexCredential
import com.promenar.nexara.data.remote.provider.VertexCredentialException
import com.promenar.nexara.data.remote.provider.VertexCredentialFailure
import com.promenar.nexara.data.remote.provider.VertexCredentialParser
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import com.promenar.nexara.domain.generation.CompletionReason

class VertexAIProtocol(
    private val serviceAccountJson: String,
    private val projectId: String,
    private val location: String = VERTEX_DEFAULT_LOCATION,
    private val model: String = "",
    private val baseUrl: String = "",
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Google_VertexAI

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

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiryMs: Long = 0L

    @Volatile
    private var parsedCredential: ParsedVertexCredential? = null

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null

        if (request.containsToolProtocolData()) {
            send(StreamChunk.Error(
                code = com.promenar.nexara.domain.generation.GenerationFailureCode.INVALID_REQUEST,
                retryable = false,
                technical = "Vertex tool calls are disabled until thought-signature round trips are verified",
            ))
            return@channelFlow
        }

        val credential = try {
            parsedCredential()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            send(StreamChunk.Error(
                code = com.promenar.nexara.domain.generation.GenerationFailureCode.AUTH,
                retryable = false,
                technical = "Vertex AI Authentication Failed: ${safeCredentialError(error)}",
            ))
            return@channelFlow
        }

        val endpoint = try {
            inferenceUrl(request, streaming = true, credential = credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            send(StreamChunk.Error(
                code = com.promenar.nexara.domain.generation.GenerationFailureCode.INVALID_REQUEST,
                retryable = false,
                technical = "Vertex endpoint validation failed",
            ))
            return@channelFlow
        }

        val token: String
        try {
            token = getAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(StreamChunk.Error(
                code = com.promenar.nexara.domain.generation.GenerationFailureCode.AUTH,
                retryable = false,
                technical = "Vertex AI Authentication Failed: ${safeCredentialError(e)}",
            ))
            return@channelFlow
        }

        val response: HttpResponse
        try {
            response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(buildRequestBody(request))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(normalizeError(e))
            return@channelFlow
        }

        if (!response.status.isSuccess()) {
            val errorBody = try {
                response.bodyAsText()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
            val normalized = ErrorNormalizer.normalize(
                HttpStatusException(response.status.value, errorBody)
            )
            send(normalized.toStreamChunkError())
            return@channelFlow
        }

        response.contentType()?.let { ct ->
            if (ct.match(ContentType.Text.Html)) {
                send(StreamChunk.Error(
                    code = com.promenar.nexara.domain.generation.GenerationFailureCode.SERVER,
                    retryable = false,
                    technical = "Received HTML response instead of JSON stream.",
                ))
                return@channelFlow
            }
        }

        val channel = response.body<ByteReadChannel>()
        activeChannel = channel

        try {
            val sb = StringBuilder()
            val timeoutMs = request.streamTimeout ?: 120000L
            var completionReason: CompletionReason? = null
            var failed = false

            while (!channel.isClosedForRead) {
                sb.clear()
                val readSuccess = withTimeoutOrNull(timeoutMs) {
                    channel.readUTF8LineTo(sb, 1_048_576)
                }

                if (readSuccess == null) {
                    send(StreamChunk.Error(
                        code = com.promenar.nexara.domain.generation.GenerationFailureCode.TIMEOUT,
                        retryable = true,
                        technical = "Streaming timeout after ${timeoutMs / 1000}s of inactivity.",
                    ))
                    failed = true
                    return@channelFlow
                }
                if (!readSuccess) break

                val line = sb.toString()
                if (line.isEmpty()) continue

                if (line.trimStart().startsWith('<')) {
                    send(StreamChunk.Error(
                        code = com.promenar.nexara.domain.generation.GenerationFailureCode.SERVER,
                        retryable = false,
                        technical = "Received HTML response instead of JSON stream.",
                    ))
                    failed = true
                    return@channelFlow
                }

                if (line.startsWith("data: ")) {
                    val data = line.substring(6).trim()
                    if (data == "[DONE]") break
                    val reason = tryProcessJsonObject(data)
                    if (reason != null) {
                        check(completionReason == null) { "Duplicate Vertex finishReason" }
                        completionReason = reason
                    }
                    continue
                }

                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed == "[") continue
                if (trimmed == "]") continue
                if (trimmed == "],") continue
                if (trimmed == ",") continue

                val jsonCandidate = if (trimmed.endsWith(",")) trimmed.dropLast(1) else trimmed
                val reason = tryProcessJsonObject(jsonCandidate)
                if (reason != null) {
                    check(completionReason == null) { "Duplicate Vertex finishReason" }
                    completionReason = reason
                }
            }

            if (!failed) {
                val reason = completionReason
                if (reason == null) {
                    send(truncatedStreamError("Vertex stream ended before STOP finishReason"))
                } else {
                    send(StreamChunk.Completed(reason))
                }
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
        require(!request.containsToolProtocolData()) {
            "Vertex tool calls are disabled until thought-signature round trips are verified"
        }
        val credential = try {
            parsedCredential()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw Exception("Vertex AI Authentication Failed: ${safeCredentialError(error)}")
        }
        // 必须在创建 JWT 或交换 OAuth token 前验证 inference origin 与所有路径 segment。
        val endpoint = inferenceUrl(request, streaming = false, credential = credential)
        val token: String
        try {
            token = getAccessToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw Exception("Vertex AI Authentication Failed: ${safeCredentialError(e)}")
        }

        val response: HttpResponse
        try {
            response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(buildRequestBody(request))
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
            val normalized = ErrorNormalizer.normalize(
                HttpStatusException(response.status.value, responseText)
            )
            throw Exception("[HTTP ${response.status.value}][${normalized.category}] ${responseText.take(300)}")
        }

        return parseSyncResponse(responseText)
    }

    override suspend fun listModels(): List<String> {
        ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointOperation.MODELS,
        )
        return emptyList()
    }

    override fun cancel() {
        activeChannel?.cancel()
    }

    private suspend fun getAccessToken(): String {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < tokenExpiryMs - 300_000) {
                return token
            }
        }

        val credential = parsedCredential()
        val jwt = VertexCredentialParser.createJwtAssertion(credential)
        val tokenResponse = exchangeJwtForToken(jwt)

        cachedToken = tokenResponse.accessToken
        tokenExpiryMs = System.currentTimeMillis() + tokenResponse.expiresInSeconds * 1000L

        return tokenResponse.accessToken
    }

    private fun parsedCredential(): ParsedVertexCredential {
        parsedCredential?.let { return it }
        return VertexCredentialParser.parse(serviceAccountJson, projectId).also {
            parsedCredential = it
        }
    }

    private fun safeCredentialError(error: Exception): String = when (
        (error as? VertexCredentialException)?.reason
    ) {
        VertexCredentialFailure.MISSING_CREDENTIALS -> "Service account credentials are missing"
        VertexCredentialFailure.INVALID_JSON -> "Service account credentials are invalid"
        VertexCredentialFailure.MISSING_PROJECT_ID,
        VertexCredentialFailure.MISSING_CLIENT_EMAIL,
        VertexCredentialFailure.MISSING_PRIVATE_KEY,
        VertexCredentialFailure.PROJECT_ID_MISMATCH,
        -> "Service account credentials are incomplete"
        VertexCredentialFailure.INVALID_PRIVATE_KEY -> "Service account private key is invalid"
        null -> "Vertex authentication request failed"
    }

    private data class TokenResponse(
        val accessToken: String,
        val expiresInSeconds: Long
    )

    private suspend fun exchangeJwtForToken(jwt: String): TokenResponse {
        val endpoint = ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointTarget.VertexOAuthToken,
        )
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
            )
        }

        val body = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Vertex token exchange failed")
        }

        val parsed = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("Vertex token exchange response is invalid")
        }

        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Vertex token exchange response is invalid")

        val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

        return TokenResponse(accessToken, expiresIn)
    }

    private fun inferenceUrl(
        request: PromptRequest,
        streaming: Boolean,
        credential: ParsedVertexCredential,
    ): String {
        return ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointTarget.VertexInference(
                projectId = credential.projectId,
                location = location,
                model = request.model.ifBlank { model },
                streaming = streaming,
            ),
        )
    }

    private fun buildRequestBody(request: PromptRequest): String {
        val systemMessages = request.messages.filter { it.role == "system" }
        val contentMessages = request.messages.filter { it.role != "system" }
        val normalizedTurns = normalizeTurns(contentMessages)

        val body = buildJsonObject {
            put("contents", JsonArray(normalizedTurns.map { turn ->
                buildJsonObject {
                    put("role", turn.role)
                    put("parts", JsonArray(turn.parts))
                }
            }))

            put("safety_settings", JsonArray(listOf(
                buildJsonObject {
                    put("category", "HARM_CATEGORY_HARASSMENT")
                    put("threshold", "BLOCK_NONE")
                },
                buildJsonObject {
                    put("category", "HARM_CATEGORY_HATE_SPEECH")
                    put("threshold", "BLOCK_NONE")
                },
                buildJsonObject {
                    put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                    put("threshold", "BLOCK_NONE")
                },
                buildJsonObject {
                    put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                    put("threshold", "BLOCK_NONE")
                },
                buildJsonObject {
                    put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                    put("threshold", "BLOCK_NONE")
                }
            )))

            put("generation_config", buildJsonObject {
                ProtocolParamAdapter.mapCommonParamsVertexAI(this, request)
                ProtocolParamAdapter.mapPenaltyParamsVertexAI(this, request)
                ProtocolParamAdapter.mapSamplingParams(this, request)

                if (request.reasoning == true) {
                    put("thinking_config", buildJsonObject {
                        put("include_thoughts", true)
                    })
                }
            })

            if (systemMessages.isNotEmpty()) {
                val combinedSystem = systemMessages.joinToString("\n") { it.content }
                put("system_instruction", buildJsonObject {
                    put("parts", JsonArray(listOf(
                        buildJsonObject { put("text", combinedSystem) }
                    )))
                })
            }

            val shouldAddGeminiSearch = request.enableGeminiSearch != false

            if ((request.tools != null && request.tools.isNotEmpty()) || shouldAddGeminiSearch) {
                val toolsList = buildJsonArray {
                    if (shouldAddGeminiSearch) {
                        add(buildJsonObject {
                            put("googleSearchRetrieval", buildJsonObject {})
                        })
                    }
                    if (request.tools != null && request.tools.isNotEmpty()) {
                        add(buildJsonObject {
                            put("functionDeclarations", JsonArray(request.tools.map { tool ->
                                buildJsonObject {
                                    put("name", tool.function.name)
                                    put("description", tool.function.description)
                                    val paramsObj = try {
                                        json.parseToJsonElement(tool.function.parameters).jsonObject
                                    } catch (_: Exception) {
                                        buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {})
                                        }
                                    }
                                    put("parameters", paramsObj)
                                }
                            }))
                        })
                    }
                }
                put("tools", toolsList)
            }
        }

        return body.toString()
    }

    private data class NormalizedTurn(
        val role: String,
        val parts: List<JsonObject>
    )

    private fun normalizeTurns(messages: List<ProtocolMessage>): List<NormalizedTurn> {
        val turns = mutableListOf<NormalizedTurn>()

        for (msg in messages) {
            val parts = formatMessageParts(msg)
            if (parts.isEmpty()) continue

            val geminiRole = when (msg.role) {
                "user", "tool" -> "user"
                "assistant" -> "model"
                else -> continue
            }

            val lastTurn = turns.lastOrNull()

            if (lastTurn != null && lastTurn.role == geminiRole) {
                if (msg.role == "tool" && lastTurn.role == "user") {
                    turns[turns.lastIndex] = lastTurn.copy(
                        parts = lastTurn.parts + parts
                    )
                } else if (msg.role == "user" && lastTurn.role == "user") {
                    turns.add(NormalizedTurn(
                        "model",
                        listOf(buildJsonObject { put("text", "OK") })
                    ))
                    turns.add(NormalizedTurn(geminiRole, parts))
                } else {
                    turns[turns.lastIndex] = lastTurn.copy(
                        parts = lastTurn.parts + parts
                    )
                }
            } else {
                turns.add(NormalizedTurn(geminiRole, parts))
            }
        }

        if (turns.isNotEmpty() && turns[0].role != "user") {
            turns.add(0, NormalizedTurn(
                "user",
                listOf(buildJsonObject { put("text", "Please proceed.") })
            ))
        }

        return turns
    }

    private fun formatMessageParts(msg: ProtocolMessage): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()

        when (msg.role) {
            "tool" -> {
                parts.add(buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", msg.name ?: "unknown")
                        put("response", buildJsonObject {
                            put("content", msg.content)
                        })
                    })
                })
            }
            "assistant" -> {
                if (!msg.reasoning.isNullOrEmpty()) {
                    parts.add(buildJsonObject {
                        put("thought", true)
                        put("text", msg.reasoning)
                    })
                }
                if (msg.content.isNotEmpty()) {
                    parts.add(buildJsonObject { put("text", msg.content) })
                }
                if (!msg.toolCalls.isNullOrEmpty()) {
                    for (tc in msg.toolCalls) {
                        val argsElement = try {
                            json.parseToJsonElement(tc.arguments)
                        } catch (_: Exception) {
                            buildJsonObject {}
                        }
                        parts.add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", tc.name)
                                put("args", argsElement)
                            })
                        })
                    }
                }
                if (parts.isEmpty() && msg.content.isEmpty()) {
                    parts.add(buildJsonObject { put("text", "") })
                }
            }
            else -> {
                if (msg.content.isNotEmpty()) {
                    parts.add(buildJsonObject { put("text", msg.content) })
                }
                
                msg.imageUrls?.forEach { img ->
                    parts.add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", img.mimeType)
                            put("data", img.base64 ?: "")
                        })
                    })
                }
                
                msg.audioData?.forEach { aud ->
                    parts.add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", aud.mimeType)
                            put("data", aud.base64)
                        })
                    })
                }
                
                msg.documentData?.forEach { doc ->
                    if (doc.url != null) {
                        parts.add(buildJsonObject {
                            put("fileData", buildJsonObject {
                                put("mimeType", doc.mimeType)
                                put("fileUri", doc.url)
                            })
                        })
                    } else {
                        parts.add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", doc.mimeType)
                                put("data", doc.base64 ?: "")
                            })
                        })
                    }
                }
            }
        }

        return parts
    }

    private suspend fun SendChannel<StreamChunk>.tryProcessJsonObject(jsonStr: String): CompletionReason? {
        val chunk = try {
            json.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("Malformed Vertex stream event")
        }

        return processGeminiChunk(chunk)
    }

    private suspend fun SendChannel<StreamChunk>.processGeminiChunk(chunk: JsonObject): CompletionReason? {
        val candidates = chunk["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) return null

        val candidate = candidates.firstOrNull()?.jsonObject ?: return null

        var text = ""
        var reasoning = ""
        val contentParts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
        if (contentParts != null) {
            for (partElement in contentParts) {
                val part = partElement.jsonObject

                val isThought = part["thought"]?.let { el ->
                    when {
                        el.jsonPrimitive.booleanOrNull == true -> true
                        el.jsonPrimitive.contentOrNull?.isNotEmpty() == true &&
                            el.jsonPrimitive.contentOrNull != "false" -> true
                        else -> false
                    }
                } ?: false

                if (isThought) {
                    reasoning += part.stringField("text")
                    val thoughtText = part["thought"]?.jsonPrimitive?.contentOrNull
                    if (!thoughtText.isNullOrEmpty() && thoughtText != "true" && thoughtText != "false") {
                        reasoning += thoughtText
                    }
                } else if (part.containsKey("text")) {
                    text += part.stringField("text")
                } else if (part.containsKey("functionCall")) {
                    throw IllegalStateException("Vertex returned an unsupported functionCall")
                }
            }
        }

        val citations = candidate["groundingMetadata"]
            ?.jsonObject?.get("groundingChunks")?.jsonArray?.mapNotNull { gc ->
                val web = gc.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
                ProtocolCitation(
                    title = web.stringField("title").ifEmpty { "Web Source" },
                    url = web.stringField("uri"),
                    source = "Google"
                )
            }

        if (text.isNotEmpty() || reasoning.isNotEmpty()) {
            send(StreamChunk.TextDelta(text, reasoning.ifEmpty { null }))
        }

        if (citations != null && citations.isNotEmpty()) {
            send(StreamChunk.Citations(citations))
        }

        val usageMetadata = chunk["usageMetadata"]?.jsonObject
        if (usageMetadata != null) {
            send(StreamChunk.Usage(ProtocolUsage(
                input = usageMetadata["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                output = usageMetadata["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                total = usageMetadata["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
            )))
        }

        return when (val finishReason = candidate.stringField("finishReason")) {
            "" -> null
            "STOP" -> CompletionReason.END_TURN
            else -> throw IllegalStateException("Unknown Vertex finishReason: $finishReason")
        }
    }

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val parsed: JsonObject
        try {
            parsed = json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw Exception("Invalid JSON response from Vertex AI: ${responseText.take(200)}")
        }

        val error = parsed["error"]
        if (error != null) {
            val errorMsg = error.jsonObject
                ?.let { it["message"]?.jsonPrimitive?.contentOrNull ?: it["status"]?.jsonPrimitive?.contentOrNull }
                ?: responseText.take(200)
            throw Exception(errorMsg)
        }

        val promptFeedback = parsed["promptFeedback"]?.jsonObject
        val blockReason = promptFeedback?.get("blockReason")?.jsonPrimitive?.contentOrNull
        if (blockReason != null) {
            throw Exception("Vertex AI prompt blocked: $blockReason")
        }

        val candidatesArray = parsed["candidates"]?.jsonArray
        if (candidatesArray.isNullOrEmpty()) {
            throw Exception("Vertex AI returned no candidates")
        }

        val candidate = candidatesArray.first().jsonObject
        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull

        var textContent = ""
        var reasoningContent = ""
        val contentParts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
        if (contentParts != null) {
            for (partElement in contentParts) {
                val part = partElement.jsonObject

                val isThought = part["thought"]?.let { el ->
                    el.jsonPrimitive.booleanOrNull == true ||
                        (el.jsonPrimitive.contentOrNull?.isNotEmpty() == true &&
                            el.jsonPrimitive.contentOrNull != "false")
                } ?: false

                if (isThought) {
                    reasoningContent += part.stringField("text")
                } else if (part.containsKey("text")) {
                    textContent += part.stringField("text")
                } else if (part.containsKey("functionCall")) {
                    throw IllegalStateException("Vertex returned an unsupported functionCall")
                }
            }
        }

        if (textContent.isEmpty() && reasoningContent.isEmpty()) {
            if (finishReason != null && finishReason != "STOP" && finishReason != "END_TURN") {
                throw Exception("Vertex AI response finished with reason: $finishReason")
            }
        }

        val citations = candidate["groundingMetadata"]
            ?.jsonObject?.get("groundingChunks")?.jsonArray?.mapNotNull { gc ->
                val web = gc.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
                ProtocolCitation(
                    title = web.stringField("title").ifEmpty { "Web Source" },
                    url = web.stringField("uri"),
                    source = "Google"
                )
            }

        val usageMetadata = parsed["usageMetadata"]?.jsonObject
        val usage = if (usageMetadata != null) {
            ProtocolUsage(
                input = usageMetadata["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                output = usageMetadata["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                total = usageMetadata["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
            )
        } else null

        return PromptResponse(
            content = textContent,
            reasoning = reasoningContent.ifEmpty { null },
            usage = usage,
            citations = citations?.ifEmpty { null }
        )
    }

    private fun PromptRequest.containsToolProtocolData(): Boolean =
        !tools.isNullOrEmpty() || messages.any { message ->
            message.role == "tool" || !message.toolCalls.isNullOrEmpty()
        }

    private fun normalizeError(e: Exception): StreamChunk.Error {
        val normalized = ErrorNormalizer.normalize(e)
        return normalized.toStreamChunkError()
    }

    private fun JsonObject.stringField(key: String): String {
        val el = this[key] ?: return ""
        return if (el is JsonNull) "" else el.jsonPrimitive.contentOrNull ?: ""
    }
}

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
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class VertexAIProtocol(
    private val serviceAccountKeyPath: String,
    private val projectId: String,
    private val location: String = "us-central1",
    private val model: String = "gemini-3-flash-preview",
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val id: ProtocolId = ProtocolId.VERTEX_AI

    private val httpClient: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
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
    private var serviceAccountKeyData: ServiceAccountKey? = null

    private data class ServiceAccountKey(
        val clientEmail: String,
        val privateKey: String
    )

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null

        val token: String
        try {
            token = getAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(StreamChunk.Error(
                message = "Vertex AI Authentication Failed: ${e.message}",
                retryable = false,
                category = "AUTH"
            ))
            return@channelFlow
        }

        val response: HttpResponse
        try {
            response = httpClient.post(buildStreamingUrl()) {
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
                    "Received HTML response instead of JSON stream. Check your Vertex AI settings."
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
                        "Received HTML response instead of JSON stream. Check your Vertex AI settings."
                    ))
                    return@channelFlow
                }

                if (line.startsWith("data: ")) {
                    val data = line.substring(6).trim()
                    if (data == "[DONE]") break
                    tryProcessJsonObject(data)
                    continue
                }

                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed == "[") continue
                if (trimmed == "]") continue
                if (trimmed == "],") continue
                if (trimmed == ",") continue

                val jsonCandidate = if (trimmed.endsWith(",")) trimmed.dropLast(1) else trimmed
                tryProcessJsonObject(jsonCandidate)
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
        val token: String
        try {
            token = getAccessToken()
        } catch (e: Exception) {
            throw Exception("Vertex AI Authentication Failed: ${e.message}")
        }

        val response: HttpResponse
        try {
            response = httpClient.post(buildSyncUrl()) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(buildRequestBody(request))
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

    private suspend fun getAccessToken(): String {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < tokenExpiryMs - 300_000) {
                return token
            }
        }

        val keyData = loadServiceAccountKey()
        val jwt = createJwt(keyData)
        val tokenResponse = exchangeJwtForToken(jwt)

        cachedToken = tokenResponse.accessToken
        tokenExpiryMs = System.currentTimeMillis() + tokenResponse.expiresInSeconds * 1000L

        return tokenResponse.accessToken
    }

    private fun loadServiceAccountKey(): ServiceAccountKey {
        serviceAccountKeyData?.let { return it }

        val keyFile = File(serviceAccountKeyPath)
        if (!keyFile.exists()) {
            throw IllegalStateException(
                "Service account key file not found: $serviceAccountKeyPath"
            )
        }

        val keyJson = json.parseToJsonElement(keyFile.readText()).jsonObject
        val key = ServiceAccountKey(
            clientEmail = keyJson["client_email"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing client_email in service account key"),
            privateKey = keyJson["private_key"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing private_key in service account key")
        )

        serviceAccountKeyData = key
        return key
    }

    private fun createJwt(keyData: ServiceAccountKey): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""

        val now = System.currentTimeMillis() / 1000
        val claims = buildJsonObject {
            put("iss", keyData.clientEmail)
            put("scope", "https://www.googleapis.com/auth/cloud-platform")
            put("aud", "https://oauth2.googleapis.com/token")
            put("exp", now + 3600)
            put("iat", now)
        }.toString()

        val headerB64 = base64UrlEncode(header.toByteArray(Charsets.UTF_8))
        val claimsB64 = base64UrlEncode(claims.toByteArray(Charsets.UTF_8))
        val signInput = "$headerB64.$claimsB64"

        val privateKey = loadRsaPrivateKey(keyData.privateKey)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signInput.toByteArray(Charsets.UTF_8))
        val signatureBytes = sig.sign()

        return "$signInput.${base64UrlEncode(signatureBytes)}"
    }

    private data class TokenResponse(
        val accessToken: String,
        val expiresInSeconds: Long
    )

    private suspend fun exchangeJwtForToken(jwt: String): TokenResponse {
        val response = httpClient.post("https://oauth2.googleapis.com/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
            )
        }

        val body = try { response.bodyAsText() } catch (_: Exception) { "" }

        if (!response.status.isSuccess()) {
            throw Exception(
                "Token exchange failed: ${response.status.value} ${body.take(200)}"
            )
        }

        val parsed = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw Exception("Token exchange returned non-JSON response: ${body.take(200)}")
        }

        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw Exception("Token exchange response missing access_token")

        val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

        return TokenResponse(accessToken, expiresIn)
    }

    private fun loadRsaPrivateKey(pem: String): RSAPrivateCrtKey {
        val pemContent = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(pemContent)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec) as RSAPrivateCrtKey
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun buildHost(): String {
        return if (location == "global") {
            "aiplatform.googleapis.com"
        } else {
            "$location-aiplatform.googleapis.com"
        }
    }

    private fun buildStreamingUrl(): String {
        val host = buildHost()
        return "https://$host/v1beta1/projects/$projectId/locations/$location/publishers/google/models/${model}:streamGenerateContent?alt=sse"
    }

    private fun buildSyncUrl(): String {
        val host = buildHost()
        return "https://$host/v1beta1/projects/$projectId/locations/$location/publishers/google/models/${model}:generateContent"
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
                put("temperature", request.temperature ?: 0.7)
                request.topP?.let { if (it < 1.0) put("top_p", it) }
                request.maxTokens?.let { put("max_output_tokens", it) }

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
            }
        }

        return parts
    }

    private suspend fun SendChannel<StreamChunk>.tryProcessJsonObject(jsonStr: String) {
        val chunk = try {
            json.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Exception) {
            return
        }

        processGeminiChunk(chunk)
    }

    private suspend fun SendChannel<StreamChunk>.processGeminiChunk(chunk: JsonObject) {
        val candidates = chunk["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) return

        val candidate = candidates.firstOrNull()?.jsonObject ?: return

        var text = ""
        var reasoning = ""
        val toolCalls = mutableListOf<ProtocolToolCall>()

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
                    val fc = part["functionCall"]!!.jsonObject
                    val argsStr = fc["args"]?.let { args ->
                        json.encodeToString(JsonElement.serializer(), args)
                    } ?: "{}"
                    toolCalls.add(ProtocolToolCall(
                        id = "vcall_${System.currentTimeMillis()}_${(10000..99999).random()}",
                        name = fc.stringField("name"),
                        arguments = argsStr
                    ))
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

        if (toolCalls.isNotEmpty()) {
            for ((index, tc) in toolCalls.withIndex()) {
                send(StreamChunk.ToolCallDelta(tc.id, tc.name, tc.arguments, index))
            }
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
        val toolCallsList = mutableListOf<ProtocolToolCall>()

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
                    val fc = part["functionCall"]!!.jsonObject
                    val argsStr = fc["args"]?.let { args ->
                        json.encodeToString(JsonElement.serializer(), args)
                    } ?: "{}"
                    toolCallsList.add(ProtocolToolCall(
                        id = "vcall_${System.currentTimeMillis()}_${(10000..99999).random()}",
                        name = fc.stringField("name"),
                        arguments = argsStr
                    ))
                }
            }
        }

        if (textContent.isEmpty() && reasoningContent.isEmpty() && toolCallsList.isEmpty()) {
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
            toolCalls = toolCallsList.ifEmpty { null },
            usage = usage,
            citations = citations?.ifEmpty { null }
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

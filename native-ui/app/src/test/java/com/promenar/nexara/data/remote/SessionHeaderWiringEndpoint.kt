package com.promenar.nexara.data.remote

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.SessionHeaderPolicy
import com.promenar.nexara.data.remote.protocol.GenericOpenAICompatProtocol
import com.promenar.nexara.data.remote.protocol.ProtocolType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

/** 生产 Router 与协议共用的内存端点，禁止访问真实网络。 */
internal class SessionHeaderWiringEndpoint(var enabled: Boolean = true) : AutoCloseable {
    val requests = mutableListOf<HttpRequestData>()
    var reply: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to textStream("摘要标题") }
    val http = HttpClient(MockEngine { request ->
        requests += request
        val (status, body) = reply(request)
        respond(body, status, headersOf(HttpHeaders.ContentType,
            if (body.startsWith("data:")) "text/event-stream" else "application/json"))
    })
    val protocol = GenericOpenAICompatProtocol(BASE, "synthetic-key", "model", httpClient = http)
    val router = DefaultProviderRequestRouter(
        modelResolver = { ModelInfo("Model", MODEL, "", true, providerId = "gateway", remoteModelId = "model") },
        providerResolver = { ProviderListItem(id = "gateway", protocolType = ProtocolType.Generic_OpenAI_Compat) },
        configResolver = { ProviderConfig(baseUrl = BASE, apiKey = "synthetic-key", sessionHeadersEnabled = enabled) },
        sessionIdentifier = { SessionHeaderPolicy.identifier("wiring-test-salt", it) },
        clientFactory = { config -> UnifiedLlmClient({ config }, protocolFactory = { protocol },
            sessionIdentifier = { SessionHeaderPolicy.identifier("wiring-test-salt", it) }) },
    )

    fun identifier(sessionId: String) = SessionHeaderPolicy.identifier("wiring-test-salt", sessionId)
    override fun close() = http.close()

    companion object {
        const val BASE = "https://wiring.invalid/v1"
        const val MODEL = "gateway::model"
        fun body(request: HttpRequestData) = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        fun textStream(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        fun toolStream() = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        fun sync(content: String) = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}}]}"
    }
}

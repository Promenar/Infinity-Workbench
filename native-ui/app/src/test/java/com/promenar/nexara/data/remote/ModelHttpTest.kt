package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.ImageGenClient
import com.promenar.nexara.data.rag.RerankClient
import com.promenar.nexara.data.remote.protocol.ProtocolFactory
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbe
import com.promenar.nexara.data.tailnet.DisabledTailnetRouting
import com.promenar.nexara.data.tailnet.TailnetConnectGate
import com.promenar.nexara.data.tailnet.TailnetConnector
import com.promenar.nexara.data.tailnet.TailnetPhase
import com.promenar.nexara.data.tailnet.TailnetRouting
import com.promenar.nexara.data.tailnet.TailnetState
import com.promenar.nexara.data.tailnet.TailnetUnavailableException
import com.promenar.nexara.data.tailnet.TailnetUnavailableReason
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ModelHttpTest {
    private class FakeConnector(phase: TailnetPhase, private val starts: Boolean) : TailnetConnector {
        override val state: StateFlow<TailnetState> = MutableStateFlow(TailnetState(phase))
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net")
        override suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean = starts
    }

    private val tailnetRouting = object : TailnetRouting by DisabledTailnetRouting {
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net")
    }

    /** 守卫之后接一个记录到达次数的终端拦截器，代替真实网络。 */
    private fun client(gate: TailnetConnectGate, reached: AtomicInteger) = OkHttpClient.Builder()
        .addInterceptor(TailnetRouteGuard({ tailnetRouting }, { gate }))
        .addInterceptor(Interceptor { chain ->
            reached.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        })
        .build()

    @Test
    fun `内置 Tailscale 未接入时 tailnet 请求直接失败，不发出也不回退直连`() {
        val reached = AtomicInteger()
        val gate = TailnetConnectGate(FakeConnector(TailnetPhase.FAILED, starts = false))
        val call = client(gate, reached).newCall(
            Request.Builder().url("https://gpu.tail1.ts.net/v1/chat/completions").header("Authorization", "Bearer k").build(),
        )
        val error = assertThrows(TailnetUnavailableException::class.java) { call.execute() }
        assertThat(error.reason).isEqualTo(TailnetUnavailableReason.FAILED)
        assertThat(error.message).doesNotContain("Bearer")
        assertThat(reached.get()).isEqualTo(0)
    }

    @Test
    fun `已接入或非 tailnet 目标照常发出`() {
        val reached = AtomicInteger()
        val offline = client(TailnetConnectGate(FakeConnector(TailnetPhase.FAILED, starts = false)), reached)
        offline.newCall(Request.Builder().url("https://api.deepseek.com/v1/models").build()).execute().close()
        val online = client(TailnetConnectGate(FakeConnector(TailnetPhase.RUNNING, starts = true)), reached)
        online.newCall(Request.Builder().url("https://gpu.tail1.ts.net/v1/models").build()).execute().close()
        assertThat(reached.get()).isEqualTo(2)
    }

    private fun Any.declaredHttpClient(): HttpClient {
        val field = generateSequence<Class<*>>(javaClass) { it.superclass }
            .firstNotNullOf { type -> type.declaredFields.firstOrNull { it.name == "httpClient" } }
        field.isAccessible = true
        return field.get(this) as HttpClient
    }

    @Test
    fun `所有云端协议、连接测试、向量化、重排与生图默认共用模型引擎`() {
        val protocols = listOf(
            ProtocolType.OpenAI_ChatCompletions,
            ProtocolType.OpenAI_Responses,
            ProtocolType.Anthropic_Messages,
            ProtocolType.Google_Gemini,
            ProtocolType.Google_VertexAI,
            ProtocolType.Generic_OpenAI_Compat,
            ProtocolType.DeepSeek,
        ).map { type -> ProtocolFactory.create(type, baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k", model = "m") }
        val clients = protocols + listOf(
            ProviderConnectionProbe(),
            EmbeddingClient(baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k"),
            RerankClient(baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k", modelId = "r"),
            ImageGenClient(baseUrl = "https://gpu.tail1.ts.net/v1", apiKey = "k", model = "i"),
        )
        clients.forEach { owner ->
            assertThat(owner.declaredHttpClient().engine).isSameInstanceAs(ModelHttp.engine)
        }
    }
}

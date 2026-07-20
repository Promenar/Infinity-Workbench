package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.OpenAIProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.ui.common.status.NoticeSeverity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderModelReleaseBlockersTest {

    private fun source(relative: String): String = String(
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/$relative")),
        Charsets.UTF_8,
    )

    @Test
    fun `provider form uses unframed sections and one filled save action`() {
        val form = source("ui/settings/ProviderFormScreen.kt")
        val cloudSection = form.substringAfter("private fun CloudProviderSection")
            .substringBefore("private fun ProviderFormActionsSection")
        val actionsSection = form.substringAfter("private fun ProviderFormActionsSection")
            .substringBefore("private fun LocalProviderSection")
        val localSection = form.substringAfter("private fun LocalProviderSection")
            .substringBefore("private fun ProviderFormErrorText")

        assertThat(cloudSection).doesNotContain("Surface(")
        assertThat(localSection).doesNotContain("Surface(")
        assertThat(actionsSection).contains("Row(")
        assertThat(actionsSection).contains("OutlinedButton(")
        assertThat(actionsSection).contains("Button(")
        assertThat(actionsSection).doesNotContain("Column(")
    }

    @Test
    fun `shared settings fields and protocol choices use standard Material 3 controls`() {
        val input = source("ui/common/SettingsInput.kt")
        val selector = source("ui/common/ProtocolSelector.kt")

        assertThat(input).contains("OutlinedTextField(")
        assertThat(input).contains("MaterialTheme.typography")
        assertThat(input).doesNotContain("NexaraGlassCard")
        assertThat(input).doesNotContain("NexaraColors.")
        assertThat(input).doesNotContain("BasicTextField(")

        assertThat(selector).contains("ListItem(")
        assertThat(selector).contains("RadioButton(")
        assertThat(selector).contains("selectable(")
        assertThat(selector).contains("Role.RadioButton")
        assertThat(selector).doesNotContain("NexaraColors.")
        assertThat(selector).doesNotContain(".border(")
    }

    @Test
    fun `built in fallback can never be presented as green success`() {
        val notice = ModelSyncNotice.synced(2, 1).withFallbackWarning(true)

        assertThat(notice.severity).isEqualTo(NoticeSeverity.Warning)
        assertThat(notice.technical).isEqualTo("built_in_model_fallback_used")
    }

    @Test
    fun `probe uses router and sends minimal request with remote model id`() = runTest {
        var captured: PromptRequest? = null
        val protocol = RecordingProtocol { request ->
            captured = request
            flowOf(StreamChunk.TextDelta("OK"), StreamChunk.Done)
        }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        advanceUntilIdle()

        val success = coordinator.states.value.getValue("provider-a::remote-a") as ModelTestState.Success
        assertThat(success.latencyMs).isAtLeast(0)
        assertThat(captured!!.model).isEqualTo("remote-a")
        assertThat(captured!!.maxTokens).isEqualTo(1)
        assertThat(captured!!.messages).containsExactly(ProtocolMessage("user", "Reply OK."))
    }

    @Test
    fun `same model test is idempotent while different model state remains isolated`() = runTest {
        val requests = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val protocol = RecordingProtocol {
            flow {
                requests.incrementAndGet()
                release.await()
                emit(StreamChunk.TextDelta("OK"))
                emit(StreamChunk.Done)
            }
        }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        coordinator.test("provider-a::remote-a")
        runCurrent()

        assertThat(requests.get()).isEqualTo(1)
        assertThat(coordinator.states.value["provider-a::remote-a"]).isEqualTo(ModelTestState.Testing)
        assertThat(coordinator.states.value["provider-b::remote-b"]).isNull()

        release.complete(Unit)
        advanceUntilIdle()
        assertThat(coordinator.states.value["provider-a::remote-a"]).isInstanceOf(ModelTestState.Success::class.java)
    }

    @Test
    fun `concurrent same model test stays idempotent before lazy job starts`() = runTest {
        val requests = AtomicInteger()
        val enteredStartWindow = CountDownLatch(1)
        val releaseStartWindow = CountDownLatch(1)
        val hookCalls = AtomicInteger()
        val protocol = RecordingProtocol {
            flowOf(StreamChunk.TextDelta("OK"), StreamChunk.Done).also {
                requests.incrementAndGet()
            }
        }
        val coordinator = coordinator(
            scope = this,
            protocol = protocol,
            beforeJobStartForTest = {
                if (hookCalls.incrementAndGet() == 1) {
                    enteredStartWindow.countDown()
                    check(releaseStartWindow.await(5, TimeUnit.SECONDS))
                }
            },
        )

        val firstCall = async(Dispatchers.Default) {
            coordinator.test("provider-a::remote-a")
        }
        assertThat(withContext(Dispatchers.IO) {
            enteredStartWindow.await(5, TimeUnit.SECONDS)
        }).isTrue()

        coordinator.test("provider-a::remote-a")
        releaseStartWindow.countDown()
        firstCall.await()
        advanceUntilIdle()

        assertThat(requests.get()).isEqualTo(1)
    }

    @Test
    fun `401 and 429 stay structured and never expose provider body`() = runTest {
        suspend fun probe(status: HttpStatusCode, body: String, retryAfter: String? = null): ModelTestState.Error {
            val protocol = OpenAIProtocol(
                baseUrl = "https://provider.invalid/v1",
                apiKey = "test-key",
                model = "remote-a",
                httpClient = HttpClient(MockEngine {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.RetryAfter, listOfNotNull(retryAfter)),
                    )
                }),
            )
            val classifiedChunks = protocol.sendPrompt(
                PromptRequest(
                    messages = listOf(ProtocolMessage("user", "Reply OK.")),
                    model = "remote-a",
                    maxTokens = 1,
                ),
            ).toList()
            val coordinator = coordinator(this, RecordingProtocol { flowOf(*classifiedChunks.toTypedArray()) })
            coordinator.test("provider-a::remote-a")
            advanceUntilIdle()
            return coordinator.states.value.getValue("provider-a::remote-a") as ModelTestState.Error
        }

        val auth = probe(HttpStatusCode.Unauthorized, "private upstream credential details")
        val rateLimit = probe(HttpStatusCode.TooManyRequests, "private quota details", "17")

        assertThat(auth.code).isEqualTo(GenerationFailureCode.AUTH)
        assertThat(rateLimit.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(auth.toString()).doesNotContain("private")
        assertThat(rateLimit.toString()).doesNotContain("private")
    }

    @Test
    fun `cancellation propagates and does not become an error state`() = runTest {
        val started = CompletableDeferred<Unit>()
        val protocol = RecordingProtocol {
            flow {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        started.await()
        coordinator.cancel("provider-a::remote-a")
        advanceUntilIdle()

        assertThat(coordinator.states.value["provider-a::remote-a"]).isEqualTo(ModelTestState.Idle)
    }

    @Test
    fun `timeout becomes a typed timeout error`() = runTest {
        val protocol = RecordingProtocol { flow { CompletableDeferred<Unit>().await() } }
        val coordinator = coordinator(this, protocol, timeoutMillis = 25)

        coordinator.test("provider-a::remote-a")
        advanceUntilIdle()

        val error = coordinator.states.value.getValue("provider-a::remote-a") as ModelTestState.Error
        assertThat(error.code).isEqualTo(GenerationFailureCode.TIMEOUT)
    }

    @Test
    fun `empty flow becomes a structured error instead of success`() = runTest {
        val protocol = RecordingProtocol { emptyFlow() }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        advanceUntilIdle()

        val state = coordinator.states.value.getValue("provider-a::remote-a")
        assertThat(state).isInstanceOf(ModelTestState.Error::class.java)
        assertThat((state as ModelTestState.Error).code).isEqualTo(GenerationFailureCode.UNKNOWN)
    }

    @Test
    fun `done only flow becomes a structured error instead of success`() = runTest {
        val protocol = RecordingProtocol { flowOf(StreamChunk.Done) }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        advanceUntilIdle()

        val state = coordinator.states.value.getValue("provider-a::remote-a")
        assertThat(state).isInstanceOf(ModelTestState.Error::class.java)
    }

    @Test
    fun `error chunk followed by done stays a structured error`() = runTest {
        val protocol = RecordingProtocol {
            flowOf(
                StreamChunk.Error(code = GenerationFailureCode.RATE_LIMIT, retryAfterSeconds = 17),
                StreamChunk.Done,
            )
        }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        advanceUntilIdle()

        val state = coordinator.states.value.getValue("provider-a::remote-a") as ModelTestState.Error
        assertThat(state.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(state.retryAfterSeconds).isEqualTo(17)
    }

    @Test
    fun `payload without done becomes a structured error`() = runTest {
        val protocol = RecordingProtocol { flowOf(StreamChunk.TextDelta("OK")) }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        advanceUntilIdle()

        val state = coordinator.states.value.getValue("provider-a::remote-a")
        assertThat(state).isInstanceOf(ModelTestState.Error::class.java)
    }

    @Test
    fun `cancelled generation does not clobber a fresh retest of the same model`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val protocol = RecordingProtocol {
            flow {
                emit(StreamChunk.TextDelta("OK"))
                hold.await()
                emit(StreamChunk.Done)
            }
        }
        val coordinator = coordinator(this, protocol)

        coordinator.test("provider-a::remote-a")
        runCurrent()
        coordinator.cancel("provider-a::remote-a")
        coordinator.test("provider-a::remote-a")
        runCurrent()

        assertThat(coordinator.states.value["provider-a::remote-a"]).isEqualTo(ModelTestState.Testing)

        hold.complete(Unit)
        advanceUntilIdle()

        assertThat(coordinator.states.value["provider-a::remote-a"]).isInstanceOf(ModelTestState.Success::class.java)
    }

    private fun coordinator(
        scope: CoroutineScope,
        protocol: LlmProtocol,
        timeoutMillis: Long = 15_000,
        beforeJobStartForTest: (String) -> Unit = {},
    ): ProviderModelTestCoordinator {
        val resolved = ResolvedProviderModel(
            modelId = "provider-a::remote-a",
            remoteModelId = "remote-a",
            providerId = "provider-a",
            providerName = "Provider A",
            config = UnifiedProviderConfig(
                protocolType = ProtocolType.OpenAI_ChatCompletions,
                baseUrl = "https://provider.invalid/v1",
                apiKey = "test-key",
                defaultModel = "remote-a",
            ),
        )
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                resolved.copy(
                    modelId = modelId,
                    remoteModelId = modelId.substringAfter("::"),
                    providerId = modelId.substringBefore("::"),
                ),
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = UnifiedLlmClient(
                providerConfigResolver = { resolved.config.copy(defaultModel = resolved.remoteModelId) },
                protocolFactory = { protocol },
            )
        }
        return ProviderModelTestCoordinator(
            router = router,
            scope = scope,
            timeoutMillis = timeoutMillis,
            beforeJobStartForTest = beforeJobStartForTest,
        )
    }

    private class RecordingProtocol(
        private val stream: suspend (PromptRequest) -> Flow<StreamChunk>,
    ) : LlmProtocol {
        override val protocolType: ProtocolType = ProtocolType.OpenAI_ChatCompletions
        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = stream(request)
        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse = error("unused")
        override fun cancel() = Unit
    }
}

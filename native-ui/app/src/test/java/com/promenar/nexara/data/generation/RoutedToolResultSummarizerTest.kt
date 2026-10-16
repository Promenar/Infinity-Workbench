package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import com.promenar.nexara.data.remote.protocol.ProtocolToolChoice
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.ProtocolUsage
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.generation.GenerationFailureCode
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RoutedToolResultSummarizerTest {
    @Test
    fun `失败摘要仍通过用量回调保留提供商实报值`() = runTest {
        val protocol = RecordingProtocol(flow {
            emit(StreamChunk.Usage(ProtocolUsage(4000, 1000, 5000)))
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        })
        var reported: com.promenar.nexara.data.model.TokenUsage? = null
        val result = summarizer(protocol).summarizeWithUsage("data", "summary::remote", "current::remote") { reported = it }
        assertThat(result).isNull()
        assertThat(reported?.total).isEqualTo(5000)
        assertThat(reported?.estimated).isFalse()
    }

    @Test
    fun `摘要模型按所属provider路由并向远端模型ID发出禁用工具搜索的请求`() = runTest {
        val summaryProtocol = RecordingProtocol(successfulStream("摘要", "正文"))
        val currentProtocol = RecordingProtocol(successfulStream("错误的当前模型"))
        val defaultProtocol = RecordingProtocol(successfulStream("错误的默认模型"))
        val router = RecordingRouter(
            mapOf(
                "summary-provider::summary-remote" to endpoint("summary-provider", "summary-remote", summaryProtocol),
                "current-provider::current-remote" to endpoint("current-provider", "current-remote", currentProtocol),
                "default-provider::default-remote" to endpoint("default-provider", "default-remote", defaultProtocol),
            ),
        )
        val localModels = mutableListOf<String>()
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { model ->
            localModels += model
            LlmProvider(defaultProtocol)
        }
        val input = "工具结果正文：成功读取 7 个文件。"

        val result = summarizer.summarize(input, "summary-provider::summary-remote", "current-provider::current-remote")

        assertThat(result).isEqualTo("摘要正文")
        assertThat(router.resolvedIds).containsExactly("summary-provider::summary-remote")
        assertThat(summaryProtocol.requests).hasSize(1)
        val request = summaryProtocol.requests.single()
        assertThat(request.model).isEqualTo("summary-remote")
        assertThat(request.maxTokens).isEqualTo(256)
        assertThat(request.stream).isTrue()
        assertThat(request.toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(request.tools.orEmpty()).isEmpty()
        assertThat(request.webSearch).isFalse()
        assertThat(request.enableGeminiSearch).isFalse()
        assertThat(request.messages.single { it.role == "user" }.content).isEqualTo(input)
        assertThat(request.messages.map { it.role }).containsExactly("system", "user").inOrder()
        assertThat(summaryProtocol.requestDispatchers).containsExactly(Dispatchers.IO)
        assertThat(currentProtocol.requests).isEmpty()
        assertThat(defaultProtocol.requests).isEmpty()
        assertThat(localModels).isEmpty()
    }

    @Test
    fun `未指定或空白摘要模型时精确使用当前稳定模型ID`() = runTest {
        listOf<String?>(null, "", "  ").forEach { summaryModel ->
            val protocol = RecordingProtocol(successfulStream("当前模型摘要"))
            val router = RecordingRouter(mapOf("current::remote" to endpoint("current", "remote", protocol)))
            val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用本地提供商") }

            assertThat(summarizer.summarize("待摘要数据", summaryModel, "current::remote"))
                .isEqualTo("当前模型摘要")
            assertThat(router.resolvedIds).containsExactly("current::remote")
            assertThat(protocol.requests.single().model).isEqualTo("remote")
        }
    }

    @Test
    fun `摘要模型路由失败时返回空值且不向当前或默认provider发送数据`() = runTest {
        val currentProtocol = RecordingProtocol(successfulStream("不应发送"))
        val router = RecordingRouter(mapOf("current::remote" to endpoint("current", "remote", currentProtocol)))
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认本地提供商") }

        assertThat(summarizer.summarize("私有工具数据", "disabled::summary", "current::remote")).isNull()
        assertThat(router.resolvedIds).containsExactly("disabled::summary")
        assertThat(currentProtocol.requests).isEmpty()
    }

    @Test
    fun `路由解析抛出异常时失败关闭且不创建客户端`() = runTest {
        var clientCreated = false
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = throw IOException("route unavailable")
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient {
                clientCreated = true
                error("路由失败不应创建客户端")
            }
        }

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认提供商") }
            .summarize("数据", "summary::remote", "current::remote")

        assertThat(result).isNull()
        assertThat(clientCreated).isFalse()
    }

    @Test
    fun `provider配置为空时返回空值且不调用协议工厂`() = runTest {
        var protocolCreated = false
        val resolved = endpoint("summary", "remote", RecordingProtocol(successfulStream("不应发送"))).resolved
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String) = ProviderResolution.Success(resolved)
            override fun createClient(resolved: ResolvedProviderModel) = UnifiedLlmClient(
                providerConfigResolver = { null },
                protocolFactory = {
                    protocolCreated = true
                    error("缺少配置不应创建协议")
                },
            )
        }

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认提供商") }
            .summarize("数据", "summary::remote", "current::remote")

        assertThat(result).isNull()
        assertThat(protocolCreated).isFalse()
    }

    @Test
    fun `缺失远端模型ID的路由不发送请求`() = runTest {
        val protocol = RecordingProtocol(successfulStream("不应发送"))
        val router = RecordingRouter(mapOf("summary::" to endpoint("summary", "", protocol)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用本地提供商") }
            .summarize("数据", "summary::", "current::remote")

        assertThat(result).isNull()
        assertThat(protocol.requests).isEmpty()
    }

    @Test
    fun `EOF缺少显式完成事件时丢弃部分摘要`() = runTest {
        val protocol = RecordingProtocol(flowOf(StreamChunk.TextDelta("不完整摘要")))

        assertThat(summarizer(protocol).summarize("数据", "summary::remote", "current::remote")).isNull()
    }

    @Test
    fun `显式错误后的文本不能冒充有效摘要`() = runTest {
        val protocol = RecordingProtocol(flowOf(
            StreamChunk.TextDelta("部分摘要"),
            StreamChunk.Error(GenerationFailureCode.SERVER),
            StreamChunk.Completed(CompletionReason.END_TURN),
        ))

        assertThat(summarizer(protocol).summarize("数据", "summary::remote", "current::remote")).isNull()
    }

    @Test
    fun `意外工具调用与工具结束原因均拒绝采用`() = runTest {
        val protocol = RecordingProtocol(flowOf(
            StreamChunk.TextDelta("尝试执行工具"),
            StreamChunk.ToolCallDelta("call", "delete_file", "{}", 0),
            StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
        ))

        assertThat(summarizer(protocol).summarize("数据", "summary::remote", "current::remote")).isNull()
    }

    @Test
    fun `只有结束事件或空白输出时不返回空摘要`() = runTest {
        val protocol = RecordingProtocol(successfulStream(" \n "))

        assertThat(summarizer(protocol).summarize("数据", "summary::remote", "current::remote")).isNull()
    }

    @Test
    fun `流传输取消必须透传取消异常`() = runTest {
        val cancelled = CancellationException("summary cancelled")
        val protocol = RecordingProtocol(flow { throw cancelled })

        val failure = runCatching {
            summarizer(protocol).summarize("数据", "summary::remote", "current::remote")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `路由取消必须透传且不降级到默认provider`() = runTest {
        val cancelled = CancellationException("route cancelled")
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = throw cancelled
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("不应创建客户端")
        }

        val failure = runCatching {
            RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用默认提供商") }
                .summarize("数据", "summary::remote", "current::remote")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `本地摘要使用路由远端ID同步推理且禁用工具搜索`() = runTest {
        val localProtocol = RecordingProtocol(successfulStream("不应走流式"), PromptResponse("本地摘要"))
        val router = RecordingRouter(mapOf("local::gguf-model" to endpoint("local", "gguf-model", localProtocol, local = true)))
        val localModels = mutableListOf<String>()
        val summarizer = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { model ->
            localModels += model
            LlmProvider(localProtocol)
        }

        assertThat(summarizer.summarize("数据", "local::gguf-model", "current::remote")).isEqualTo("本地摘要")
        assertThat(localModels).containsExactly("gguf-model")
        assertThat(router.createdClients).isEmpty()
        assertThat(localProtocol.requests).isEmpty()
        val request = localProtocol.syncRequests.single()
        assertThat(request.model).isEqualTo("gguf-model")
        assertThat(request.maxTokens).isEqualTo(256)
        assertThat(request.stream).isFalse()
        assertThat(request.toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(request.tools.orEmpty()).isEmpty()
        assertThat(request.webSearch).isFalse()
        assertThat(request.enableGeminiSearch).isFalse()
        assertThat(localProtocol.requestDispatchers).containsExactly(Dispatchers.IO)
    }

    @Test
    fun `本地响应包含工具调用时拒绝采用`() = runTest {
        val protocol = RecordingProtocol(
            successfulStream("不应走流式"),
            PromptResponse("不安全摘要", toolCalls = listOf(ProtocolToolCall("call", "write_file", "{}"))),
        )
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
            .summarize("数据", "local::model", "current::remote")

        assertThat(result).isNull()
    }

    @Test
    fun `本地推理取消必须透传`() = runTest {
        val protocol = RecordingProtocol(
            successfulStream("不应走流式"),
            syncFailure = CancellationException("local cancelled"),
        )
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val failure = runCatching {
            RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
                .summarize("数据", "local::model", "current::remote")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `多条累计Usage按单请求最大计量而非逐事件相加`() = runTest {
        val cases = listOf(
            listOf(ProtocolUsage(10, 1, 11), ProtocolUsage(10, 5, 9), ProtocolUsage(8, 2, 10)) to 15,
            listOf(ProtocolUsage(10, 1, 40), ProtocolUsage(10, 5, 15)) to 40,
        )
        cases.forEach { (usages, expectedTotal) ->
            val protocol = RecordingProtocol(flow {
                emit(StreamChunk.TextDelta("完整摘要"))
                usages.forEach { emit(StreamChunk.Usage(it)) }
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            })

            val result = summarizer(protocol).summarizeWithUsage("工具数据", "summary::remote", "current::remote")

            assertThat(result?.content).isEqualTo("完整摘要")
            assertThat(result?.usage?.input).isEqualTo(10)
            assertThat(result?.usage?.output).isEqualTo(5)
            assertThat(result?.usage?.total).isEqualTo(expectedTotal)
            assertThat(result?.usage?.estimated).isFalse()
            assertThat(protocol.requests).hasSize(1)
        }
    }

    @Test
    fun `无Usage时计入完整提示和输出估算且不额外发送请求`() = runTest {
        val protocol = RecordingProtocol(successfulStream("摘要"))

        val result = summarizer(protocol).summarizeWithUsage("abcdefgh", "summary::remote", "current::remote")

        assertThat(result?.content).isEqualTo("摘要")
        val usage = checkNotNull(result).usage
        assertThat(usage.estimated).isTrue()
        assertThat(usage.input).isGreaterThan(8)
        assertThat(usage.output).isEqualTo(2)
        assertThat(usage.total).isEqualTo(usage.input + 2)
        assertThat(protocol.requests).hasSize(1)
    }

    @Test
    fun `本地同步响应采用实际Usage并补足不一致总数`() = runTest {
        val protocol = RecordingProtocol(
            successfulStream("不应走流式"),
            PromptResponse("本地摘要", usage = ProtocolUsage(11, 7, 15)),
        )
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
            .summarizeWithUsage("工具数据", "local::model", "current::remote")

        val usage = checkNotNull(result).usage
        assertThat(result.content).isEqualTo("本地摘要")
        assertThat(usage.input).isEqualTo(11)
        assertThat(usage.output).isEqualTo(7)
        assertThat(usage.total).isEqualTo(18)
        assertThat(usage.estimated).isFalse()
        assertThat(protocol.syncRequests).hasSize(1)
        assertThat(protocol.requests).isEmpty()
    }

    @Test
    fun `本地缺少Usage时估算提示和输出`() = runTest {
        val protocol = RecordingProtocol(successfulStream("不应走流式"), PromptResponse("本地摘要"))
        val router = RecordingRouter(mapOf("local::model" to endpoint("local", "model", protocol, local = true)))

        val result = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { LlmProvider(protocol) }
            .summarizeWithUsage("abcdefgh", "local::model", "current::remote")

        val usage = checkNotNull(result).usage
        assertThat(usage.estimated).isTrue()
        assertThat(usage.input).isGreaterThan(8)
        assertThat(usage.output).isEqualTo(4)
        assertThat(usage.total).isEqualTo(usage.input + 4)
        assertThat(protocol.syncRequests).hasSize(1)
    }

    @Test
    fun `用量总和超过Int上限时饱和而不溢出为负数`() = runTest {
        val protocol = RecordingProtocol(flowOf(
            StreamChunk.TextDelta("摘要"),
            StreamChunk.Usage(ProtocolUsage(1_500_000_000, 1_000_000_000, 0)),
            StreamChunk.Completed(CompletionReason.END_TURN),
        ))

        val result = summarizer(protocol).summarizeWithUsage("工具数据", "summary::remote", "current::remote")

        assertThat(result?.usage?.input).isEqualTo(1_500_000_000)
        assertThat(result?.usage?.output).isEqualTo(1_000_000_000)
        assertThat(result?.usage?.total).isEqualTo(Int.MAX_VALUE)
        assertThat(result?.usage?.estimated).isFalse()
    }

    private fun summarizer(protocol: RecordingProtocol): RoutedToolResultSummarizer {
        val router = RecordingRouter(mapOf("summary::remote" to endpoint("summary", "remote", protocol)))
        return RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { error("不应使用本地提供商") }
    }

    private fun endpoint(
        providerId: String,
        remoteModelId: String,
        protocol: RecordingProtocol,
        local: Boolean = false,
    ): Endpoint = Endpoint(
        ResolvedProviderModel(
            modelId = "$providerId::$remoteModelId",
            remoteModelId = remoteModelId,
            providerId = providerId,
            providerName = providerId,
            config = UnifiedProviderConfig(
                protocolType = if (local) ProtocolType.Local else ProtocolType.OpenAI_ChatCompletions,
                baseUrl = if (local) "" else "https://$providerId.invalid",
                apiKey = "",
                defaultModel = remoteModelId,
            ),
        ),
        protocol,
    )

    private fun successfulStream(vararg chunks: String): Flow<StreamChunk> = flow {
        chunks.forEach { emit(StreamChunk.TextDelta(it)) }
        emit(StreamChunk.Completed(CompletionReason.END_TURN))
    }

    private data class Endpoint(val resolved: ResolvedProviderModel, val protocol: RecordingProtocol)

    private class RecordingRouter(private val endpoints: Map<String, Endpoint>) : ProviderRequestRouter {
        val resolvedIds = mutableListOf<String>()
        val createdClients = mutableListOf<String>()

        override fun resolve(modelId: String): ProviderResolution {
            resolvedIds += modelId
            return endpoints[modelId]?.let { ProviderResolution.Success(it.resolved) }
                ?: ProviderResolution.Failure(ProviderResolutionError.MODEL_NOT_FOUND, modelId)
        }

        override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient {
            createdClients += resolved.providerId
            return UnifiedLlmClient(
                providerConfigResolver = { resolved.config },
                protocolFactory = { endpoints.getValue(resolved.modelId).protocol },
            )
        }
    }

    private class RecordingProtocol(
        private val output: Flow<StreamChunk>,
        private val syncResponse: PromptResponse = PromptResponse(""),
        private val syncFailure: Exception? = null,
    ) : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        val requests = mutableListOf<PromptRequest>()
        val syncRequests = mutableListOf<PromptRequest>()
        val requestDispatchers = mutableListOf<ContinuationInterceptor?>()

        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
            requests += request
            requestDispatchers += currentCoroutineContext()[ContinuationInterceptor]
            return output
        }

        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
            syncRequests += request
            requestDispatchers += currentCoroutineContext()[ContinuationInterceptor]
            syncFailure?.let { throw it }
            return syncResponse
        }

        override fun cancel() = Unit
    }
}

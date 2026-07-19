package com.promenar.nexara.data.model.catalog

import android.app.Application
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BundledModelCatalogTest {
    private lateinit var catalog: BundledModelCatalog
    private lateinit var resolver: ModelMetadataResolver

    @Before
    fun setUp() {
        catalog = BundledModelCatalog.load(applicationWithBundledCatalog())
        resolver = ModelMetadataResolver(catalog.records)
    }

    @Test
    fun `规范化目录所有 canonical ID 唯一且精确可达`() {
        val records = catalog.records

        assertThat(records).hasSize(258)
        assertThat(records.map { it.canonicalModelId }.distinct()).hasSize(records.size)
        records.forEach { record ->
            assertThat(catalog.findExact(record.canonicalModelId)).isEqualTo(record)
        }
    }

    @Test
    fun `所有 reasoning true 记录解析为推理能力支持`() {
        val reasoningRecords = catalog.records.filter {
            it.capabilities[ModelCapability.REASONING] == SupportState.SUPPORTED
        }

        assertThat(reasoningRecords).isNotEmpty()
        reasoningRecords.forEach { record ->
            assertThat(resolver.resolve(record.canonicalModelId).capabilities[ModelCapability.REASONING])
                .isEqualTo(SupportState.SUPPORTED)
        }
    }

    @Test
    fun `唯一短别名精确可达`() {
        val uniqueShortAliases = catalog.records
            .groupBy { it.canonicalModelId.substringAfter('/') }
            .filterValues { it.size == 1 }

        assertThat(uniqueShortAliases).isNotEmpty()
        uniqueShortAliases.forEach { (shortAlias, records) ->
            assertThat(catalog.findExact(shortAlias)).isEqualTo(records.single())
        }
    }

    @Test
    fun `同优先级短别名歧义不按声明顺序解析`() {
        val first = record(canonicalModelId = "vendor-a/shared-model", aliases = setOf("shared-model"))
        val second = record(canonicalModelId = "vendor-b/shared-model", aliases = setOf("shared-model"))
        val ambiguousCatalog = BundledModelCatalog.fromRecords(listOf(first, second))

        assertThat(ambiguousCatalog.findExact("shared-model")).isNull()
        assertThat(ModelMetadataResolver(ambiguousCatalog.records).resolve("shared-model").diagnostics)
            .containsExactly("ambiguous_exact_match")
    }

    @Test
    fun `Nexara 修正逐字段回落到公共目录`() {
        val resolved = ModelCatalogRuntime.resolverFor(catalog).resolve("deepseek-v4-flash")

        assertThat(resolved.displayName).isEqualTo("DeepSeek V4 Flash")
        assertThat(resolved.familyName).isEqualTo("DeepSeek V4")
        assertThat(resolved.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(resolved.sourceByField["displayName"])
            .isEqualTo(MetadataSource.NEXARA_OVERRIDE)
        assertThat(resolved.sourceByField["familyName"])
            .isEqualTo(MetadataSource.NEXARA_OVERRIDE)
        assertThat(resolved.sourceByField["capabilities.REASONING"])
            .isEqualTo(MetadataSource.NEXARA_OVERRIDE)
        assertThat(resolved.contextTokens).isEqualTo(1_000_000)
        assertThat(resolved.outputTokens).isEqualTo(384_000)
        assertThat(resolved.capabilities[ModelCapability.STRUCTURED_OUTPUT])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(resolved.capabilities[ModelCapability.TOOL_CALLING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(resolved.sourceByField["contextTokens"])
            .isEqualTo(MetadataSource.MODELS_DEV)
        assertThat(resolved.sourceByField["outputTokens"])
            .isEqualTo(MetadataSource.MODELS_DEV)
        assertThat(resolved.sourceByField["capabilities.STRUCTURED_OUTPUT"])
            .isEqualTo(MetadataSource.MODELS_DEV)
        assertThat(resolved.sourceByField["capabilities.TOOL_CALLING"])
            .isEqualTo(MetadataSource.MODELS_DEV)
    }

    @Test
    fun `canonical ID 原样保留而规范化只用于索引 key`() {
        val record = catalog.findExact("minimax-m3")

        assertThat(record?.canonicalModelId).isEqualTo("minimax/MiniMax-M3")
        assertThat(catalog.findExact("minimax/MiniMax-M3")?.canonicalModelId)
            .isEqualTo("minimax/MiniMax-M3")
    }

    @Test
    fun `三条专用 snapshot ID 使用显式 workload`() {
        val runtimeResolver = ModelCatalogRuntime.resolverFor(catalog)

        val embedding = runtimeResolver.resolve("google/gemini-embedding-001")
        assertThat(embedding.workload).isEqualTo(ModelWorkload.EMBEDDING)
        assertThat(embedding.contextTokens).isEqualTo(2_048)
        assertThat(embedding.outputTokens).isEqualTo(1)
        assertThat(embedding.knowledgeCutoff).isEqualTo("2025-05")
        assertThat(embedding.sourceByField["workload"]).isEqualTo(MetadataSource.NEXARA_OVERRIDE)

        val multimodalEmbedding = runtimeResolver.resolve("nvidia/llama-nemotron-embed-vl-1b-v2")
        assertThat(multimodalEmbedding.workload).isEqualTo(ModelWorkload.EMBEDDING)
        assertThat(multimodalEmbedding.contextTokens).isEqualTo(32_768)
        assertThat(multimodalEmbedding.outputTokens).isEqualTo(2_048)
        assertThat(multimodalEmbedding.capabilities[ModelCapability.VISION_INPUT])
            .isEqualTo(SupportState.SUPPORTED)

        val rerank = runtimeResolver.resolve("nvidia/llama-nemotron-rerank-vl-1b-v2")
        assertThat(rerank.workload).isEqualTo(ModelWorkload.RERANK)
        assertThat(rerank.contextTokens).isEqualTo(128_000)
        assertThat(rerank.outputTokens).isEqualTo(4_096)
        assertThat(rerank.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNSUPPORTED)
        assertThat(rerank.capabilities[ModelCapability.TOOL_CALLING])
            .isEqualTo(SupportState.UNSUPPORTED)
        assertThat(rerank.capabilities[ModelCapability.STRUCTURED_OUTPUT])
            .isEqualTo(SupportState.UNKNOWN)
        assertThat(rerank.sourceByField["contextTokens"])
            .isEqualTo(MetadataSource.NEXARA_OVERRIDE)
    }

    @Test
    fun `运行时 resolver 保留 MiniMax 上游 canonical 大小写`() {
        val runtimeResolver = ModelCatalogRuntime.resolverFor(catalog)

        assertThat(runtimeResolver.resolve("minimax-m3").canonicalModelId)
            .isEqualTo("minimax/MiniMax-M3")
        assertThat(runtimeResolver.resolve("minimax-m2.7-highspeed").canonicalModelId)
            .isEqualTo("minimax/MiniMax-M2.7-highspeed")
    }

    @Test
    fun `冻结的兼容型号全部来自 Nexara exact overlay`() {
        val compatibilityIds = listOf(
            "o1-preview",
            "gemini-1.5-pro",
            "gemini-2.0-flash-thinking",
            "deepseek-v3",
            "qwen2.5-72b",
            "llama-3.1-405b",
            "qwen-long",
            "grok-4.1",
            "gemma-4-31b",
            "bge-reranker-v2-m3",
            "gemini-3.1-pro",
            "gemini-3-flash",
            "glm-4v",
        )
        val runtimeResolver = ModelCatalogRuntime.resolverFor(catalog)

        compatibilityIds.forEach { modelId ->
            assertThat(catalog.findExact(modelId)).isNull()
            assertThat(runtimeResolver.resolve(modelId).sourceByField["canonicalModelId"])
                .isEqualTo(MetadataSource.NEXARA_OVERRIDE)
        }
    }

    @Test
    fun `解析结果集合对外不可变`() {
        val resolved = ModelCatalogRuntime.resolverFor(catalog).resolve("deepseek-v4-flash")

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (resolved.capabilities as MutableMap<ModelCapability, SupportState>)[ModelCapability.REASONING] =
                SupportState.UNKNOWN
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (resolved.sourceByField as MutableMap<String, MetadataSource>)["displayName"] =
                MetadataSource.FALLBACK
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (resolved.diagnostics as MutableSet<String>).add("mutated")
        }
    }

    @Test
    fun `畸形 JSON 后运行时只保留 Nexara 精确修正`() {
        ModelCatalogRuntime.withTestResolver(ModelCatalogRuntime.resolverFor(catalog)) {
            ModelCatalogRuntime.withUninitializedTestRuntime {
                ModelCatalogRuntime.initialize(applicationWithBundledCatalog(malformedJson = true))

                assertThat(ModelCatalogRuntime.resolver.resolveExactOrNull("deepseek-v4-flash")).isNotNull()
                assertThat(ModelCatalogRuntime.resolver.resolveExactOrNull("qwen-flash")).isNull()
                val unknown = ModelCatalogRuntime.resolver.resolve("future-provider/model-x")
                assertThat(unknown.canonicalModelId).isNull()
                assertThat(unknown.workload).isEqualTo(ModelWorkload.UNKNOWN)
            }

            assertThat(ModelCatalogRuntime.resolver.resolveExactOrNull("qwen-flash")).isNotNull()
        }
    }

    @Test
    fun `生产目录只初始化一次且并发调用安全`() {
        val openCount = AtomicInteger()
        val assets = mockk<AssetManager>()
        every { assets.open("model-catalog/models-dev.normalized.json") } answers {
            openCount.incrementAndGet()
            bundledCatalogFile().inputStream()
        }
        val application = mockk<Application>().also { app ->
            every { app.assets } returns assets
        }

        ModelCatalogRuntime.withUninitializedTestRuntime {
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val tasks = List(4) {
                    executor.submit {
                        start.await()
                        ModelCatalogRuntime.initialize(application)
                    }
                }
                start.countDown()
                tasks.forEach { it.get() }
            } finally {
                executor.shutdownNow()
            }

            assertThat(openCount.get()).isEqualTo(1)
        }
    }

    private fun record(
        canonicalModelId: String,
        aliases: Set<String>,
    ) = ModelMetadataRecord(
        canonicalModelId = canonicalModelId,
        exactAliases = aliases,
        displayName = canonicalModelId,
        familyName = null,
        workload = ModelWorkload.GENERATIVE_TEXT,
        capabilities = emptyMap(),
        contextTokens = null,
        inputTokens = null,
        outputTokens = null,
        knowledgeCutoff = null,
        source = MetadataSource.MODELS_DEV,
    )

    private fun applicationWithBundledCatalog(malformedJson: Boolean = false): Application {
        val assets = mockk<AssetManager>()
        every { assets.open("model-catalog/models-dev.normalized.json") } answers {
            if (malformedJson) "{not-valid-json".byteInputStream() else bundledCatalogFile().inputStream()
        }
        return mockk<Application>().also { application ->
            every { application.assets } returns assets
        }
    }

    private fun bundledCatalogFile(): File = listOf(
        File("app/src/main/assets/model-catalog/models-dev.normalized.json"),
        File("src/main/assets/model-catalog/models-dev.normalized.json"),
        File("native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json"),
    ).firstOrNull(File::isFile)
        ?: error("models-dev.normalized.json fixture is unavailable")
}

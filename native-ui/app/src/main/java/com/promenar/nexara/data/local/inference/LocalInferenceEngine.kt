package com.promenar.nexara.data.local.inference

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlin.math.sqrt

enum class SlotType { MAIN, EMBEDDING, RERANK }

data class SlotState(
    val modelPath: String? = null,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val loadProgress: Float = 0f,
    val backendType: BackendType = BackendType.LLAMA_CPU,
    val modelName: String = "",
    val modelSize: String = "",
    val quantization: String = "",
    val error: String? = null
)

class LlamaCppBackend : InferenceBackend {

    private var ctx: LlamaContext? = null

    override val backendType: BackendType
        get() = if (ctx?.gpuAccelerated == true) BackendType.LLAMA_VULKAN else BackendType.LLAMA_CPU

    override val isLoaded: Boolean get() = ctx != null

    override suspend fun loadModel(path: String, config: LoadConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val loadedCtx = LlamaContext.load(
                    modelPath = path,
                    contextSize = config.contextSize,
                    nThreads = config.threadCount,
                    useGpu = config.useGpu
                ) ?: throw IllegalStateException("Failed to load model: $path")

                ctx?.release()
                ctx = loadedCtx
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun generate(prompt: String, config: GenerateConfig): Flow<String> {
        val context = ctx ?: throw IllegalStateException("No model loaded")
        return context.generate(prompt, config.maxTokens)
    }

    override suspend fun embed(text: String): Result<FloatArray> {
        val context = ctx ?: return Result.failure(IllegalStateException("No model loaded"))
        return withContext(Dispatchers.IO) {
            try {
                Result.success(context.embed(text))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun release() {
        ctx?.release()
        ctx = null
    }
}

class LocalInferenceEngine(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mainSlot = MutableStateFlow(SlotState())
    val mainSlot: StateFlow<SlotState> = _mainSlot.asStateFlow()

    private val _embeddingSlot = MutableStateFlow(SlotState())
    val embeddingSlot: StateFlow<SlotState> = _embeddingSlot.asStateFlow()

    private val _rerankSlot = MutableStateFlow(SlotState())
    val rerankSlot: StateFlow<SlotState> = _rerankSlot.asStateFlow()

    private val mainBackend: InferenceBackend = LlamaCppBackend()
    private val embeddingBackend: InferenceBackend = LlamaCppBackend()
    private val rerankBackend: InferenceBackend = LlamaCppBackend()

    var onStateChanged: (() -> Unit)? = null

    suspend fun loadModel(
        slot: SlotType,
        modelPath: String,
        config: LoadConfig = LoadConfig()
    ): Result<Unit> {
        val (stateField, backend) = when (slot) {
            SlotType.MAIN -> _mainSlot to mainBackend
            SlotType.EMBEDDING -> _embeddingSlot to embeddingBackend
            SlotType.RERANK -> _rerankSlot to rerankBackend
        }

        stateField.value = stateField.value.copy(
            isLoading = true,
            loadProgress = 0f,
            error = null
        )

        val fileName = java.io.File(modelPath).name
        val sizeBytes = java.io.File(modelPath).length()
        stateField.value = stateField.value.copy(loadProgress = 0.3f)

        return backend.loadModel(modelPath, config).also { result ->
            result.onSuccess {
                stateField.value = SlotState(
                    modelPath = modelPath,
                    isLoaded = true,
                    isLoading = false,
                    loadProgress = 1f,
                    backendType = backend.backendType,
                    modelName = fileName,
                    modelSize = formatFileSize(sizeBytes),
                    quantization = detectQuantization(fileName)
                )
                onStateChanged?.invoke()
            }.onFailure { e ->
                stateField.value = stateField.value.copy(
                    isLoading = false,
                    isLoaded = false,
                    error = e.message
                )
            }
        }
    }

    suspend fun unloadModel(slot: SlotType) {
        when (slot) {
            SlotType.MAIN -> {
                mainBackend.release()
                _mainSlot.value = SlotState()
            }
            SlotType.EMBEDDING -> {
                embeddingBackend.release()
                _embeddingSlot.value = SlotState()
            }
            SlotType.RERANK -> {
                rerankBackend.release()
                _rerankSlot.value = SlotState()
            }
        }
        onStateChanged?.invoke()
    }

    fun generate(prompt: String, config: GenerateConfig = GenerateConfig()): Flow<String> {
        if (!mainBackend.isLoaded)
            throw IllegalStateException("No model loaded in main slot")
        return mainBackend.generate(prompt, config)
    }

    suspend fun embed(text: String): Result<FloatArray> {
        if (!embeddingBackend.isLoaded)
            return Result.failure(IllegalStateException("No embedding model loaded"))
        return embeddingBackend.embed(text)
    }

    suspend fun embedBatch(texts: List<String>): Result<List<FloatArray>> {
        if (!embeddingBackend.isLoaded)
            return Result.failure(IllegalStateException("No embedding model loaded"))
        val results = mutableListOf<FloatArray>()
        for (text in texts) {
            embeddingBackend.embed(text).fold(
                onSuccess = { results.add(it) },
                onFailure = { return Result.failure(it) }
            )
        }
        return Result.success(results)
    }

    suspend fun rerank(
        query: String,
        documents: List<String>,
        topN: Int = 5
    ): Result<List<Pair<Int, Float>>> {
        if (!rerankBackend.isLoaded)
            return Result.failure(IllegalStateException("No reranker model loaded"))

        val queryEmb = rerankBackend.embed(query)
            .getOrElse { return Result.failure(it) }

        val scored = documents.mapIndexed { idx, doc ->
            val docEmb = rerankBackend.embed(doc)
                .getOrElse { return Result.failure(it) }
            Pair(idx, cosineSimilarity(queryEmb, docEmb))
        }.sortedByDescending { it.second }.take(topN)

        return Result.success(scored)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f else dot / (sqrt(normA) * sqrt(normB))
    }

    fun release() {
        scope.cancel()
        mainBackend.release()
        embeddingBackend.release()
        rerankBackend.release()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            else -> "$bytes B"
        }
    }

    private fun detectQuantization(fileName: String): String {
        val patterns = listOf(
            "Q8_0", "Q6_K", "Q5_K_M", "Q5_K_S", "Q4_K_M", "Q4_K_S",
            "Q3_K_M", "Q2_K", "F16", "F32"
        )
        return patterns.find { fileName.contains(it, ignoreCase = true) } ?: "Unknown"
    }
}

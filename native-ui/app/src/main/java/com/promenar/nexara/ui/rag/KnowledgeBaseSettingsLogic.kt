package com.promenar.nexara.ui.rag

import android.content.SharedPreferences
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.RetrievalDepth
import com.promenar.nexara.data.rag.retrievalDepth

/** 检索深度分段控件中的一段：三个档位，或当前值不匹配任何档位时的“自定义”。 */
internal sealed interface RetrievalDepthChoice {
    data class Level(val depth: RetrievalDepth) : RetrievalDepthChoice
    data object Custom : RetrievalDepthChoice
}

/** 三档常驻；四个原始值不匹配任何档位时追加“自定义”段，它只显示选中、不可点按。 */
internal fun retrievalDepthChoices(config: RagConfiguration): List<RetrievalDepthChoice> {
    val levels = RetrievalDepth.entries.map { RetrievalDepthChoice.Level(it) }
    return if (config.retrievalDepth() == null) levels + RetrievalDepthChoice.Custom else levels
}

internal fun selectedRetrievalDepthChoice(config: RagConfiguration): RetrievalDepthChoice =
    config.retrievalDepth()?.let { RetrievalDepthChoice.Level(it) } ?: RetrievalDepthChoice.Custom

/** 改写变体数只在开启查询改写且策略为多查询时生效（K4）。 */
internal fun rewriteVariantsEnabled(config: RagConfiguration): Boolean =
    config.enableQueryRewrite && config.queryRewriteStrategy == "multi-query"

/** 决定已建索引分块方式的三个参数；任一项变化后，已有分块需要重新向量化才会按新值切分。 */
data class ChunkSettings(
    val docChunkSize: Int,
    val chunkOverlap: Int,
    val memoryChunkSize: Int,
)

internal fun RagConfiguration.chunkSettings() = ChunkSettings(docChunkSize, chunkOverlap, memoryChunkSize)

/** 当前分块参数与建索引时的参数不同时提示重建；还没有基线时不提示。 */
internal fun chunkSettingsChanged(config: RagConfiguration, indexed: ChunkSettings?): Boolean =
    indexed != null && config.chunkSettings() != indexed

/**
 * 记录现有索引按哪组分块参数建立。首次读取时以当时的配置为基线（升级前建好的索引按当时的设置切分）；
 * 全部重新向量化或清除向量后改写为当前配置。
 */
interface IndexedChunkBaseline {
    fun load(): ChunkSettings?
    fun save(settings: ChunkSettings)
}

internal class SharedPreferencesIndexedChunkBaseline(private val prefs: SharedPreferences) : IndexedChunkBaseline {
    override fun load(): ChunkSettings? {
        if (!prefs.contains(KEY_DOC_CHUNK_SIZE)) return null
        return ChunkSettings(
            docChunkSize = prefs.getInt(KEY_DOC_CHUNK_SIZE, 0),
            chunkOverlap = prefs.getInt(KEY_CHUNK_OVERLAP, 0),
            memoryChunkSize = prefs.getInt(KEY_MEMORY_CHUNK_SIZE, 0),
        )
    }

    override fun save(settings: ChunkSettings) {
        prefs.edit()
            .putInt(KEY_DOC_CHUNK_SIZE, settings.docChunkSize)
            .putInt(KEY_CHUNK_OVERLAP, settings.chunkOverlap)
            .putInt(KEY_MEMORY_CHUNK_SIZE, settings.memoryChunkSize)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "rag_index_baseline"
        private const val KEY_DOC_CHUNK_SIZE = "doc_chunk_size"
        private const val KEY_CHUNK_OVERLAP = "chunk_overlap"
        private const val KEY_MEMORY_CHUNK_SIZE = "memory_chunk_size"
    }
}

/** 图谱抽取与查询改写的模型行：未单独设置时为 null，界面显示“跟随快速模型”；设置的模型已不存在时显示原始标识。 */
internal fun knowledgeModelLabel(modelId: String?, models: List<ModelInfo>): String? =
    modelId?.takeIf(String::isNotBlank)?.let { id -> models.firstOrNull { it.id == id }?.name ?: id }

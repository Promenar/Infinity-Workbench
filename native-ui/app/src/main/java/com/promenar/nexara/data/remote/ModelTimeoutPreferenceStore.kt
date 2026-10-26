package com.promenar.nexara.data.remote

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 与预设模型共用 nexara_settings；写入先更新内存，磁盘由 SharedPreferences 异步保存。 */
internal class ModelTimeoutPreferenceStore(private val prefs: SharedPreferences) {
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<ModelTimeoutSettings> = mutableSettings.asStateFlow()

    @Synchronized
    fun set(kind: ModelTimeoutKind, seconds: Int) {
        val value = seconds.coerceIn(ModelTimeoutSettings.MIN_SECONDS, ModelTimeoutSettings.MAX_SECONDS)
        val current = mutableSettings.value
        val updated = when (kind) {
            ModelTimeoutKind.LLM -> current.copy(llmSeconds = value)
            ModelTimeoutKind.IMAGE -> current.copy(imageSeconds = value)
            ModelTimeoutKind.EMBEDDING -> current.copy(embeddingSeconds = value)
            ModelTimeoutKind.RERANK -> current.copy(rerankSeconds = value)
        }
        prefs.edit().putInt(key(kind), value).apply()
        mutableSettings.value = updated
    }

    private fun read(): ModelTimeoutSettings {
        val defaults = ModelTimeoutSettings()
        fun seconds(kind: ModelTimeoutKind): Int =
            runCatching { prefs.getInt(key(kind), defaults.seconds(kind)) }.getOrDefault(defaults.seconds(kind))
                .coerceIn(ModelTimeoutSettings.MIN_SECONDS, ModelTimeoutSettings.MAX_SECONDS)
        return ModelTimeoutSettings(
            llmSeconds = seconds(ModelTimeoutKind.LLM),
            imageSeconds = seconds(ModelTimeoutKind.IMAGE),
            embeddingSeconds = seconds(ModelTimeoutKind.EMBEDDING),
            rerankSeconds = seconds(ModelTimeoutKind.RERANK),
        )
    }

    // 键写成字面量，备份偏好清单测试才能逐个核对其备份策略。
    private fun key(kind: ModelTimeoutKind) = when (kind) {
        ModelTimeoutKind.LLM -> "model_timeout_llm_seconds"
        ModelTimeoutKind.IMAGE -> "model_timeout_image_seconds"
        ModelTimeoutKind.EMBEDDING -> "model_timeout_embedding_seconds"
        ModelTimeoutKind.RERANK -> "model_timeout_rerank_seconds"
    }
}

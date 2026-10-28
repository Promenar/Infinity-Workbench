package com.promenar.infinity.data.remote

enum class ModelTimeoutKind { LLM, IMAGE, EMBEDDING, RERANK }

data class ModelTimeoutSettings(
    val llmSeconds: Int = 120,
    val imageSeconds: Int = 120,
    val embeddingSeconds: Int = 30,
    val rerankSeconds: Int = 30,
) {
    fun seconds(kind: ModelTimeoutKind): Int = when (kind) {
        ModelTimeoutKind.LLM -> llmSeconds
        ModelTimeoutKind.IMAGE -> imageSeconds
        ModelTimeoutKind.EMBEDDING -> embeddingSeconds
        ModelTimeoutKind.RERANK -> rerankSeconds
    }

    companion object {
        const val MIN_SECONDS = 5
        const val MAX_SECONDS = 1800
    }
}

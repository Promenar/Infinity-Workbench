package com.promenar.nexara.data.remote

import com.promenar.nexara.data.remote.parser.ProviderType

/**
 * FormatterFactory
 *
 * 根据Provider类型创建对应的MessageFormatter
 */
object MessageFormatterFactory {
    private val formatters = mutableMapOf<String, MessageFormatter>()

    /**
     * 获取Provider对应的Formatter（缓存策略）
     * @param provider Provider类型
     * @param modelName 模型名称（可选，用于模型特定优化）
     */
    fun getFormatter(provider: ProviderType, modelName: String? = null): MessageFormatter {
        val cacheKey = if (modelName != null) "${provider}:$modelName" else provider.name

        return formatters.getOrPut(cacheKey) {
            createFormatter(provider, modelName)
        }
    }

    private fun createFormatter(provider: ProviderType, modelName: String?): MessageFormatter {
        return when (provider) {
            ProviderType.OPENAI_COMPATIBLE -> {
                // Determine if it's DeepSeek, GLM, or Moonshot based on modelName or other heuristics
                // For now, mapping based on modelName if available
                val model = modelName?.lowercase() ?: ""
                when {
                    model.contains("deepseek") -> DeepSeekFormatter(model)
                    model.contains("glm") || model.contains("chatglm") -> GLMFormatter()
                    model.contains("moonshot") || model.contains("kimi") -> MoonshotFormatter()
                    else -> OpenAIFormatter()
                }
            }
            ProviderType.VERTEX_AI -> GeminiFormatter(modelName ?: "")
            ProviderType.ANTHROPIC_COMPATIBLE -> OpenAIFormatter() // Anthropic could have its own, but OpenAI format is standard
            ProviderType.GENERIC -> OpenAIFormatter()
        }
    }

    /**
     * 清除缓存（用于测试）
     */
    fun clearCache() {
        formatters.clear()
    }
}

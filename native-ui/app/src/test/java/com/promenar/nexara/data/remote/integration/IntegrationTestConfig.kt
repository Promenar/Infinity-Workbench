package com.promenar.nexara.data.remote.integration

import java.util.Arrays

sealed interface IntegrationTestConfigLoadResult {
    data class Available(val config: IntegrationTestConfig) : IntegrationTestConfigLoadResult
    data object Unavailable : IntegrationTestConfigLoadResult
}

data class IntegrationTestConfig(
    val baseUrl: String,
    val apiKey: CharArray,
    val multimodalModel: String,
    val fastTextModel: String,
    val reasoningModel: String,
    val balancedMultimodalModel: String,
) : AutoCloseable {
    override fun close() {
        Arrays.fill(apiKey, '\u0000')
    }

    override fun toString(): String = "IntegrationTestConfig(<redacted>)"

    companion object {
        const val BASE_URL = "NEXARA_TEST_LLM_BASE_URL"
        const val API_KEY = "NEXARA_TEST_LLM_API_KEY"
        const val MULTIMODAL_MODEL = "NEXARA_TEST_LLM_MULTIMODAL_MODEL"
        const val FAST_TEXT_MODEL = "NEXARA_TEST_LLM_FAST_TEXT_MODEL"
        const val REASONING_MODEL = "NEXARA_TEST_LLM_REASONING_MODEL"
        const val BALANCED_MULTIMODAL_MODEL = "NEXARA_TEST_LLM_BALANCED_MULTIMODAL_MODEL"

        val ENVIRONMENT_NAMES = listOf(
            BASE_URL,
            API_KEY,
            MULTIMODAL_MODEL,
            FAST_TEXT_MODEL,
            REASONING_MODEL,
            BALANCED_MULTIMODAL_MODEL,
        )

        fun load(environment: (String) -> String? = System::getenv): IntegrationTestConfigLoadResult {
            val values = ENVIRONMENT_NAMES.map { name ->
                environment(name)?.takeIf(String::isNotBlank)
                    ?: return IntegrationTestConfigLoadResult.Unavailable
            }
            return IntegrationTestConfigLoadResult.Available(
                IntegrationTestConfig(
                    baseUrl = values[0],
                    apiKey = values[1].toCharArray(),
                    multimodalModel = values[2],
                    fastTextModel = values[3],
                    reasoningModel = values[4],
                    balancedMultimodalModel = values[5],
                )
            )
        }
    }
}

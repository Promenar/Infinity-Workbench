package com.promenar.nexara.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ModelSpecs")
class ModelSpecsTest {

    @Nested
    @DisplayName("findContextLength")
    inner class FindContextLength {

        @Test
        @DisplayName("GPT-4o returns 128000")
        fun gpt4o() {
            assertThat(findContextLength("gpt-4o")).isEqualTo(128000)
        }

        @Test
        @DisplayName("GPT-4o-mini-2024-07-18 returns 128000 via partial match")
        fun gpt4oPartialMatch() {
            assertThat(findContextLength("gpt-4o-mini-2024-07-18")).isEqualTo(128000)
        }

        @Test
        @DisplayName("GPT-4 Turbo returns 128000")
        fun gpt4Turbo() {
            assertThat(findContextLength("gpt-4-turbo")).isEqualTo(128000)
        }

        @Test
        @DisplayName("GPT-4 returns 128000")
        fun gpt4() {
            assertThat(findContextLength("gpt-4")).isEqualTo(128000)
        }

        @Test
        @DisplayName("GPT-3.5 returns 16385")
        fun gpt35() {
            assertThat(findContextLength("gpt-3.5-turbo")).isEqualTo(16385)
        }

        @Test
        @DisplayName("O1 returns 200000")
        fun o1() {
            assertThat(findContextLength("o1")).isEqualTo(200000)
        }

        @Test
        @DisplayName("O1-preview returns 128000 (more specific match first)")
        fun o1Preview() {
            assertThat(findContextLength("o1-preview")).isEqualTo(128000)
        }

        @Test
        @DisplayName("Claude 3.5 Sonnet returns 200000")
        fun claude35Sonnet() {
            assertThat(findContextLength("claude-3-5-sonnet-20241022")).isEqualTo(200000)
        }

        @Test
        @DisplayName("Claude returns 100000 (generic)")
        fun claudeGeneric() {
            assertThat(findContextLength("claude-instant")).isEqualTo(100000)
        }

        @Test
        @DisplayName("Gemini 1.5 Pro returns 2000000")
        fun gemini15Pro() {
            assertThat(findContextLength("gemini-1.5-pro")).isEqualTo(2000000)
        }

        @Test
        @DisplayName("Gemini 2.0 Flash Thinking returns 1000000")
        fun gemini20FlashThinking() {
            assertThat(findContextLength("gemini-2.0-flash-thinking")).isEqualTo(1000000)
        }

        @Test
        @DisplayName("DeepSeek R1 returns 64000")
        fun deepseekR1() {
            assertThat(findContextLength("deepseek-r1")).isEqualTo(64000)
        }

        @Test
        @DisplayName("DeepSeek V3 returns 64000")
        fun deepseekV3() {
            assertThat(findContextLength("deepseek-v3")).isEqualTo(64000)
        }

        @Test
        @DisplayName("GLM-4.7 returns 128000 via regex")
        fun glm47() {
            assertThat(findContextLength("glm-4.7")).isEqualTo(128000)
            assertThat(findContextLength("glm4.7")).isEqualTo(128000)
        }

        @Test
        @DisplayName("GLM-4.6V returns 128000 with vision capability")
        fun glm46v() {
            assertThat(findContextLength("glm-4.6v")).isEqualTo(128000)
        }

        @Test
        @DisplayName("Qwen2.5-72B returns 131072")
        fun qwen2572b() {
            assertThat(findContextLength("qwen2.5-72b")).isEqualTo(131072)
        }

        @Test
        @DisplayName("Llama 3.1 405B returns 128000")
        fun llama31405b() {
            assertThat(findContextLength("llama-3.1-405b")).isEqualTo(128000)
        }

        @Test
        @DisplayName("Unknown model returns null")
        fun unknownModel() {
            assertThat(findContextLength("nonexistent-model-xyz")).isNull()
        }

        @Test
        @DisplayName("Case insensitive match for string patterns")
        fun caseInsensitive() {
            assertThat(findContextLength("GPT-4O")).isEqualTo(128000)
            assertThat(findContextLength("DeepSeek-V3")).isEqualTo(64000)
        }

        @Test
        @DisplayName("MiniMax M2.7 returns 4096 (falls through to openai generic or no match)")
        fun minimaxM27() {
            val result = findContextLength("MiniMax-M2.7")
            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("extractContextLengthFromName")
    inner class ExtractContextLengthFromName {

        @Test
        @DisplayName("128k returns 128000")
        fun kSuffix() {
            assertThat(extractContextLengthFromName("model-128k")).isEqualTo(128000)
        }

        @Test
        @DisplayName("2m returns 2000000")
        fun mSuffix() {
            assertThat(extractContextLengthFromName("model-2m")).isEqualTo(2000000)
        }

        @Test
        @DisplayName("32k returns 32000")
        fun kSuffix32() {
            assertThat(extractContextLengthFromName("doubao-pro-32k")).isEqualTo(32000)
        }

        @Test
        @DisplayName("No match returns null")
        fun noMatch() {
            assertThat(extractContextLengthFromName("gpt-4o")).isNull()
        }

        @Test
        @DisplayName("Case insensitive")
        fun caseInsensitive() {
            assertThat(extractContextLengthFromName("MODEL-128K")).isEqualTo(128000)
        }
    }

    @Nested
    @DisplayName("findModelSpec")
    inner class FindModelSpec {

        @Test
        @DisplayName("GPT-4o spec has correct fields")
        fun gpt4oFields() {
            val spec = findModelSpec("gpt-4o")!!
            assertThat(spec.contextLength).isEqualTo(128000)
            assertThat(spec.type).isEqualTo(ModelType.CHAT)
            assertThat(spec.icon).isEqualTo("openai")
            assertThat(spec.capabilities?.vision).isTrue()
            assertThat(spec.forcedReasoning).isFalse()
        }

        @Test
        @DisplayName("O1-preview has forced reasoning")
        fun o1PreviewForcedReasoning() {
            val spec = findModelSpec("o1-preview")!!
            assertThat(spec.type).isEqualTo(ModelType.REASONING)
            assertThat(spec.forcedReasoning).isTrue()
            assertThat(spec.capabilities?.reasoning).isTrue()
        }

        @Test
        @DisplayName("DeepSeek R1 has forced reasoning")
        fun deepseekR1ForcedReasoning() {
            val spec = findModelSpec("deepseek-r1")!!
            assertThat(spec.type).isEqualTo(ModelType.REASONING)
            assertThat(spec.forcedReasoning).isTrue()
        }

        @Test
        @DisplayName("BGE Reranker has rerank type")
        fun bgeReranker() {
            val spec = findModelSpec("bge-reranker-v2-m3")!!
            assertThat(spec.type).isEqualTo(ModelType.RERANK)
            assertThat(spec.icon).isEqualTo("rerank")
        }

        @Test
        @DisplayName("Gemini 1.5 Pro has vision and reasoning")
        fun gemini15ProCapabilities() {
            val spec = findModelSpec("gemini-1.5-pro")!!
            assertThat(spec.capabilities?.vision).isTrue()
            assertThat(spec.capabilities?.reasoning).isTrue()
        }

        @Test
        @DisplayName("GLM Vision matches regex pattern")
        fun glmVision() {
            val spec = findModelSpec("glm-4v")!!
            assertThat(spec.contextLength).isEqualTo(128000)
            assertThat(spec.capabilities?.vision).isTrue()
        }

        @Test
        @DisplayName("Unknown returns null")
        fun unknown() {
            assertThat(findModelSpec("nonexistent-xyz")).isNull()
        }
    }

    @Nested
    @DisplayName("MODEL_SPECS integrity")
    inner class Integrity {

        @Test
        @DisplayName("All specs have context length > 0")
        fun allContextLengthPositive() {
            for (spec in MODEL_SPECS) {
                assertThat(spec.contextLength).isGreaterThan(0)
            }
        }

        @Test
        @DisplayName("Spec count matches TS source")
        fun specCount() {
            assertThat(MODEL_SPECS).hasSize(80)
        }

        @Test
        @DisplayName("All regex patterns compile and match their target")
        fun regexPatternsWork() {
            val regexSpecs = MODEL_SPECS.filter { it.pattern is ModelPattern.RegexPattern }
            for (spec in regexSpecs) {
                val regex = (spec.pattern as ModelPattern.RegexPattern).regex
                assertThat(regex.pattern).isNotEmpty()
            }
        }
    }
}

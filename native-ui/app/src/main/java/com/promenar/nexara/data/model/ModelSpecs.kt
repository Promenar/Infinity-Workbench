package com.promenar.nexara.data.model

enum class ModelType {
    CHAT, REASONING, IMAGE, EMBEDDING, RERANK
}

data class ModelCapabilities(
    val vision: Boolean = false,
    val internet: Boolean = false,
    val reasoning: Boolean = false
)

sealed class ModelPattern {
    data class StringPattern(val value: String) : ModelPattern()
    data class RegexPattern(val regex: Regex) : ModelPattern()

    fun matches(modelId: String): Boolean = when (this) {
        is StringPattern -> modelId.lowercase().contains(value.lowercase())
        is RegexPattern -> regex.containsMatchIn(modelId)
    }
}

data class ModelSpec(
    val pattern: ModelPattern,
    val contextLength: Int,
    val type: ModelType? = null,
    val capabilities: ModelCapabilities? = null,
    val forcedReasoning: Boolean = false,
    val icon: String? = null,
    val note: String? = null
)

val MODEL_SPECS: List<ModelSpec> = listOf(

    // ==================== OpenAI ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4o"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "openai",
        note = "GPT-4o series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4-turbo"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "openai",
        note = "GPT-4 Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "openai",
        note = "GPT-4 Generic"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-3.5"),
        contextLength = 16385,
        type = ModelType.CHAT,
        icon = "openai",
        note = "GPT-3.5"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("openai"),
        contextLength = 4096,
        icon = "openai",
        note = "OpenAI Generic"
    ),

    // O1 series (reasoning models, cannot disable reasoning)
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1-preview"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1 Preview"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1-mini"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1 Mini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1"
    ),

    // ==================== Anthropic ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3-5-sonnet"),
        contextLength = 200000,
        icon = "claude",
        note = "Claude 3.5 Sonnet"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3-5"),
        contextLength = 200000,
        icon = "claude",
        note = "Claude 3.5"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3"),
        contextLength = 200000,
        icon = "claude",
        note = "Claude 3"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude"),
        contextLength = 100000,
        icon = "claude",
        note = "Claude Generic"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("anthropic"),
        contextLength = 100000,
        icon = "anthropic",
        note = "Anthropic Generic"
    ),

    // ==================== Google Gemini ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0-flash-thinking"),
        contextLength = 1000000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0 Flash Thinking"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-1.5-pro"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 1.5 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-1.5-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 1.5 Flash"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-1.5"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "gemini",
        note = "Gemini 1.5"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        icon = "gemini",
        note = "Gemini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("google"),
        contextLength = 32768,
        icon = "google",
        note = "Google Generic"
    ),

    // ==================== DeepSeek ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-reasoner"),
        contextLength = 64000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "deepseek",
        note = "DeepSeek R1 (Native Reasoning)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-r1"),
        contextLength = 64000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "deepseek",
        note = "DeepSeek R1"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-v3"),
        contextLength = 64000,
        type = ModelType.CHAT,
        icon = "deepseek",
        note = "DeepSeek V3"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek"),
        contextLength = 64000,
        icon = "deepseek",
        note = "DeepSeek"
    ),

    // ==================== Zhipu AI (GLM) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4\.7""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "zhipu",
        note = "GLM-4.7 (Reasoning)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4\.6.*v""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "zhipu",
        note = "GLM-4.6V (Vision)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "zhipu",
        note = "GLM-4.5 (Reasoning)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm.*v(?:ision)?$""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "zhipu",
        note = "GLM Vision Series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("glm-4-plus"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-4 Plus"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("glm-4"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-4"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("glm-3"),
        contextLength = 128000,
        icon = "zhipu"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("zhipu"),
        contextLength = 128000,
        icon = "zhipu"
    ),

    // ==================== Moonshot (Kimi) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("thinking"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "kimi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi"),
        contextLength = 128000,
        icon = "kimi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("moonshot"),
        contextLength = 128000,
        icon = "moonshot"
    ),

    // ==================== Baichuan ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan-4""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan 4"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan-3-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan 3 Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan-2-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan 2 Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan"
    ),

    // ==================== Qwen ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-max""", RegexOption.IGNORE_CASE)),
        contextLength = 8000,
        icon = "qwen",
        note = "Qwen Max"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-plus""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "qwen",
        note = "Qwen Plus"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 8000,
        icon = "qwen",
        note = "Qwen Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-72b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 72B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-32b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 32B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-14b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 14B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-7b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 7B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2-72b""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "qwen",
        note = "Qwen2 72B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen""", RegexOption.IGNORE_CASE)),
        contextLength = 8000,
        icon = "qwen",
        note = "Qwen"
    ),

    // ==================== ERNIE ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-4\.0""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE 4.0"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-3\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE 3.5"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-speed""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE Speed"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE"
    ),

    // ==================== Doubao ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-pro-32k""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "doubao",
        note = "Doubao Pro 32K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-pro-4k""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "doubao",
        note = "Doubao Pro 4K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-lite-32k""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "doubao",
        note = "Doubao Lite 32K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "doubao",
        note = "Doubao"
    ),

    // ==================== Yi ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-large""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "yi",
        note = "Yi Large"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-medium""", RegexOption.IGNORE_CASE)),
        contextLength = 16384,
        icon = "yi",
        note = "Yi Medium"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-34b-chat""", RegexOption.IGNORE_CASE)),
        contextLength = 200000,
        icon = "yi",
        note = "Yi 34B Chat 200K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-6b""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "yi",
        note = "Yi 6B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "yi",
        note = "Yi series"
    ),

    // ==================== MiniMax ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""abab6\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 245760,
        icon = "minimax",
        note = "ABAB 6.5 (245K)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""abab6""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "minimax",
        note = "ABAB 6"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""abab5\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "minimax",
        note = "ABAB 5.5"
    ),

    // ==================== Open Source Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3\.1-405b""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "meta",
        note = "Llama 3.1 405B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3\.1-70b""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "meta",
        note = "Llama 3.1 70B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3\.1""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "meta",
        note = "Llama 3.1"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3-70b""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "meta",
        note = "Llama 3 70B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "meta",
        note = "Llama 3"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-2-70b""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "meta",
        note = "Llama 2 70B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-2""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "meta",
        note = "Llama 2"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-large""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "mistral",
        note = "Mistral Large"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-medium""", RegexOption.IGNORE_CASE)),
        contextLength = 32000,
        icon = "mistral",
        note = "Mistral Medium"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-small""", RegexOption.IGNORE_CASE)),
        contextLength = 32000,
        icon = "mistral",
        note = "Mistral Small"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mixtral-8x7b""", RegexOption.IGNORE_CASE)),
        contextLength = 32000,
        icon = "mistral",
        note = "Mixtral 8x7B"
    ),

    // ==================== Rerank Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""bge-reranker""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        icon = "rerank",
        note = "BGE Reranker"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""jina-reranker""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.RERANK,
        icon = "rerank",
        note = "Jina Reranker"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""cohere-rerank""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        icon = "rerank",
        note = "Cohere Rerank"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""rerank""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        icon = "rerank",
        note = "Generic Rerank Model"
    )
)

fun findContextLength(modelId: String): Int? {
    for (spec in MODEL_SPECS) {
        if (spec.pattern.matches(modelId)) {
            return spec.contextLength
        }
    }
    return null
}

fun extractContextLengthFromName(text: String): Int? {
    val normalized = text.lowercase()
    val kMatch = Regex("""(\d+)k\b""").find(normalized)
    if (kMatch != null) {
        return kMatch.groupValues[1].toInt() * 1000
    }
    val mMatch = Regex("""(\d+)m\b""").find(normalized)
    if (mMatch != null) {
        return mMatch.groupValues[1].toInt() * 1000000
    }
    return null
}

fun findModelSpec(modelId: String): ModelSpec? {
    for (spec in MODEL_SPECS) {
        if (spec.pattern.matches(modelId)) {
            return spec
        }
    }
    return null
}

package com.promenar.nexara.data.model

enum class ModelType {
    CHAT, REASONING, IMAGE, EMBEDDING, RERANK
}

data class ModelCapabilities(
    val vision: Boolean = false,
    val internet: Boolean = false,
    val reasoning: Boolean = false,
    val image: Boolean = false,
    val embedding: Boolean = false,
    val rerank: Boolean = false,
    val audioInput: Boolean = false,
    val audioOutput: Boolean = false,
    val videoUnderstanding: Boolean = false,
    val structuredOutput: Boolean = false,
    val promptCaching: Boolean = false,
    val computerUse: Boolean = false
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

    // ==================== OpenAI (2025-2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4.1"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true, promptCaching = true),
        icon = "openai",
        note = "GPT-4.1 (1M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4.1-mini"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-4.1 Mini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4.1-nano"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(structuredOutput = true),
        icon = "openai",
        note = "GPT-4.1 Nano"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4o-mini"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "openai",
        note = "GPT-4o Mini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4o"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, audioInput = true, audioOutput = true, structuredOutput = true),
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

    // ==================== OpenAI (2025-2026) ====================
    // O3/O4 series (reasoning models)
    ModelSpec(
        pattern = ModelPattern.StringPattern("o4-mini"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true, structuredOutput = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O4 Mini (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o3"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true, structuredOutput = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O3 (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o3-mini"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O3 Mini"
    ),
    // O1 series (reasoning models, cannot disable reasoning)
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1-pro"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1 Pro"
    ),
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

    // ==================== Anthropic (2025-2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-4-opus"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, computerUse = true, promptCaching = true, structuredOutput = true),
        icon = "claude",
        note = "Claude 4 Opus (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-4-sonnet"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, computerUse = true, promptCaching = true, structuredOutput = true),
        icon = "claude",
        note = "Claude 4 Sonnet (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3.7-sonnet"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, promptCaching = true, computerUse = true),
        icon = "claude",
        note = "Claude 3.7 Sonnet"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3.5-haiku"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, promptCaching = true),
        icon = "claude",
        note = "Claude 3.5 Haiku"
    ),
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

    // ==================== Google Gemini (2025) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.5-pro"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, structuredOutput = true, audioInput = true, videoUnderstanding = true),
        icon = "gemini",
        note = "Gemini 2.5 Pro (2M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.5-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true),
        icon = "gemini",
        note = "Gemini 2.5 Flash"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.5"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.5"
    ),
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
        pattern = ModelPattern.StringPattern("gemini-2.0-pro"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0 Flash"
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
        pattern = ModelPattern.StringPattern("gemini-1.0-pro"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "gemini",
        note = "Gemini 1.0 Pro"
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

    // ==================== DeepSeek (2025) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-r2"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        forcedReasoning = true,
        icon = "deepseek",
        note = "DeepSeek R2 (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-v3.1"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "deepseek",
        note = "DeepSeek V3.1 (2025)"
    ),
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
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4[.-]?9b""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-4-9B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4[.-]?v""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "zhipu",
        note = "GLM-4V"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-4 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("glm-edge"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-Edge"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("cogview"),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "zhipu",
        note = "CogView (Image Generation)"
    ),

    // ==================== Alibaba (Qwen) (2025) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-235b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen3 235B (MoE)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-72b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen3 72B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-32b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen3 32B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-14b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen3 14B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-8b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen3 8B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-vl""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, videoUnderstanding = true),
        icon = "qwen",
        note = "Qwen3 VL"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-coder""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen3 Coder"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?2\.5-?math""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen 2.5 Math"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?2\.5-?coder""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen 2.5 Coder"
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
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?2\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen 2.5"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?vl""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "qwen",
        note = "Qwen-VL"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("qwen"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "qwen"
    ),

    // ==================== Meta (Llama) (2025) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4-maverick""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "meta",
        note = "Llama 4 Maverick (1M)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4-scout""", RegexOption.IGNORE_CASE)),
        contextLength = 10000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "meta",
        note = "Llama 4 Scout (10M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4-behemoth""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "meta",
        note = "Llama 4 Behemoth"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        icon = "meta",
        note = "Llama 4 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?3\.2-?vision""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "meta",
        note = "Llama 3.2 Vision"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?3\.2""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "meta",
        note = "Llama 3.2"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?3\.1""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "meta",
        note = "Llama 3.1"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("llama-3"),
        contextLength = 8192,
        type = ModelType.CHAT,
        icon = "meta"
    ),

    // ==================== Mistral AI (2025) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral-large-2"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "mistral",
        note = "Mistral Large 2"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("codestral"),
        contextLength = 256000,
        type = ModelType.CHAT,
        icon = "mistral",
        note = "Codestral (256K, code-focused)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("pixtral"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "mistral",
        note = "Pixtral (Vision)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral-large"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "mistral"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "mistral"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mixtral"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "mistral"
    ),

    // ==================== 01.AI (Yi) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("yi-lightning"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "yi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("yi-vision"),
        contextLength = 16384,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "yi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("yi"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "yi"
    ),

    // ==================== Moonshot (Kimi) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "kimi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("moonshot"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "kimi"
    ),

    // ==================== ByteDance (Doubao) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("doubao"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "bytedance"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("skylark"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "bytedance"
    ),

    // ==================== MiniMax ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("abab6.5"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "minimax"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("abab7"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "minimax"
    ),

    // ==================== Baichuan ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("baichuan"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "baichuan"
    ),

    // ==================== StepFun (阶跃星辰) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("step-"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "stepfun"
    ),

    // ==================== SenseTime (商汤日日新) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("sensechat"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "sensetime"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("sensenova"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "sensetime"
    ),

    // ==================== xAI (Grok) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok-3"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true, internet = true),
        icon = "xai",
        note = "Grok 3 (1M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok-2"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "xai"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "xai"
    ),

    // ==================== Cohere ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-r-plus""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(rerank = true, internet = true),
        icon = "cohere",
        note = "Command R+ (128K)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-r""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "cohere",
        note = "Command R"
    ),

    // ==================== Microsoft (Phi) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""phi-4-multimodal""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, audioInput = true, reasoning = true),
        icon = "microsoft",
        note = "Phi-4 Multimodal (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""phi-4""", RegexOption.IGNORE_CASE)),
        contextLength = 16384,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "microsoft",
        note = "Phi-4"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""phi-3""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "microsoft",
        note = "Phi-3 Series"
    ),

    // ==================== IBM (Granite) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""granite-3\.2""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "ibm",
        note = "Granite 3.2"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""granite""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "ibm",
        note = "Granite Series"
    ),

    // ==================== Local / Other ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-r""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi-k1.5"),
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

    // ==================== Gemma ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma-2-27b""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "gemma",
        note = "Gemma 2 27B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma-2-9b""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "gemma",
        note = "Gemma 2 9B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "gemma",
        note = "Gemma Series"
    ),

    // ==================== Rerank Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""bge-reranker""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "BGE Reranker"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""jina-reranker""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "Jina Reranker"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""cohere-rerank""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "Cohere Rerank"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""rerank""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "Generic Rerank Model"
    ),

    // ==================== Embedding Models ====================

    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""embedding""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.EMBEDDING,
        capabilities = ModelCapabilities(embedding = true),
        icon = "embedding",
        note = "Generic Embedding Model"
    ),

    // ==================== Image Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""dall-e""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "DALL-E Series"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""flux""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "Flux Series"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""stable-diffusion""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "Stable Diffusion"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""sdxl""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "SDXL"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""kolors""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "Kolors"
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

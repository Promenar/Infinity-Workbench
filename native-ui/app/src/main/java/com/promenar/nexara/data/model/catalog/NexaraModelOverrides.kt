package com.promenar.nexara.data.model.catalog

import java.util.Collections

internal val NEXARA_EXACT_MODEL_OVERRIDES = Collections.unmodifiableMap(listOf(
    exactModel(
        exactAlias = "deepseek-v4-flash",
        canonicalId = "deepseek/deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
        familyName = "DeepSeek V4",
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "minimax-m3",
        canonicalId = "minimax/MiniMax-M3",
        displayName = "MiniMax M3",
        familyName = "MiniMax M3",
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "minimax-m2.7-highspeed",
        canonicalId = "minimax/MiniMax-M2.7-highspeed",
        displayName = "MiniMax M2.7 Highspeed",
        familyName = "MiniMax M2.7",
        capabilities = capabilities(reasoning = SupportState.UNKNOWN),
    ),
    exactModel(
        exactAlias = "sensenova-6.7-flash-lite",
        canonicalId = "sensenova/sensenova-6.7-flash-lite",
        displayName = "SenseNova 6.7 Flash Lite",
        familyName = "SenseNova 6.7",
        capabilities = capabilities(reasoning = SupportState.UNKNOWN),
    ),
    exactModel(
        exactAlias = "o1-preview",
        displayName = "O1 Preview",
        familyName = "OpenAI O1",
        contextTokens = 128_000,
        capabilities = capabilities(reasoning = SupportState.SUPPORTED),
    ),
    exactModel(
        exactAlias = "gemini-1.5-pro",
        displayName = "Gemini 1.5 Pro",
        familyName = "Gemini 1.5",
        contextTokens = 2_000_000,
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
            vision = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "gemini-2.0-flash-thinking",
        displayName = "Gemini 2.0 Flash Thinking",
        familyName = "Gemini 2.0",
        contextTokens = 1_000_000,
        capabilities = capabilities(reasoning = SupportState.SUPPORTED),
    ),
    exactModel(
        exactAlias = "deepseek-v3",
        displayName = "DeepSeek V3",
        familyName = "DeepSeek V3",
        contextTokens = 64_000,
    ),
    exactModel(
        exactAlias = "qwen2.5-72b",
        displayName = "Qwen2.5 72B",
        familyName = "Qwen 2.5",
        contextTokens = 131_072,
    ),
    exactModel(
        exactAlias = "llama-3.1-405b",
        displayName = "Llama 3.1",
        familyName = "Llama 3.1",
        contextTokens = 128_000,
    ),
    exactModel(
        exactAlias = "qwen-long",
        displayName = "Qwen Long (10M context)",
        familyName = "Qwen",
        contextTokens = 10_000_000,
    ),
    exactModel(
        exactAlias = "grok-4.1",
        displayName = "Grok 4.1 (Apr 2026, 2M context)",
        familyName = "Grok 4",
        contextTokens = 2_000_000,
        outputTokens = 65_536,
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
            vision = SupportState.SUPPORTED,
            webAccess = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "gemma-4-31b",
        displayName = "Gemma 4 31B (Apr 2026, Apache 2.0)",
        familyName = "Gemma 4",
        contextTokens = 256_000,
        outputTokens = 8_192,
        knowledgeCutoff = "202603",
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
            vision = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "bge-reranker-v2-m3",
        displayName = "BGE Reranker",
        familyName = "BGE",
        workload = ModelWorkload.RERANK,
        contextTokens = 4_096,
    ),
    exactModel(
        exactAlias = "gemini-3.1-pro",
        displayName = "Gemini 3.1 Pro (Apr 2026)",
        familyName = "Gemini 3.1",
        contextTokens = 2_000_000,
        outputTokens = 65_536,
        knowledgeCutoff = "202604",
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
            vision = SupportState.SUPPORTED,
            audioInput = SupportState.SUPPORTED,
            audioOutput = SupportState.SUPPORTED,
            videoInput = SupportState.SUPPORTED,
            structuredOutput = SupportState.SUPPORTED,
            promptCaching = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "gemini-3-flash",
        displayName = "Gemini 3 Flash",
        familyName = "Gemini 3",
        contextTokens = 1_000_000,
        outputTokens = 65_536,
        knowledgeCutoff = "202512",
        capabilities = capabilities(
            reasoning = SupportState.SUPPORTED,
            vision = SupportState.SUPPORTED,
            audioInput = SupportState.SUPPORTED,
            videoInput = SupportState.SUPPORTED,
            structuredOutput = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "glm-4v",
        displayName = "GLM-4V",
        familyName = "GLM-4",
        contextTokens = 128_000,
        capabilities = capabilities(
            vision = SupportState.SUPPORTED,
        ),
    ),
    exactModel(
        exactAlias = "gemini-embedding-001",
        canonicalId = "google/gemini-embedding-001",
        displayName = "Gemini Embedding 001",
        familyName = "gemini",
        workload = ModelWorkload.EMBEDDING,
        contextTokens = 2_048,
        outputTokens = 1,
        knowledgeCutoff = "2025-05",
        capabilities = snapshotCapabilities(),
    ),
    exactModel(
        exactAlias = "llama-nemotron-embed-vl-1b-v2",
        canonicalId = "nvidia/llama-nemotron-embed-vl-1b-v2",
        displayName = "Llama Nemotron Embed VL 1B v2",
        familyName = "nemotron",
        workload = ModelWorkload.EMBEDDING,
        contextTokens = 32_768,
        outputTokens = 2_048,
        capabilities = snapshotCapabilities(vision = SupportState.SUPPORTED),
    ),
    exactModel(
        exactAlias = "llama-nemotron-rerank-vl-1b-v2",
        canonicalId = "nvidia/llama-nemotron-rerank-vl-1b-v2",
        displayName = "Llama Nemotron Rerank VL 1B v2",
        familyName = "nemotron",
        workload = ModelWorkload.RERANK,
        contextTokens = 128_000,
        outputTokens = 4_096,
        capabilities = snapshotCapabilities(vision = SupportState.SUPPORTED),
    ),
).associateBy { record -> normalizeRemoteModelId(record.exactAliases.first()) })

private fun exactModel(
    exactAlias: String,
    canonicalId: String = exactAlias,
    displayName: String,
    familyName: String,
    workload: ModelWorkload = ModelWorkload.GENERATIVE_TEXT,
    capabilities: Map<ModelCapability, SupportState> = emptyMap(),
    contextTokens: Int? = null,
    inputTokens: Int? = null,
    outputTokens: Int? = null,
    knowledgeCutoff: String? = null,
) = ModelMetadataRecord(
    canonicalModelId = canonicalId,
    exactAliases = Collections.unmodifiableSet(linkedSetOf(exactAlias, canonicalId)),
    displayName = displayName,
    familyName = familyName,
    workload = workload,
    capabilities = Collections.unmodifiableMap(LinkedHashMap(capabilities)),
    contextTokens = contextTokens,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    knowledgeCutoff = knowledgeCutoff,
    source = MetadataSource.NEXARA_OVERRIDE,
)

private fun capabilities(
    reasoning: SupportState? = null,
    vision: SupportState? = null,
    audioInput: SupportState? = null,
    audioOutput: SupportState? = null,
    videoInput: SupportState? = null,
    structuredOutput: SupportState? = null,
    promptCaching: SupportState? = null,
    webAccess: SupportState? = null,
): Map<ModelCapability, SupportState> = buildMap {
    reasoning?.let { put(ModelCapability.REASONING, it) }
    vision?.let { put(ModelCapability.VISION_INPUT, it) }
    audioInput?.let { put(ModelCapability.AUDIO_INPUT, it) }
    audioOutput?.let { put(ModelCapability.AUDIO_OUTPUT, it) }
    videoInput?.let { put(ModelCapability.VIDEO_INPUT, it) }
    structuredOutput?.let { put(ModelCapability.STRUCTURED_OUTPUT, it) }
    promptCaching?.let { put(ModelCapability.PROMPT_CACHING, it) }
    webAccess?.let { put(ModelCapability.WEB_ACCESS, it) }
}

private fun snapshotCapabilities(
    vision: SupportState? = null,
): Map<ModelCapability, SupportState> = capabilities(
    reasoning = SupportState.UNSUPPORTED,
    vision = vision,
).toMutableMap().apply {
    put(ModelCapability.TOOL_CALLING, SupportState.UNSUPPORTED)
    put(ModelCapability.STRUCTURED_OUTPUT, SupportState.UNKNOWN)
}

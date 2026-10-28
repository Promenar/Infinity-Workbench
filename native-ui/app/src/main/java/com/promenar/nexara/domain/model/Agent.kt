package com.promenar.nexara.domain.model

import com.promenar.nexara.data.agent.AgentMemoryPolicy
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.model.params.SamplingValues

data class Agent(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val modelId: String = "",
    val icon: String = "✨",
    val color: String = "#C0C1FF",
    val avatarPath: String? = null,
    val isPinned: Boolean = false,
    /** 以下采样参数与推理强度是该助手会话的默认值；为空表示按模型默认、不发送。 */
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    val reasoningEffort: String? = null,
    val ragConfig: AgentRagConfig? = null,
    val retrievalConfig: AgentRetrievalConfig? = null,
    val useInheritedConfig: Boolean = true,
    val executionMode: ExecutionMode = ExecutionMode.WORKSPACE,
    val skills: List<String> = emptyList(),
    val mcpServerIds: List<String> = emptyList(),
    val nameCustomized: Boolean = false,
    val descriptionCustomized: Boolean = false,
    val defaultWorkspaceId: String? = null,
    val userRules: String = "",
    val createdAt: Long = 0L
) {
    fun samplingValues(): SamplingValues =
        SamplingValues(temperature, topP, maxTokens, frequencyPenalty, presencePenalty, topK, repetitionPenalty)

    /** [retrievalConfig] 在运行时只承载记忆权限，检索参数统一取全局。 */
    val memoryPolicy: AgentMemoryPolicy
        get() = retrievalConfig?.memoryPolicy ?: AgentMemoryPolicy()
}

object ExecutionModeCodec {
    fun parseOrWorkspace(raw: String?): ExecutionMode = when (raw?.trim()?.lowercase()) {
        "auto", "full_access" -> ExecutionMode.FULL_ACCESS
        else -> ExecutionMode.WORKSPACE
    }

    fun serialize(mode: ExecutionMode): String = mode.name.lowercase()
}

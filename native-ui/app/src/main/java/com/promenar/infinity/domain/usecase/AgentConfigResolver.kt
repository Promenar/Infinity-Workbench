package com.promenar.infinity.domain.usecase

import android.content.SharedPreferences
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRagConfig
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.domain.model.Agent

class AgentConfigResolver(
    private val globalPrefs: SharedPreferences
) {
    data class ResolvedConfig(
        val systemPrompt: String,
        val modelId: String,
        /** 助手为其会话提供的默认采样参数与推理强度；为空的项按模型默认。 */
        val inferenceDefaults: InferenceParams,
        val ragConfig: AgentRagConfig?,
        val retrievalConfig: AgentRetrievalConfig?,
        val memoryPolicy: AgentMemoryPolicy = AgentMemoryPolicy(),
    )

    fun resolve(agent: Agent?): ResolvedConfig {
        return ResolvedConfig(
            systemPrompt = agent?.systemPrompt ?: "",
            modelId = agent?.modelId ?: globalPrefs.getString("default_model", "") ?: "",
            inferenceDefaults = agent?.inferenceDefaults() ?: InferenceParams(),
            ragConfig = null,
            retrievalConfig = null,
            memoryPolicy = agent?.memoryPolicy ?: AgentMemoryPolicy(),
        )
    }

    fun resolveName(agent: Agent?): String = agent?.name ?: ""
}

fun Agent.inferenceDefaults(): InferenceParams = InferenceParams(
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty,
    topK = topK,
    repetitionPenalty = repetitionPenalty,
    reasoningEffort = reasoningEffort,
)

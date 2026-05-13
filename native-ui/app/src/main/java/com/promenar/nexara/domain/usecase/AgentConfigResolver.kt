package com.promenar.nexara.domain.usecase

import android.content.SharedPreferences
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.domain.model.Agent

class AgentConfigResolver(
    private val globalPrefs: SharedPreferences
) {
    data class ResolvedConfig(
        val systemPrompt: String,
        val modelId: String,
        val temperature: Double,
        val topP: Double,
        val maxTokens: Int,
        val ragConfig: AgentRagConfig?,
        val retrievalConfig: AgentRetrievalConfig?
    )

    fun resolve(agent: Agent?): ResolvedConfig {
        return ResolvedConfig(
            systemPrompt = agent?.systemPrompt ?: "",
            modelId = agent?.modelId ?: globalPrefs.getString("default_model", "") ?: "",
            temperature = agent?.temperature ?: globalPrefs.getFloat("default_temperature", 0.7f).toDouble(),
            topP = agent?.topP ?: globalPrefs.getFloat("default_top_p", 0.9f).toDouble(),
            maxTokens = agent?.maxTokens ?: globalPrefs.getInt("default_max_tokens", 4096),
            ragConfig = agent?.ragConfig,
            retrievalConfig = agent?.retrievalConfig
        )
    }

    fun resolveName(agent: Agent?): String = agent?.name ?: ""
}

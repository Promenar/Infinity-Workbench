package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.*

/** 模型选择只解析稳定 ID；路由不可用时由运行器结束任务，不寻找其它模型。 */
object SubagentModelSelection {
    data class Selection(val modelId: String, val chosenByAgent: Boolean)

    fun resolve(model: String?, policy: AgentSubagentModelPolicy, sessionModel: String?): Selection {
        model?.trim()?.takeIf(String::isNotEmpty)?.let { return Selection(it, true) }
        policy.defaultModelId?.trim()?.takeIf(String::isNotEmpty)?.let { return Selection(it, false) }
        return Selection(sessionModel?.trim()?.takeIf(String::isNotEmpty)
            ?: throw SubagentModelUnavailable("会话没有可用模型"), false)
    }

    /** 参数错误必须在派生任何任务前返回，避免部分批次启动。 */
    fun validationError(
        specs: List<SubagentSpec>,
        policy: AgentSubagentModelPolicy,
        supportsTools: (String) -> Boolean,
    ): String? {
        specs.forEachIndexed { index, spec ->
            val model = spec.model?.trim()?.takeIf(String::isNotEmpty) ?: return@forEachIndexed
            val task = "Task ${index + 1}"
            if (!policy.allowPerTaskModel) return "$task: model selection is disabled for this agent. Omit \"model\" to use the default subagent model."
            if (model !in policy.selectableModelIds) return "$task: model \"$model\" is not in this agent's allowed list: ${policy.selectableModelIds.joinToString(", ").ifEmpty { "(none)" }}."
            if (!supportsTools(model)) return "$task: model \"$model\" does not support tool calling. Choose another allowed model or omit \"model\"."
        }
        return null
    }

    /** 与模型选择器使用同一目录和覆盖优先级；未知能力仍允许调用。 */
    fun supportsToolCalling(
        modelId: String,
        models: List<ModelInfo>,
        resolver: ModelMetadataResolver = ModelCatalogRuntime.resolver,
    ): Boolean {
        val model = models.firstOrNull { it.id == modelId } ?: return true
        val legacySupport = "toolcalling" in model.capabilities.map { it.lowercase() }
        val capability = ModelCapability.TOOL_CALLING
        val resolved = resolver.resolve(
            remoteModelId = model.remoteModelId,
            providerId = model.providerId.orEmpty(),
            sourceProviderId = model.sourceProviderId,
            ownedBy = model.providerOwnedBy,
            providerMetadata = model.providerMetadata ?: ModelMetadataOverride(
                capabilities = if (legacySupport) mapOf(capability to SupportState.SUPPORTED) else emptyMap(),
            ),
            userOverride = if ("capabilities" in model.userEditedFields) ModelMetadataOverride(
                capabilities = mapOf(capability to if (legacySupport) SupportState.SUPPORTED else SupportState.UNSUPPORTED),
            ) else null,
        )
        return resolved.capabilities[capability] != SupportState.UNSUPPORTED
    }
}

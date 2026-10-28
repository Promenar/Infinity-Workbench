package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.SupportState
import com.promenar.nexara.data.skills.SkillStatus
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.ui.skills.SkillCatalogUiState

/** 工具关闭后本会话还剩什么：只保留 Web 搜索，或不调用任何工具（S2 页脚）。 */
internal enum class ToolsOffState { WebSearchOnly, NoTools }

/** 工具开启时为 null；模型不支持工具调用时“保留联网搜索”不起作用。 */
internal fun toolsOffState(options: SessionOptions, toolCallingSupported: Boolean): ToolsOffState? = when {
    options.toolsEnabled -> null
    toolCallingSupported && options.keepWebSearchWhenToolsOff -> ToolsOffState.WebSearchOnly
    else -> ToolsOffState.NoTools
}

/** 只有模型目录明确标为不支持工具调用时才算不支持；未知时按支持处理，不隐藏子项（S1）。 */
internal fun modelSupportsToolCalling(modelId: String?, models: List<ModelInfo>): Boolean {
    val model = models.firstOrNull { it.id == modelId } ?: return true
    return model.toModelSelectionUiModel().capabilityStates[ModelCapability.TOOL_CALLING] != SupportState.UNSUPPORTED
}

/** 会话工具页的技能只读摘要（S3）：已启用的用户技能数，以及当前工作区的项目技能数。 */
internal data class SessionSkillsSummary(
    val enabledUserSkills: Int,
    /** 会话没有目录型工作区时为 null，不显示项目技能。 */
    val projectSkills: Int?,
    val workspaceName: String?,
)

/** 首次扫描完成前返回 null，摘要行暂不显示。 */
internal fun sessionSkillsSummary(state: SkillCatalogUiState): SessionSkillsSummary? {
    val snapshot = state.snapshot ?: return if (state.loading) null else SessionSkillsSummary(0, null, null)
    return SessionSkillsSummary(
        enabledUserSkills = snapshot.user.count { it.status == SkillStatus.ENABLED },
        projectSkills = if (state.projectAvailable) snapshot.project.size else null,
        workspaceName = snapshot.workspace?.root?.name?.takeIf { state.projectAvailable },
    )
}

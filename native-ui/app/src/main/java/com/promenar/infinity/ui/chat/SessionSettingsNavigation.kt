package com.promenar.infinity.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会话设置面板与可达范围面板跳往全局设置的去向，由会话页所在的导航提供。
 * 预览与测试中为 null，相应的行显示为不可跳转。
 */
class SessionSettingsNavigation(
    /** 全局“技能包”页（S3）。 */
    val openSkills: () -> Unit,
    /** 全局“网络访问”页（R3）。 */
    val openNetworkAccess: () -> Unit,
    /** 当前会话所属智能体的编辑页（S7）。 */
    val openAgentEdit: (agentId: String) -> Unit,
)

val LocalSessionSettingsNavigation = staticCompositionLocalOf<SessionSettingsNavigation?> { null }

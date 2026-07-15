package com.promenar.nexara.ui.chat.components

import com.promenar.nexara.R
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.ResolvedStatus
import com.promenar.nexara.ui.common.status.UiStatusNotice

/**
 * 资源管理器加载错误的结构化状态码（F-5）。稳定、不随 locale 变化。
 * 展示文案由 [template] 映射到 stringResource；底层异常文本仅进入 [UiStatusNotice.technical]。
 */
object ResourceExplorerNotice {
    const val CODE_WORKSPACE_LOAD_FAILED = "resource_explorer.workspace_load_failed"
    const val CODE_WORKSPACE_NOT_READY = "resource_explorer.workspace_not_ready"

    fun workspaceLoadFailed(technical: String?): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Error,
        code = CODE_WORKSPACE_LOAD_FAILED,
        technical = technical,
    )

    fun workspaceNotReady(): UiStatusNotice = UiStatusNotice(
        severity = NoticeSeverity.Error,
        code = CODE_WORKSPACE_NOT_READY,
    )

    /**
     * 纯函数：将 [notice] 映射到展示用的 string resource 模板。
     * 未知 code 返回 null，调用方须显示安全通用文案，不得泄漏 code/裸串。
     */
    fun template(notice: UiStatusNotice): ResolvedStatus? = when (notice.code) {
        CODE_WORKSPACE_LOAD_FAILED -> ResolvedStatus(R.string.resource_explorer_error_workspace_load_failed)
        CODE_WORKSPACE_NOT_READY -> ResolvedStatus(R.string.resource_explorer_error_workspace_not_ready)
        else -> null
    }
}

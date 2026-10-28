package com.promenar.infinity.ui.chat

/** 顶栏状态行的收缩档位：先收起 Git 分支名，再把 Git 收成图标，最后运行模式只留图标；工作区名称始终取剩余宽度。 */
internal enum class StatusLineFit { FULL, GIT_SUFFIX, GIT_ICON, RUN_ICON }

/** 状态行各段在各档的宽度（像素，含图标与间距）；不显示的段宽度为 0。 */
internal data class StatusLineWidths(
    val runFull: Float,
    val runIcon: Float,
    val workspace: Float,
    val gitFull: Float,
    val gitSuffix: Float,
    val gitIcon: Float,
    val separator: Float,
)

/** 取第一个能让运行模式、完整工作区名称与 Git 同时放下的档位；都放不下时运行模式收成图标，工作区名称从末尾省略。 */
internal fun statusLineFit(available: Float, widths: StatusLineWidths): StatusLineFit {
    fun fits(run: Float, git: Float): Boolean {
        val segments = listOf(run, widths.workspace, git).count { it > 0f }
        return run + widths.workspace + git + widths.separator * (segments - 1).coerceAtLeast(0) <= available
    }
    return when {
        fits(widths.runFull, widths.gitFull) -> StatusLineFit.FULL
        widths.gitFull > 0f && fits(widths.runFull, widths.gitSuffix) -> StatusLineFit.GIT_SUFFIX
        widths.gitFull > 0f && fits(widths.runFull, widths.gitIcon) -> StatusLineFit.GIT_ICON
        else -> StatusLineFit.RUN_ICON
    }
}

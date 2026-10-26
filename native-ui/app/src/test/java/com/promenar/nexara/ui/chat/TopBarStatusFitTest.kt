package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TopBarStatusFitTest {
    private val widths = StatusLineWidths(
        runFull = 50f,
        runIcon = 14f,
        workspace = 80f,
        gitFull = 100f,
        gitSuffix = 66f,
        gitIcon = 13f,
        separator = 14f,
    )

    @Test
    fun `放得下时全部完整显示`() {
        assertThat(statusLineFit(available = 260f, widths = widths)).isEqualTo(StatusLineFit.FULL)
    }

    @Test
    fun `空间不足时先去掉分支名，再把 Git 收成图标`() {
        assertThat(statusLineFit(available = 230f, widths = widths)).isEqualTo(StatusLineFit.GIT_SUFFIX)
        assertThat(statusLineFit(available = 180f, widths = widths)).isEqualTo(StatusLineFit.GIT_ICON)
    }

    @Test
    fun `仍放不下时运行模式只留图标，工作区名称取剩余宽度`() {
        assertThat(statusLineFit(available = 120f, widths = widths)).isEqualTo(StatusLineFit.RUN_ICON)
    }

    @Test
    fun `没有 Git 时不经过 Git 收缩档`() {
        val noGit = widths.copy(gitFull = 0f, gitSuffix = 0f, gitIcon = 0f)
        assertThat(statusLineFit(available = 150f, widths = noGit)).isEqualTo(StatusLineFit.FULL)
        assertThat(statusLineFit(available = 130f, widths = noGit)).isEqualTo(StatusLineFit.RUN_ICON)
    }

    @Test
    fun `分隔符只计入相邻两段之间`() {
        val onlyWorkspace = widths.copy(runFull = 0f, runIcon = 0f, gitFull = 0f, gitSuffix = 0f, gitIcon = 0f)
        assertThat(statusLineFit(available = 80f, widths = onlyWorkspace)).isEqualTo(StatusLineFit.FULL)
    }
}

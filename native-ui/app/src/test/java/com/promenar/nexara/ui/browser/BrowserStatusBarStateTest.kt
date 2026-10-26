package com.promenar.nexara.ui.browser

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.browser.BrowserActionKind
import com.promenar.nexara.data.browser.BrowserAgentAction
import com.promenar.nexara.data.browser.BrowserMode
import com.promenar.nexara.data.browser.BrowserUiState
import org.junit.Test

class BrowserStatusBarStateTest {
    private val agent = BrowserUiState(url = "https://example.com/", mode = BrowserMode.AGENT, action = BrowserAgentAction(BrowserActionKind.OPEN))

    @Test fun `操作中直接显示当前状态`() {
        assertThat(browserStatusBarState(agent, previous = null)).isEqualTo(agent)
    }

    @Test fun `转为空闲后退场期间沿用退场前的状态，不落入交接文案`() {
        val idle = agent.copy(mode = BrowserMode.IDLE, action = null)
        assertThat(browserStatusBarState(idle, previous = agent)).isEqualTo(agent)
    }

    @Test fun `从未操作过的空闲页面不显示状态条`() {
        assertThat(browserStatusBarState(agent.copy(mode = BrowserMode.IDLE), previous = null)).isNull()
    }
}

package com.promenar.nexara.ui

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.navigation.HUB_PANE_EMPTY
import com.promenar.nexara.navigation.NavDestinations
import org.junit.jupiter.api.Test

class MainTabsStateHubTest {
    private fun state() = MainTabsState(AppTab.CHAT, emptyList(), NavDestinations.THEME_CONFIG)
    private val chat = NavDestinations.chatHero("s1")
    private val subagent = "subagent/s1/step/0"

    @Test
    fun `会话列有会话时展开按钮在三列与展开之间切换，没有会话时保持三列`() {
        val state = state()
        state.toggleHubExpanded()
        assertThat(state.hubExpanded).isFalse()

        state.updateHubStack(listOf(chat))
        assertThat(state.hubSessionId).isEqualTo("s1")
        state.toggleHubExpanded()
        assertThat(state.hubExpanded).isTrue()
        state.toggleHubExpanded()
        assertThat(state.hubExpanded).isFalse()
    }

    @Test
    fun `系统返回先收起窄列表，再还原三列，三列时交给首页原有处理`() {
        val state = state()
        state.updateHubStack(listOf(chat))
        state.toggleHubExpanded()
        state.hubDrawerOpen = true

        assertThat(state.handleHubBack()).isTrue()
        assertThat(state.hubDrawerOpen).isFalse()
        assertThat(state.hubExpanded).isTrue()
        assertThat(state.handleHubBack()).isTrue()
        assertThat(state.hubExpanded).isFalse()
        assertThat(state.handleHubBack()).isFalse()
        assertThat(state.hubStack).containsExactly(chat)
    }

    @Test
    fun `展开状态下切到资料或设置先还原三列，切回对话不改变`() {
        val state = state()
        state.updateHubStack(listOf(chat))
        state.toggleHubExpanded()
        state.selectTab(AppTab.CHAT)
        assertThat(state.hubExpanded).isTrue()

        state.hubDrawerOpen = true
        state.selectTab(AppTab.SETTINGS)
        assertThat(state.selectedTab).isEqualTo(AppTab.SETTINGS)
        assertThat(state.hubExpanded).isFalse()
        assertThat(state.hubDrawerOpen).isFalse()
        assertThat(state.hubStack).containsExactly(chat)
    }

    @Test
    fun `会话列回到新建入口时不计入层级，也不保留展开`() {
        val state = state()
        state.updateHubStack(listOf(chat, subagent))
        state.toggleHubExpanded()
        assertThat(state.hubSessionId).isEqualTo("s1")

        state.updateHubStack(listOf(HUB_PANE_EMPTY))
        assertThat(state.hubStack).isEmpty()
        assertThat(state.hubSessionId).isNull()
        assertThat(state.hubExpanded).isFalse()
    }

    @Test
    fun `合屏交出会话列层级并结束展开，开屏收回时以三列显示`() {
        val state = state()
        state.updateHubStack(listOf(chat, subagent))
        state.toggleHubExpanded()

        assertThat(state.takeHubStack()).containsExactly(chat, subagent).inOrder()
        assertThat(state.hubStack).isEmpty()
        assertThat(state.hubExpanded).isFalse()

        state.restoreHubStack(listOf(chat))
        assertThat(state.hubSessionId).isEqualTo("s1")
        assertThat(state.hubExpanded).isFalse()
    }

    @Test
    fun `跨 Activity 重建保存页签、设置右列、会话列层级与展开状态`() {
        val state = MainTabsState(AppTab.CHAT, listOf("skills_config"), "skills_config")
        state.updateHubStack(listOf(chat, subagent))
        state.toggleHubExpanded()

        val saved = with(MainTabsState.Saver) { SaverScope { true }.save(state) }!!
        val restored = MainTabsState.Saver.restore(saved)!!
        assertThat(restored.selectedTab).isEqualTo(AppTab.CHAT)
        assertThat(restored.settingsStack).containsExactly("skills_config")
        assertThat(restored.settingsLastRoot).isEqualTo("skills_config")
        assertThat(restored.hubStack).containsExactly(chat, subagent).inOrder()
        assertThat(restored.hubExpanded).isTrue()
    }
}

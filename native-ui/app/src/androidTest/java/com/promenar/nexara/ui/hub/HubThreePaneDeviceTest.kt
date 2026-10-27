package com.promenar.nexara.ui.hub

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.composable
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.ui.AppTab
import com.promenar.nexara.ui.MainTabsState
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 首页会话列的首次组合：开合屏迁移进来的层级要在会话列的图建立后压入，不能被起始占位页覆盖。 */
class HubThreePaneDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun setPane(state: MainTabsState) {
        val host = HubThreePaneHost(state, Animatable(0f), HubNavigationRailWidth) {
            composable(NavDestinations.CHAT_HERO) { entry -> Text("会话 ${entry.arguments?.getString("sessionId")}") }
            composable(NavDestinations.SUBAGENT_VIEWER_ROUTE) { Text("子代理") }
        }
        rule.setContent {
            NexaraTheme {
                HubThreePane(
                    host = host,
                    strip = emptyList(),
                    activity = emptyMap(),
                    empty = { Text("新建入口") },
                    list = { _, _ -> Text("列表") },
                )
            }
        }
    }

    @Test
    fun migratedSessionOpensInPane() {
        val state = MainTabsState(AppTab.CHAT, emptyList(), "", hubStack = listOf("chat_hero/s1"))
        setPane(state)

        rule.onNodeWithText("会话 s1").assertExists()
        rule.onNodeWithText("新建入口").assertDoesNotExist()
        rule.runOnIdle {
            assertThat(state.hubStack).containsExactly("chat_hero/s1")
            assertThat(state.hubSessionId).isEqualTo("s1")
        }
    }

    @Test
    fun migratedSubagentPageStaysAboveSession() {
        val state = MainTabsState(AppTab.CHAT, emptyList(), "", hubStack = listOf("chat_hero/s1", "subagent/s1/step/2"))
        setPane(state)

        rule.onNodeWithText("子代理").assertExists()
        rule.runOnIdle {
            assertThat(state.hubStack).containsExactly("chat_hero/s1", "subagent/s1/step/2").inOrder()
        }
    }

    @Test
    fun emptyPaneShowsNewChatEntry() {
        val state = MainTabsState(AppTab.CHAT, emptyList(), "")
        setPane(state)

        rule.onNodeWithText("新建入口").assertExists()
        rule.runOnIdle { assertThat(state.hubStack).isEmpty() }
    }
}

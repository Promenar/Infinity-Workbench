package com.promenar.infinity.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HubPaneTest {
    private val chat = NavDestinations.chatHero("s1")
    private val subagent = "subagent/s1/step/2"

    @Test
    fun `会话列只承载会话与子代理查看页，根层必须是会话`() {
        assertThat(isHubPaneRoute(chat)).isTrue()
        assertThat(isHubPaneRoute(subagent)).isTrue()
        assertThat(isHubPaneRoute(NavDestinations.DEFAULT_MODELS)).isFalse()
        assertThat(isHubPaneRoute(NavDestinations.KNOWLEDGE_GRAPH)).isFalse()
        assertThat(isHubPaneRoute("command_output/s1/m/t")).isFalse()

        assertThat(isHubPaneRootRoute(chat)).isTrue()
        assertThat(isHubPaneRootRoute(subagent)).isFalse()
        assertThat(isHubPaneRootRoute(HUB_PANE_EMPTY)).isFalse()
        assertThat(isHubPaneRootRoute("chat_hero/")).isFalse()

        assertThat(hubPaneSessionId(chat)).isEqualTo("s1")
        assertThat(hubPaneSessionId(subagent)).isNull()
        assertThat(hubPaneSessionId(null)).isNull()
    }

    @Test
    fun `开屏时最上面的会话连同其上的子代理页收进会话列`() {
        assertThat(hubPagesAboveMainTabs(listOf(chat))).containsExactly(chat)
        assertThat(hubPagesAboveMainTabs(listOf(chat, subagent))).containsExactly(chat, subagent).inOrder()
        // 会话里打开过别的会话：会话列只放一个会话，取最上面的。
        val branched = NavDestinations.chatHero("s2")
        assertThat(hubPagesAboveMainTabs(listOf(chat, subagent, branched))).containsExactly(branched)
        assertThat(hubPagesAboveMainTabs(listOf(subagent))).isNull()
        assertThat(hubPagesAboveMainTabs(emptyList())).isNull()
    }

    private class FakeBackStack(vararg routes: String) : SettingsBackStack {
        val routes = routes.toMutableList()
        override val topRoute: String? get() = routes.lastOrNull()
        override val hasPrevious: Boolean get() = routes.size > 1
        override fun pop() {
            routes.removeAt(routes.lastIndex)
        }
        override fun push(route: String) {
            routes += route
        }
    }

    @Test
    fun `开屏时整页会话弹出到主标签页，返回交给会话列的层级`() {
        val stack = FakeBackStack(NavDestinations.MAIN_TAB_SCAFFOLD, chat, subagent)
        assertThat(stack.takeHubPagesAboveMainTabs()).containsExactly(chat, subagent).inOrder()
        assertThat(stack.routes).containsExactly(NavDestinations.MAIN_TAB_SCAFFOLD)
    }

    @Test
    fun `会话之上压着其它整页、或没有整页会话时返回栈保持原样`() {
        listOf(
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD),
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD, chat, NavDestinations.DEFAULT_MODELS),
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD, NavDestinations.SKILLS_CONFIG),
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD, subagent),
            listOf(chat),
        ).forEach { routes ->
            val stack = FakeBackStack(*routes.toTypedArray())
            assertThat(stack.takeHubPagesAboveMainTabs()).isNull()
            assertThat(stack.routes).containsExactlyElementsIn(routes).inOrder()
        }
    }
}

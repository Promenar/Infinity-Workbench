package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** 子代理审批的去向：当前聊天页的会话进会话流，其它会话与非聊天页仍走全局弹窗。 */
class SubagentApprovalInlineContractTest {
    private fun source(path: String) = Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/$path")).toString(Charsets.UTF_8)

    @Test
    fun `弹窗跳过正在会话流内显示的会话`() {
        val views = source("ui/chat/SubagentViews.kt")
        assertThat(views).contains("requests.firstOrNull { it.sessionId != inlineSessionId }")
    }

    @Test
    fun `只有聊天页路由把会话视为会话内审批`() {
        val activity = source("MainActivity.kt")
        assertThat(activity).contains("inlineSessionId = visibleChatSessionId")
        assertThat(activity).contains("backStackEntry?.destination?.route == com.promenar.nexara.navigation.NavDestinations.CHAT_HERO")
    }

    @Test
    fun `聊天页按会话筛选并把允许与拒绝交给审批代理`() {
        val route = source("ui/chat/ChatRoute.kt")
        assertThat(route).contains("subagentApprovals.filter { it.sessionId == sessionId }")
        assertThat(route).contains("application.subagentApprovalBroker.resolve(id, approved)")
        val screen = source("ui/chat/ChatScreen.kt")
        // 子代理审批组排在底部留白之前，运行坞跳转按待批准项数回推。
        assertThat(screen.indexOf("SubagentApprovalGroup(group")).isLessThan(screen.indexOf("item(key = \"bottom_spacer\")"))
        assertThat(screen).contains("subagentApprovalGroups.size")
    }
}

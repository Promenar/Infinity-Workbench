package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatPagesTest {
    @Test fun `会话入口以该页为根，替换已有层级`() {
        assertThat(openChatPageRoot(ChatPage.Git)).containsExactly(ChatPage.Git)
        assertThat(openChatPageRoot(ChatPage.Explorer)).containsExactly(ChatPage.Explorer)
    }

    @Test fun `资源管理器上打开 Git 叠为第二层，返回逐层弹出`() {
        val stacked = pushChatPage(openChatPageRoot(ChatPage.Explorer), ChatPage.Git)
        assertThat(stacked).containsExactly(ChatPage.Explorer, ChatPage.Git).inOrder()
        assertThat(pushChatPage(stacked, ChatPage.Git)).isEqualTo(stacked)
        assertThat(popChatPage(stacked)).containsExactly(ChatPage.Explorer)
        assertThat(popChatPage(popChatPage(stacked))).isEmpty()
    }

    @Test fun `已在层级中的页移到最上层，不重复叠放`() {
        val reordered = pushChatPage(listOf(ChatPage.Explorer, ChatPage.Git), ChatPage.Explorer)
        assertThat(reordered).containsExactly(ChatPage.Git, ChatPage.Explorer).inOrder()
    }

    @Test fun `层级可保存恢复，未知与重复片段被忽略`() {
        val pages = listOf(ChatPage.Explorer, ChatPage.Git)
        assertThat(restoreChatPages(saveChatPages(pages))).isEqualTo(pages)
        assertThat(restoreChatPages("")).isEmpty()
        assertThat(restoreChatPages("explorer,unknown,git,git")).isEqualTo(pages)
    }
}

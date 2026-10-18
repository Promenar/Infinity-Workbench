package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatItemSpacingTest {
    @Test fun `首项距顶栏十二 dp`() {
        assertThat(chatItemSpacing(null, ChatItemKind.USER).beforeDp).isEqualTo(12)
        assertThat(chatItemSpacing(null, ChatItemKind.ASSISTANT).beforeDp).isEqualTo(12)
    }

    @Test fun `用户到助手二十 dp`() {
        assertThat(chatItemSpacing(ChatItemKind.USER, ChatItemKind.ASSISTANT).beforeDp).isEqualTo(20)
    }

    @Test fun `助手到用户二十八 dp`() {
        assertThat(chatItemSpacing(ChatItemKind.ASSISTANT, ChatItemKind.USER).beforeDp).isEqualTo(28)
    }

    @Test fun `连续助手片段不增加空白`() {
        assertThat(chatItemSpacing(ChatItemKind.ASSISTANT, ChatItemKind.ASSISTANT).beforeDp).isEqualTo(0)
    }

    @Test fun `末项距输入区十六 dp且不叠加相邻项下边距`() {
        assertThat(chatItemSpacing(ChatItemKind.USER, ChatItemKind.ASSISTANT, isLast = true).afterDp).isEqualTo(16)
        assertThat(chatItemSpacing(ChatItemKind.USER, ChatItemKind.ASSISTANT).afterDp).isEqualTo(0)
    }
}

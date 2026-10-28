package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InspectorTabTransitionTest {
    @Test fun `切到底栏更靠后的页签向前，反之向后`() {
        assertThat(inspectorTabForward(ChatPage.Run, ChatPage.Browser)).isTrue()
        assertThat(inspectorTabForward(ChatPage.Canvas, ChatPage.Explorer)).isFalse()
    }

    @Test fun `文件、Git 与浏览器同属页面组，任务与画布各自成组`() {
        assertThat(ChatPage.Explorer.inspectorGroup).isEqualTo(ChatPage.Browser.inspectorGroup)
        assertThat(ChatPage.Git.inspectorGroup).isEqualTo(ChatPage.Explorer.inspectorGroup)
        assertThat(ChatPage.Run.inspectorGroup).isNotEqualTo(ChatPage.Canvas.inspectorGroup)
        assertThat(ChatPage.Run.inspectorGroup).isNotEqualTo(ChatPage.Explorer.inspectorGroup)
    }

    @Test fun `根层换页签时给出离开的页面与方向`() {
        val switch = inspectorRootSwitch(listOf(ChatPage.Explorer, ChatPage.Git), listOf(ChatPage.Browser))
        assertThat(switch).isEqualTo(InspectorRootSwitch(setOf(ChatPage.Explorer, ChatPage.Git), forward = true))
        assertThat(inspectorRootSwitch(listOf(ChatPage.Browser), listOf(ChatPage.Explorer))?.forward).isFalse()
    }

    @Test fun `推入、弹出与整体开合不算换页签`() {
        assertThat(inspectorRootSwitch(listOf(ChatPage.Explorer), listOf(ChatPage.Explorer, ChatPage.Git))).isNull()
        assertThat(inspectorRootSwitch(listOf(ChatPage.Explorer, ChatPage.Git), listOf(ChatPage.Explorer))).isNull()
        assertThat(inspectorRootSwitch(emptyList(), listOf(ChatPage.Browser))).isNull()
        assertThat(inspectorRootSwitch(listOf(ChatPage.Browser), emptyList())).isNull()
    }
}

package com.promenar.nexara.data.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import com.promenar.nexara.ui.chat.buildPipelineGroups
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 工作区切换提示只在聊天流中可见：独立成组，不进入协议消息，系统提示词改为提醒历史路径可能失效。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceSwitchNoticeTest {
    private val notice = WorkspaceSwitchNotice.create("已从“会话私有工作区”切换到“演示项目”", now = 3)
    private val messages = listOf(
        Message("u1", MessageRole.USER, "第一问", createdAt = 1),
        Message("a1", MessageRole.ASSISTANT, "第一答", createdAt = 2),
        notice,
        Message("u2", MessageRole.USER, "第二问", createdAt = 4),
    )

    @Test
    fun `提示独立成组且不并入前后的助手回复`() {
        val groups = buildPipelineGroups(messages + Message("a2", MessageRole.ASSISTANT, "第二答", createdAt = 5))
        assertThat(groups.map { it.messages.map(Message::id) })
            .containsExactly(listOf("u1"), listOf("a1"), listOf(notice.id), listOf("u2"), listOf("a2")).inOrder()
        assertThat(groups[2].isNotice).isTrue()
        assertThat(groups.filter { it.isNotice }).hasSize(1)
        assertThat(Message("s", MessageRole.SYSTEM, "其它系统消息").isWorkspaceSwitchNotice).isFalse()
    }

    @Test
    fun `协议消息跳过提示，系统提示词提醒历史路径可能属于之前的工作区`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val strategy = DefaultChatGenerationContentStrategy(
            context.getSharedPreferences("notice-${UUID.randomUUID()}", Context.MODE_PRIVATE), null,
        )
        val session = Session("s1", "agent", messages = messages)
        val protocol = strategy.buildProtocolMessages(session, "系统", "u2")
        assertThat(protocol.map { it.content }).containsExactly("系统", "第一问", "第一答", "第二问").inOrder()

        suspend fun prompt(session: Session) = ContextBuilder().buildContext(
            ContextBuilderParams("s1", "第二问", assistantMsgId = "m", session = session, availableToolNames = setOf("read_file")),
        ).finalSystemPrompt
        val switched = prompt(session)
        assertThat(switched).contains("### Current Workspace")
        assertThat(switched).contains("switched this conversation's workspace")
        assertThat(prompt(session.copy(messages = messages - notice))).doesNotContain("switched this conversation's workspace")
    }
}

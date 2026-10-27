package com.promenar.nexara.data.trigger

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.domain.trigger.SystemTriggerMessage
import com.promenar.nexara.domain.trigger.SystemTurnDelivery
import com.promenar.nexara.domain.trigger.SystemTurnKind
import com.promenar.nexara.domain.trigger.SystemTurnOutcome
import com.promenar.nexara.domain.trigger.SystemTurnRequest
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentCompletionSteeringTest {
    @Test fun `完成通知只在步骤边界持久化后回执并按入队顺序合并`() = runTest {
        val store = store()
        val messages = messages(store)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val first = bridge.enqueue(request("sa_first", "先完成"), SystemTurnAdmissionTicket())
        val second = bridge.enqueue(request("sa_second", "后完成"), SystemTurnAdmissionTicket())

        assertThat(first.outcome.isCompleted).isFalse()
        assertThat(store.getSession("s")!!.messages).isEmpty()
        val delivered = bridge.deliver("s", store, messages)!!

        assertThat(first.outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(second.outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(delivered.text.indexOf("先完成")).isLessThan(delivered.text.indexOf("后完成"))
        assertThat(SystemTriggerMessage.parse(delivered.text)!!.deliveryIds)
            .containsExactly("sa_first:1", "sa_second:1").inOrder()
        coVerify(exactly = 1) { messages.addMessage("s", any()) }
    }

    @Test fun `消息落盘后未收到outbox回执重启不会重复插入`() = runTest {
        val store = store()
        val messages = messages(store)
        val first = SubagentCompletionSteering(backgroundScope)
        first.enqueue(request("sa_same", "结论"), SystemTurnAdmissionTicket())
        first.deliver("s", store, messages)
        val restarted = SubagentCompletionSteering(backgroundScope)
        val retry = restarted.enqueue(request("sa_same", "结论"), SystemTurnAdmissionTicket())

        assertThat(restarted.deliver("s", store, messages)).isNull()
        assertThat(retry.outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(store.getSession("s")!!.messages).hasSize(1)
        coVerify(exactly = 1) { messages.addMessage("s", any()) }
    }

    @Test fun `同一子代理恢复运行的下一次结论独立送达`() = runTest {
        val store = store()
        val messages = messages(store)
        val bridge = SubagentCompletionSteering(backgroundScope)
        bridge.enqueue(request("sa_same", "首次结论"), SystemTurnAdmissionTicket())
        bridge.deliver("s", store, messages)
        val resumed = bridge.enqueue(request("sa_same", "恢复结论").copy(sequence = 2), SystemTurnAdmissionTicket())

        assertThat(bridge.deliver("s", store, messages)!!.text).contains("恢复结论")
        assertThat(resumed.outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(store.getSession("s")!!.messages).hasSize(2)
    }

    @Test fun `调用方在送达前撤回不会留下插入消息`() = runTest {
        val store = store()
        val messages = messages(store)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val ticket = SystemTurnAdmissionTicket()
        val pending = bridge.enqueue(request("sa_cancel", "撤回"), ticket)
        ticket.tryWithdraw()
        bridge.remove(pending)

        assertThat(bridge.deliver("s", store, messages)).isNull()
        assertThat(store.getSession("s")!!.messages).isEmpty()
    }

    @Test fun `消息写入失败不回执成功且outbox可重新送达`() = runTest {
        val store = store()
        val bridge = SubagentCompletionSteering(backgroundScope)
        val failing = mockk<MessageManager>(relaxed = true)
        coEvery { failing.addMessage(any(), any()) } throws IllegalStateException("持久化失败")
        val pending = bridge.enqueue(request("sa_retry", "可重试结论"), SystemTurnAdmissionTicket())

        assertThat(bridge.deliver("s", store, failing)).isNull()
        assertThat(pending.outcome.await()).isInstanceOf(SystemTurnOutcome.Failed::class.java)
        assertThat(store.getSession("s")!!.messages).isEmpty()
        bridge.remove(pending)
        val retry = bridge.enqueue(request("sa_retry", "可重试结论"), SystemTurnAdmissionTicket())
        bridge.deliver("s", store, messages(store))
        assertThat(retry.outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(store.getSession("s")!!.messages).hasSize(1)
    }

    @Test fun `消息已持久化但outbox回执写入失败仍交回已送达消息启动父轮`() = runTest {
        val store = store()
        var released = false
        val guard = object : SystemTurnDelivery {
            override suspend fun claim() = object : SystemTurnDelivery.Claim {
                override suspend fun confirm() { error("outbox写入失败") }
                override fun release() { released = true }
            }
        }
        val bridge = SubagentCompletionSteering(backgroundScope)
        val pending = bridge.enqueue(request("sa_ack", "已落盘结论").copy(delivery = guard), SystemTurnAdmissionTicket())
        assertThat(bridge.deliver("s", store, messages(store))).isNotNull()
        assertThat(pending.outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(store.getSession("s")!!.messages).hasSize(1)
        assertThat(released).isTrue()
    }

    private fun store() = ChatStore().apply { upsertSession(Session("s", "a")) }

    private fun messages(store: ChatStore) = mockk<MessageManager>(relaxed = true).also { manager ->
        coEvery { manager.addMessage(any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val message = secondArg<Message>()
            check(message.role == MessageRole.USER)
            store.updateSession(sessionId) { it.copy(messages = it.messages + message) }
        }
    }

    private fun request(id: String, body: String) =
        SystemTurnRequest("s", SystemTurnKind.SUBAGENT_DONE, id, "子代理", body, sequence = 1)
}

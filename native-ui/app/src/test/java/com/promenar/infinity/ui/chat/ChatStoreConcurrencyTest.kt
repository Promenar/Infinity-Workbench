package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatStoreConcurrencyTest {
    @Test fun `不同会话冷加载互不等待且同会话去重`() = runTest {
        val store = ChatStore()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val first = async {
            store.getOrLoadSession("a") { loads++; release.await(); Session("a", "agent") }
        }
        runCurrent()
        val duplicate = async { store.getOrLoadSession("a") { error("不应重复加载") } }
        val other = async { store.getOrLoadSession("b") { Session("b", "agent") } }
        runCurrent()
        try {
            assertThat(other.isCompleted).isTrue()
            assertThat(duplicate.isCompleted).isFalse()
        } finally { release.complete(Unit) }
        assertThat(first.await()).isSameInstanceAs(duplicate.await())
        assertThat(other.await()?.id).isEqualTo("b")
        assertThat(loads).isEqualTo(1)
    }

    @Test fun `冷加载不会覆盖期间进入内存的消息且取消后可重试`() = runTest {
        val store = ChatStore()
        val release = CompletableDeferred<Unit>()
        val loading = async { store.getOrLoadSession("a") { release.await(); Session("a", "agent") } }
        runCurrent()
        val live = Session("a", "agent", messages = listOf(Message("new", MessageRole.USER, "新消息")))
        store.upsertSession(live)
        release.complete(Unit)
        assertThat(loading.await()).isSameInstanceAs(live)
        val cancelled = async { store.getOrLoadSession("b") { CompletableDeferred<Unit>().await(); null } }
        runCurrent()
        cancelled.cancelAndJoin()
        assertThat(store.getOrLoadSession("b") { Session("b", "agent") }?.id).isEqualTo("b")
    }
}

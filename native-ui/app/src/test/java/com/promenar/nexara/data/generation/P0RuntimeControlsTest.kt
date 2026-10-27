package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationToolCall
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

class P0RuntimeControlsTest {
    @Test
    fun `重复检测对参数键顺序和空白不敏感且继续后重新计数`() {
        val detector = ConsecutiveToolLoopDetector(3)
        assertThat(detector.record(GenerationToolCall("1", "read", "{\"b\":2,\"a\":1}"))).isFalse()
        assertThat(detector.record(GenerationToolCall("2", "read", "{ \"a\":1, \"b\":2 }"))).isFalse()
        assertThat(detector.record(GenerationToolCall("3", "read", "{\"a\":1,\"b\":2}"))).isTrue()
        detector.reset()
        assertThat(detector.record(GenerationToolCall("4", "read", "{\"a\":1,\"b\":2}"))).isFalse()
        assertThat(detector.record(GenerationToolCall("5", "other", "{}"))).isFalse()
        assertThat(detector.record(GenerationToolCall("6", "read", "{}"))).isFalse()
    }

    @Test
    fun `循环继续只完成当前任务的交互且取消清理等待状态`() = runTest {
        val interactions = GenerationInteractionStore()
        val waiting = async { interactions.awaitDecision("s", GenerationInteractionKind.REPEATED_TOOL) }
        yield()
        val prompt = interactions.pending.value!!
        assertThat(interactions.respond("stale", true)).isFalse()
        assertThat(interactions.respond(prompt.id, true)).isTrue()
        assertThat(waiting.await()).isTrue()
        assertThat(interactions.pending.value).isNull()
        val cancelled = async { interactions.awaitDecision("s", GenerationInteractionKind.UNLIMITED_AUTO) }
        yield()
        cancelled.cancel()
        cancelled.join()
        assertThat(interactions.pending.value).isNull()
    }

    @Test
    fun `两个会话交错确认并切换页面时互不覆盖`() = runTest {
        val interactions = GenerationInteractionStore()
        val first = async { interactions.awaitDecision("first", GenerationInteractionKind.REPEATED_TOOL) }
        val second = async { interactions.awaitDecision("second", GenerationInteractionKind.UNLIMITED_AUTO) }
        runCurrent()
        val firstPrompt = interactions.pendingBySession.value.getValue("first")
        val secondPrompt = interactions.pendingBySession.value.getValue("second")
        interactions.selectSession("second")
        assertThat(interactions.pending.value).isEqualTo(secondPrompt)
        assertThat(interactions.respond(firstPrompt.id, false)).isTrue()
        assertThat(first.await()).isFalse()
        assertThat(interactions.pending.value).isEqualTo(secondPrompt)
        interactions.selectSession("first")
        assertThat(interactions.pending.value).isNull()
        interactions.selectSession("second")
        assertThat(interactions.respond(secondPrompt.id, true)).isTrue()
        assertThat(second.await()).isTrue()
        assertThat(interactions.pendingBySession.value).isEmpty()
    }
}

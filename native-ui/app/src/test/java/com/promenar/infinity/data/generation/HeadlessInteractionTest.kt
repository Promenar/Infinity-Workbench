package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Test

class HeadlessInteractionTest {
    private class Persistence : HeadlessInteractionPersistence {
        var value: GenerationInteraction? = null
        override suspend fun read() = value
        override suspend fun write(interaction: GenerationInteraction?) { value = interaction }
    }

    @Test fun `多个无界面确认持久化并独立清除`() = runTest {
        var disk = emptyList<GenerationInteraction>()
        val persistence = object : HeadlessInteractionPersistence {
            override suspend fun read() = disk.firstOrNull()
            override suspend fun write(interaction: GenerationInteraction?) { disk = listOfNotNull(interaction) }
            override suspend fun readAll() = disk
            override suspend fun writeAll(interactions: List<GenerationInteraction>) { disk = interactions }
        }
        val store = GenerationInteractionStore(persistence)
        for (id in listOf("first", "second")) {
            val failure = runCatching {
                store.awaitDecision(id, GenerationInteractionKind.REPEATED_TOOL, GenerationInteractionMode.HEADLESS)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(HeadlessInteractionRequired::class.java)
        }
        val restored = GenerationInteractionStore(persistence)
        restored.restoreHeadless()
        assertThat(restored.pendingBySession.value.keys).containsExactly("first", "second")
        restored.clearHeadless("first")
        assertThat(disk.map { it.sessionId }).containsExactly("second")
        assertThat(restored.pendingBySession.value.keys).containsExactly("second")
    }
    @Test fun `无界面确认冷重建后与其它会话的前台确认隔离`() = runTest {
        val persistence = Persistence()
        val first = GenerationInteractionStore(persistence)
        val failure = runCatching {
            first.awaitDecision("s", GenerationInteractionKind.UNLIMITED_AUTO, GenerationInteractionMode.HEADLESS)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(HeadlessInteractionRequired::class.java)
        assertThat(first.pending.value).isEqualTo(persistence.value)
        val restored = GenerationInteractionStore(persistence)
        restored.restoreHeadless()
        val prompt = restored.pending.value!!
        val other = async { restored.awaitDecision("other", GenerationInteractionKind.REPEATED_TOOL) }
        yield()
        assertThat(restored.pending.value).isEqualTo(prompt)
        val otherPrompt = restored.pendingBySession.value.getValue("other")
        assertThat(restored.respond(otherPrompt.id, true)).isTrue()
        assertThat(other.await()).isTrue()
        var responses = 0
        restored.onHeadlessDecision = { received, allowed -> assertThat(received).isEqualTo(prompt); assertThat(allowed).isTrue(); responses++ }
        assertThat(restored.respond(prompt.id, true)).isTrue()
        assertThat(restored.respond(prompt.id, true)).isFalse()
        assertThat(responses).isEqualTo(1)
        restored.clearHeadless("s")
        assertThat(persistence.value).isNull()
        assertThat(restored.pending.value).isNull()
    }

    @Test fun `确认写盘后返回前取消不能留下冷恢复幽灵确认`() = runTest {
        var disk: GenerationInteraction? = null
        val persistence = object : HeadlessInteractionPersistence {
            override suspend fun read() = disk
            override suspend fun write(interaction: GenerationInteraction?) {
                disk = interaction
                if (interaction != null) throw kotlinx.coroutines.CancellationException("写盘后取消")
            }
        }
        val interactions = GenerationInteractionStore(persistence)
        runCatching { interactions.awaitDecision("s", GenerationInteractionKind.REPEATED_TOOL, GenerationInteractionMode.HEADLESS) }
        assertThat(disk).isNull()
        assertThat(interactions.pending.value).isNull()
    }

    @Test fun `runner将无界面确认写成等待用户并在返回前释放租约`() = runTest {
        val runtime = mockk<ChatGenerationRuntime>(relaxed = true)
        val interactions = GenerationInteractionStore()
        coEvery { runtime.prepare(any()) } coAnswers {
            interactions.awaitDecision("s", GenerationInteractionKind.UNLIMITED_AUTO, GenerationInteractionMode.HEADLESS)
            Unit
        }
        val events = mutableListOf<GenerationEvent>()
        val request = GenerationRequest("s", "a", "u", "检查", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            interactionMode = GenerationInteractionMode.HEADLESS)
        ChatGenerationRunner(runtime).run(request, events::add)
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().last().phase).isEqualTo(GenerationPhase.WAITING_APPROVAL)
        assertThat(events.filterIsInstance<GenerationEvent.Failed>()).isEmpty()
        coVerify(exactly = 1) { runtime.pauseForUser(request, any()) }
        coVerify { runtime.flush(request); runtime.releaseToolResources(request, true) }
        coVerify(exactly = 0) { runtime.markTerminal(any(), any(), any(), any()) }
    }
}

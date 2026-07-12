package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenerationPresentationStoreTest {
    @Test
    fun `A生成时导航B后A更新不污染B且返回A可恢复`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        store.begin("B", "task-B")
        val aPort = store.port("A", "task-A")

        aPort.setStreamingContent("partial-A")
        store.accept("A", "task-A", GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "final-A")))

        assertThat(store.observe("B").value?.streamingContent).isEmpty()
        assertThat(store.observe("B").value?.error).isNull()
        assertThat(store.observe("A").value?.streamingContent).isEqualTo("final-A")
    }

    @Test
    fun `旧task事件被隔离且finish清理entry`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "old")
        val oldPort = store.port("A", "old")
        oldPort.setStreamingContent("old")
        store.finish("A", "old")

        assertThat(store.observe("A").value).isNull()

        store.begin("A", "new")
        val newPort = store.port("A", "new")
        oldPort.setStreamingContent("stale")
        store.accept("A", "old", GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "stale-event")))
        newPort.setStreamingContent("fresh")

        assertThat(store.observe("A").value?.taskId).isEqualTo("new")
        assertThat(store.observe("A").value?.streamingContent).isEqualTo("fresh")
    }
}

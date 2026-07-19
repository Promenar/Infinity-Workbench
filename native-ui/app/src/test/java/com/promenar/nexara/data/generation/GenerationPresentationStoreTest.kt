package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenerationPresentationStoreTest {
    @Test
    fun `失败事件只保存结构化failure且保留诊断引用`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        val cause = IllegalStateException("provider raw")
        val failSnapshot = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "37"),
            technical = "HTTP 429",
            cause = cause,
        )

        store.accept("A", "task-A", GenerationEvent.Failed(failSnapshot))

        assertThat(store.observe("A").value?.error).isSameInstanceAs(failSnapshot)
        assertThat(store.observe("A").value?.phase)
            .isEqualTo(com.promenar.nexara.domain.generation.GenerationPhase.FAILED)
        assertThat(store.observe("A").value?.generating).isFalse()
        assertThat(store.observe("A").value?.error?.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(store.observe("A").value?.error?.retryAfterSeconds).isEqualTo(37)
        assertThat(store.observe("A").value?.error?.cause).isSameInstanceAs(cause)
        assertThat(store.observe("A").value?.error?.technical).isEqualTo("HTTP 429")
    }

    @Test
    fun `结构化失败事件逐类落地并按任务隔离`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task-A")
        val snapshotFailure = GenerationFailure.unknown(technical = "snapshot")
        val rejectedFailure = GenerationFailure(GenerationFailureCode.AUTH, technical = "rejected")
        val persistenceFailure = GenerationFailure.persistence(technical = "persistence")
        val failedFailure = GenerationFailure(GenerationFailureCode.NETWORK, technical = "failed")

        store.accept(
            "A",
            "task-A",
            GenerationEvent.SnapshotChanged(GenerationSnapshot(content = "content", failure = snapshotFailure)),
        )
        assertThat(store.observe("A").value?.error).isSameInstanceAs(snapshotFailure)
        store.accept(
            "A",
            "task-A",
            GenerationEvent.Rejected(rejectedFailure),
        )
        assertThat(store.observe("A").value?.error).isSameInstanceAs(rejectedFailure)
        assertThat(store.observe("A").value?.phase).isEqualTo(com.promenar.nexara.domain.generation.GenerationPhase.FAILED)
        store.accept(
            "A",
            "task-A",
            GenerationEvent.PersistenceFailed(
                persistenceCause = RuntimeException("io"),
                originalCause = null,
                failure = persistenceFailure,
            ),
        )
        assertThat(store.observe("A").value?.error).isSameInstanceAs(persistenceFailure)
        assertThat(store.observe("A").value?.phase)
            .isEqualTo(com.promenar.nexara.domain.generation.GenerationPhase.PERSISTENCE_FAILED)
        store.accept("A", "task-A", GenerationEvent.Failed(failedFailure))
        assertThat(store.observe("A").value?.error).isSameInstanceAs(failedFailure)

        store.begin("B", "task-B")
        val uiFailure = GenerationFailure(
            code = GenerationFailureCode.TIMEOUT,
            technical = "ui",
        )
        store.port("B", "task-B").setError(uiFailure)
        assertThat(store.observe("B").value?.error?.technical).isEqualTo("ui")
        assertThat(store.observe("B").value?.error?.code).isEqualTo(GenerationFailureCode.TIMEOUT)
        assertThat(store.observe("A").value?.error?.code).isEqualTo(GenerationFailureCode.NETWORK)
    }

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

    @Test
    fun `同会话终态后再次生成必须复用原订阅flow`() = runTest {
        val store = GenerationPresentationStore()
        val observed = store.observe("A")

        store.begin("A", "first")
        store.finish("A", "first")
        store.begin("A", "second")
        store.port("A", "second").setStreamingContent("second turn")

        assertThat(store.observe("A")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isEqualTo("second")
        assertThat(observed.value?.streamingContent).isEqualTo("second turn")
    }

    @Test
    fun `旧页面基于空快照释放时不得移除并发开始的新任务`() = runTest {
        val store = GenerationPresentationStore()
        val observed = store.observe("A")
        assertThat(observed.value).isNull()

        store.release("A")
        store.begin("A", "new-task")
        store.port("A", "new-task").setStreamingContent("visible")

        assertThat(store.observe("A")).isSameInstanceAs(observed)
        assertThat(observed.value?.taskId).isEqualTo("new-task")
        assertThat(observed.value?.streamingContent).isEqualTo("visible")
    }
}

package com.promenar.nexara.ui.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MediaPlaybackCoordinatorTest {
    private val coordinator = MediaPlaybackCoordinator()
    private val paused = mutableListOf<String>()

    private fun register(id: String) = coordinator.register(id) { paused += id; coordinator.onStopped(id) }

    @Test
    fun `开始新的播放时暂停前一个，同一时刻只有一个活动播放器`() {
        register("a"); register("b"); register("c")

        coordinator.onStarted("a")
        coordinator.onStarted("b")
        assertThat(paused).containsExactly("a")
        assertThat(coordinator.activeId.value).isEqualTo("b")

        coordinator.onStarted("c")
        assertThat(paused).containsExactly("a", "b").inOrder()
        assertThat(coordinator.activeId.value).isEqualTo("c")
    }

    @Test
    fun `同一播放器重复开始不暂停自身`() {
        register("a")
        coordinator.onStarted("a")
        coordinator.onStarted("a")
        assertThat(paused).isEmpty()
        assertThat(coordinator.activeId.value).isEqualTo("a")
    }

    @Test
    fun `停止、注销与全部暂停都清除活动状态`() {
        register("a"); register("b")
        coordinator.onStarted("a")
        coordinator.onStopped("b")
        assertThat(coordinator.activeId.value).isEqualTo("a")
        coordinator.onStopped("a")
        assertThat(coordinator.activeId.value).isNull()

        coordinator.onStarted("b")
        coordinator.unregister("b")
        assertThat(coordinator.activeId.value).isNull()
        assertThat(coordinator.isRegistered("b")).isFalse()

        coordinator.onStarted("a")
        coordinator.pauseAll()
        assertThat(paused).containsExactly("a")
        assertThat(coordinator.activeId.value).isNull()
    }

    @Test
    fun `已注销的前一个播放器不会被回调`() {
        register("a"); register("b")
        coordinator.onStarted("a")
        coordinator.unregister("a")
        coordinator.onStarted("b")
        assertThat(paused).isEmpty()
        assertThat(coordinator.activeId.value).isEqualTo("b")
    }
}

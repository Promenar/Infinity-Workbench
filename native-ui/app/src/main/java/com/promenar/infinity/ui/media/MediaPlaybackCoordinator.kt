package com.promenar.infinity.ui.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内唯一的播放协调者：同一时刻只允许一个播放器处于播放状态。
 *
 * 播放器以 id 注册暂停回调；某个播放器开始播放时暂停上一个；播放器释放时注销。
 * 只维护状态与回调，不持有播放器对象，便于在 JVM 单元测试中验证。
 */
class MediaPlaybackCoordinator {
    private val pausers = LinkedHashMap<String, () -> Unit>()
    private val _activeId = MutableStateFlow<String?>(null)

    /** 当前正在播放的播放器 id。 */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun register(id: String, pause: () -> Unit) {
        synchronized(this) { pausers[id] = pause }
    }

    /** 注销播放器；它正在播放时清除活动状态。 */
    fun unregister(id: String) {
        synchronized(this) {
            pausers.remove(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    /** 播放器开始播放：暂停前一个播放器并记录新的活动 id。 */
    fun onStarted(id: String) {
        val previousPause = synchronized(this) {
            val previous = _activeId.value
            _activeId.value = id
            previous?.takeIf { it != id }?.let { pausers[it] }
        }
        previousPause?.invoke()
    }

    /** 播放器暂停、结束或出错。 */
    fun onStopped(id: String) {
        synchronized(this) {
            if (_activeId.value == id) _activeId.value = null
        }
    }

    /** 会话切换、应用进入后台时暂停当前播放器。 */
    fun pauseAll() {
        val pause = synchronized(this) {
            val active = _activeId.value
            _activeId.value = null
            active?.let { pausers[it] }
        }
        pause?.invoke()
    }

    fun isRegistered(id: String): Boolean = synchronized(this) { id in pausers }

    companion object {
        /** 应用进程内共享的协调者。 */
        val Shared = MediaPlaybackCoordinator()
    }
}

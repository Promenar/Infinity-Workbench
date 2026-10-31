package com.promenar.infinity.background.generation

/**
 * 前台通知更新节流：系统对单个应用的通知更新限速约 5 次/秒，超出部分直接丢弃。
 * 任务集合、阶段、排队位置、错误与子代理数量变化立即发出；仅进度计数变化时至少间隔 [minIntervalMs]，
 * 调用方在等待结束后补发最新状态，保证最后一次进度不被吞掉。
 */
internal class GenerationNotificationThrottle(private val minIntervalMs: Long = 1_000L) {
    private var lastKey: ForegroundNotificationKey? = null
    private var lastSentAt = 0L

    /** 返回 0 表示可以立即发出，否则为距下一次允许发出的毫秒数。 */
    fun delayFor(key: ForegroundNotificationKey, now: Long): Long {
        val previous = lastKey ?: return 0L
        if (previous != key) return 0L
        return (lastSentAt + minIntervalMs - now).coerceAtLeast(0L)
    }

    fun markSent(key: ForegroundNotificationKey, now: Long) {
        lastKey = key
        lastSentAt = now
    }

    fun reset() {
        lastKey = null
        lastSentAt = 0L
    }
}

/** 通知中除进度计数外的可见内容，变化即视为需要立即更新。 */
internal data class ForegroundNotificationKey(
    val tasks: List<TaskKey>,
    val subagentCount: Int,
) {
    data class TaskKey(val taskId: String, val sessionId: String, val phase: String, val queuePosition: Int?, val failed: Boolean)
}

internal fun ForegroundWorkState.notificationKey(): ForegroundNotificationKey = ForegroundNotificationKey(
    tasks = generations.map {
        ForegroundNotificationKey.TaskKey(it.taskId, it.sessionId, it.phase.name, it.queuePosition, it.error != null)
    },
    subagentCount = subagentCount,
)

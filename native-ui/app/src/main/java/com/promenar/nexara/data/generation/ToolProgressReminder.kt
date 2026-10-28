package com.promenar.nexara.data.generation

/** 仅统计面向用户的正文和已完成工具批次；时钟使用单调递增的毫秒值。 */
class ToolProgressReminder(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val toolRoundThreshold: Int = 4,
    private val silenceThresholdMillis: Long = 60_000L,
) {
    private var lastTextAt = clock()
    private var consecutiveToolRounds = 0

    fun start() {
        lastTextAt = clock()
        consecutiveToolRounds = 0
    }

    fun onUserFacingText(content: String) {
        if (content.isNotBlank()) start()
    }

    fun onToolsCompleted() {
        consecutiveToolRounds++
    }

    /** 计时条件只在已有工具轮之后生效：首轮请求前的检索、排队等待不算沉默。 */
    fun shouldRemind(finalAnswerMode: Boolean = false): Boolean = !finalAnswerMode && consecutiveToolRounds > 0 &&
        (consecutiveToolRounds >= toolRoundThreshold || clock() - lastTextAt > silenceThresholdMillis)
}

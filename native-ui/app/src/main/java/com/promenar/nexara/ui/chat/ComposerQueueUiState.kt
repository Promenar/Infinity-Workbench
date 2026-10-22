package com.promenar.nexara.ui.chat

/**
 * 当前会话的排队消息：生成进行中按发送键写下的后续消息，按顺序在本轮结束后自动发出。
 * 本轮失败、被停止、应用重启后恢复或发送时另一会话正在生成，队列进入暂停，由用户继续或逐条处理。
 */
data class ComposerQueueUiState(
    val items: List<QueuedMessageUi> = emptyList(),
    val pauseReason: ComposerQueuePauseReason? = null,
) {
    val paused: Boolean get() = pauseReason != null
}

/**
 * 一条排队消息的展示信息。[steering] 为真表示已请求插队：当前轮在下一次工具调用结束时让出，
 * 随后立即发出这条消息；没有工具边界的纯文字回复则在本轮结束后第一个发出。
 */
data class QueuedMessageUi(
    val id: String,
    val text: String,
    val imageCount: Int = 0,
    val documentNames: List<String> = emptyList(),
    val steering: Boolean = false,
)

enum class ComposerQueuePauseReason {
    /** 用户停止了本轮生成。 */
    STOPPED,

    /** 本轮生成失败（含持久化失败）。 */
    FAILED,

    /** 应用重启后从磁盘恢复的队列。 */
    RESTORED,

    /** 本轮结束时没有停留在该会话，未能接续发出。 */
    INTERRUPTED,

    /** 轮到发送时另一会话正在生成。 */
    BUSY,
}

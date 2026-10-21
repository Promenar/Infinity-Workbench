package com.promenar.nexara.ui.chat

import com.promenar.nexara.ui.common.NexaraHapticEvent

/** 聊天页一次状态快照中与状态振动相关的部分。 */
internal data class ChatHapticSnapshot(
    val sessionId: String?,
    val status: GenerationStatus,
    /** 当前待审批请求的身份；没有待审批时为空。 */
    val approvalKey: String?,
)

/**
 * 只在需要你留意的节点给出一次状态振动：出现新的审批请求、超过 [minCompletedMs] 的一轮生成完成、生成失败。
 * 流式输出、工具步骤与你主动停止都不振动；切换会话或首次进入时只记录状态，不补发历史事件。
 */
internal class ChatStatusHapticTracker(private val minCompletedMs: Long = 10_000) {
    private var last: ChatHapticSnapshot? = null
    private var activeSince: Long? = null

    fun next(snapshot: ChatHapticSnapshot, now: Long): NexaraHapticEvent? {
        val previous = last
        last = snapshot
        if (previous == null || previous.sessionId != snapshot.sessionId) {
            activeSince = if (snapshot.status.isActive && snapshot.approvalKey == null) now else null
            return null
        }
        if (snapshot.approvalKey != null) {
            // 等待审批的时间不计入生成用时，批准后重新计时。
            activeSince = null
            return if (snapshot.approvalKey != previous.approvalKey) NexaraHapticEvent.ATTENTION else null
        }
        if (snapshot.status.isActive) {
            if (activeSince == null) activeSince = now
            return null
        }
        val startedAt = activeSince
        activeSince = null
        if (snapshot.status == previous.status) return null
        return when (snapshot.status) {
            GenerationStatus.ERROR -> NexaraHapticEvent.FAILED
            GenerationStatus.COMPLETED ->
                if (startedAt != null && now - startedAt >= minCompletedMs) NexaraHapticEvent.COMPLETED else null
            else -> null
        }
    }
}

private val GenerationStatus.isActive: Boolean
    get() = this == GenerationStatus.UPLOADING || this == GenerationStatus.THINKING || this == GenerationStatus.RECEIVING

internal fun com.promenar.nexara.data.model.ApprovalRequest.hapticKey(): String =
    identityHash ?: listOfNotNull(assistantMessageId, toolName, args).joinToString("|")

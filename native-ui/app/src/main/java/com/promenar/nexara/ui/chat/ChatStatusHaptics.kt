package com.promenar.nexara.ui.chat

import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.ui.common.NexaraHapticEvent

/** 聊天页一次状态快照中与状态振动相关的部分。 */
internal data class ChatHapticSnapshot(
    val sessionId: String?,
    val status: GenerationStatus,
    /** 当前待审批请求的身份；没有待审批时为空。 */
    val approvalKey: String?,
)

/** 一个生成任务进入终态时发出一次的结果。 */
internal data class ChatGenerationOutcome(
    val sessionId: String,
    val kind: Kind,
) {
    enum class Kind { COMPLETED, FAILED, CANCELLED }
}

internal fun chatGenerationOutcomeKind(phase: GenerationPhase, handledFailure: Boolean): ChatGenerationOutcome.Kind? =
    when {
        handledFailure || phase == GenerationPhase.FAILED || phase == GenerationPhase.PERSISTENCE_FAILED ->
            ChatGenerationOutcome.Kind.FAILED
        phase == GenerationPhase.COMPLETED -> ChatGenerationOutcome.Kind.COMPLETED
        phase == GenerationPhase.CANCELLED -> ChatGenerationOutcome.Kind.CANCELLED
        else -> null
    }

/**
 * 只在需要你留意的节点给出一次状态振动：出现新的审批请求、批准后运行超过 [minCompletedMs] 的一轮完成、生成失败。
 * 流式输出、工具步骤与你主动停止都不振动；切换会话或首次进入时只记录状态，不补发历史事件。
 *
 * 快照只用于审批提醒与运行计时。终态在状态值上停留可能不足一帧（确认后立即回到空闲），
 * 完成与失败因此只以 [onOutcome] 收到的任务结果为准。
 */
internal class ChatStatusHapticTracker(private val minCompletedMs: Long = 10_000) {
    private var last: ChatHapticSnapshot? = null
    /** 本页看到过当前这一轮在运行或等待审批；进入会话前就已结束的任务不振动。 */
    private var observedRun = false
    private var runningSince: Long? = null

    fun next(snapshot: ChatHapticSnapshot, now: Long): NexaraHapticEvent? {
        val previous = last
        last = snapshot
        if (previous == null || previous.sessionId != snapshot.sessionId) {
            observedRun = snapshot.isRunning || snapshot.approvalKey != null
            runningSince = if (snapshot.isRunning) now else null
            return null
        }
        if (snapshot.approvalKey != null) {
            // 等待审批的时间不计入生成用时，批准后重新计时。
            observedRun = true
            runningSince = null
            return if (snapshot.approvalKey != previous.approvalKey) NexaraHapticEvent.ATTENTION else null
        }
        if (snapshot.isRunning) {
            observedRun = true
            if (!previous.isRunning) runningSince = now
        }
        return null
    }

    fun onOutcome(outcome: ChatGenerationOutcome, now: Long): NexaraHapticEvent? {
        if (outcome.sessionId != last?.sessionId || !observedRun) return null
        val startedAt = runningSince
        observedRun = false
        runningSince = null
        return when (outcome.kind) {
            ChatGenerationOutcome.Kind.FAILED -> NexaraHapticEvent.FAILED
            ChatGenerationOutcome.Kind.COMPLETED ->
                if (startedAt != null && now - startedAt >= minCompletedMs) NexaraHapticEvent.COMPLETED else null
            ChatGenerationOutcome.Kind.CANCELLED -> null
        }
    }
}

private val ChatHapticSnapshot.isRunning: Boolean
    get() = approvalKey == null && (
        status == GenerationStatus.UPLOADING || status == GenerationStatus.THINKING || status == GenerationStatus.RECEIVING
        )

internal fun com.promenar.nexara.data.model.ApprovalRequest.hapticKey(): String =
    identityHash ?: listOfNotNull(assistantMessageId, toolName, args).joinToString("|")

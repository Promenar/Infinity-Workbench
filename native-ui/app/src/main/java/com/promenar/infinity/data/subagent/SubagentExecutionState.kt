package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import kotlinx.serialization.Serializable

/** 父会话追加的指令；[id] 用于持久化收件箱的消费去重。 */
@Serializable
data class SubagentInstruction(
    val message: String,
    val wrapUp: Boolean = false,
    val at: Long = 0L,
    val id: String = "",
)

/** 执行上下文保存原文，独立于有界的界面事件与交回结果。 */
@Serializable
data class SubagentExecutionState(
    val messages: List<ProtocolMessage> = emptyList(),
    val pendingInstructions: List<SubagentInstruction> = emptyList(),
    val consumedInstructionIds: List<String> = emptyList(),
    val completedSteps: Int = 0,
    /** 当前执行的轮数预算起点；恢复后重开预算，累计步骤与用量保留。 */
    val executionStartStep: Int = 0,
    /** 累计步骤到达该值后，下一轮不提供工具。 */
    val wrapUpAfterStep: Int? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val tokensEstimated: Boolean = false,
)

/**
 * 应用级运行器提供持久化和收件箱。收件箱读取不应删除指令，保存时按 consumedInstructionIds 原子确认消费，
 * 并合并持久化端尚未消费的 pendingInstructions，避免覆盖执行中收到的消息。
 */
class SubagentExecution(
    val load: suspend () -> SubagentExecutionState? = { null },
    val save: suspend (SubagentExecutionState) -> Unit = {},
    val drainInbox: suspend () -> List<SubagentInstruction> = { emptyList() },
    val contextWindow: suspend (String) -> Int? = { null },
    val resume: Boolean = false,
)

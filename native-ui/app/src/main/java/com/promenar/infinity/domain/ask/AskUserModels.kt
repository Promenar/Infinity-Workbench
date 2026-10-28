package com.promenar.infinity.domain.ask

import kotlinx.serialization.Serializable

/** 提问的一个选项；推荐项由 Agent 放在第一位。 */
@Serializable
data class AskUserOption(
    val label: String,
    val description: String = "",
)

/** 一个问题；[header] 是页签上的短名，回答按它归档。 */
@Serializable
data class AskUserQuestion(
    val header: String,
    val question: String,
    val multiSelect: Boolean = false,
    val options: List<AskUserOption>,
)

/** `ask_user` 工具的参数：一次提问包含的全部问题。 */
@Serializable
data class AskUserRequest(
    val questions: List<AskUserQuestion>,
)

/**
 * 用户对一次提问的回答，原样作为工具结果交回 Agent。
 * [answers] 以问题的 header 为键、所选选项的 label 为值；[other] 为用户在输入栏写下的自由回答；
 * [skipped] 表示用户跳过了整次提问，此时其余字段为空。
 */
@Serializable
data class AskUserAnswer(
    val answers: Map<String, List<String>> = emptyMap(),
    val other: String? = null,
    val skipped: Boolean = false,
)

/** 会话中正在等待回答的提问：定位到所在助手消息与工具调用，界面据此渲染提问卡并提交回答。 */
data class PendingQuestion(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
    val request: AskUserRequest,
)

object AskUserLimits {
    const val TOOL_NAME = "ask_user"
    const val MAX_QUESTIONS = 4
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4
    const val MAX_HEADER_CHARS = 12
}

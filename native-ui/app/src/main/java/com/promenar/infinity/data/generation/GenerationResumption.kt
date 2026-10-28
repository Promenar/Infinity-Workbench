package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session

/** 审批前已落盘的助手锚点与原用户消息，页面首次打开也能恢复同一轮。 */
data class GenerationResumption(val assistant: Message, val user: Message)

fun generationResumption(session: Session): GenerationResumption? {
    val index = session.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (index < 0) return null
    val assistant = session.messages[index]
    val user = assistant.parentMessageId?.let { id -> session.messages.firstOrNull { it.id == id && it.role == MessageRole.USER } }
        ?: session.messages.take(index).lastOrNull { it.role == MessageRole.USER }
        ?: return null
    return GenerationResumption(assistant, user)
}

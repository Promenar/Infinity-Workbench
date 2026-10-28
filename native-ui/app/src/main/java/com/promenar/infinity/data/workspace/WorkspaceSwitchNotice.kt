package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import java.util.UUID

/**
 * 会话切换工作区后插入聊天流的可见提示。以带 [MESSAGE_NAME] 标记的 SYSTEM 消息保存，
 * 只供界面展示：协议消息与摘要都跳过它，模型改由系统提示词中的工作区说明得知切换。
 */
object WorkspaceSwitchNotice {
    const val MESSAGE_NAME = "workspace_switch"

    fun create(text: String, now: Long = System.currentTimeMillis()): Message = Message(
        id = "workspace_switch_${UUID.randomUUID()}",
        role = MessageRole.SYSTEM,
        content = text,
        name = MESSAGE_NAME,
        createdAt = now,
    )
}

val Message.isWorkspaceSwitchNotice: Boolean
    get() = role == MessageRole.SYSTEM && name == WorkspaceSwitchNotice.MESSAGE_NAME

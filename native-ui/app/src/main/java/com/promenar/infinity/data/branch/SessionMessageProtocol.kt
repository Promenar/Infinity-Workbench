package com.promenar.infinity.data.branch

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.domain.branch.SessionMessageEntry
import com.promenar.infinity.domain.trigger.SystemTriggerMessage
import com.promenar.infinity.domain.trigger.SystemTurnKind
import kotlinx.serialization.json.Json

/** 界面按条目渲染，合并送达仍逐条保留正文和对方身份。 */
object SessionMessageProtocol {
    fun entries(message: Message): List<SessionMessageEntry> = when (message.name) {
        SessionMessageStore.SENT_MESSAGE_NAME -> runCatching { Json.decodeFromString<SessionMessageOutbox>(message.content) }
            .getOrNull()?.takeIf { it.sourceSubagentId == null }?.let { listOf(it.entry) }.orEmpty()
        SessionMessageStore.RECORD_MESSAGE_NAME -> listOfNotNull(runCatching { Json.decodeFromString<SessionMessageEntry>(message.content) }.getOrNull())
        SystemTriggerMessage.MESSAGE_NAME -> SystemTriggerMessage.parse(message.content)
            ?.takeIf { it.kind == SystemTurnKind.SESSION_MESSAGE }?.body?.lineSequence()
            ?.mapNotNull { runCatching { Json.decodeFromString<SessionMessageEntry>(it) }.getOrNull() }?.toList().orEmpty()
        else -> emptyList()
    }
}

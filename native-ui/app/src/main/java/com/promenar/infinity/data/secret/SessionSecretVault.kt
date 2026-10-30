package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.domain.secret.SecretOutputCard
import com.promenar.infinity.domain.secret.SecretSlot
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅持有本进程内的副本，公开流与卡片都不包含保密值。 */
class SessionSecretVault {
    private class Entry(var slot: SecretSlot, var bytes: ByteArray?)
    private class Session {
        val entries = linkedMapOf<String, Entry>()
        val slots = MutableStateFlow<List<SecretSlot>>(emptyList())
        val cards = MutableStateFlow<List<SecretOutputCard>>(emptyList())
    }

    private val lock = Any()
    private val sessions = mutableMapOf<String, Session>()

    fun put(sessionId: String, name: String, label: String, source: SecretSource, value: ByteArray) = synchronized(lock) {
        require(sessionId.isNotBlank()) { "SECRET_SESSION_INVALID" }
        require(SecretIoLimits.SLOT_NAME.matches(name)) { "SECRET_SLOT_INVALID" }
        require(label.isNotBlank() && label.length <= SecretIoLimits.MAX_LABEL_CHARS) { "SECRET_LABEL_INVALID" }
        require(value.isNotEmpty()) { "SECRET_EMPTY" }
        require(value.size <= SecretIoLimits.MAX_SECRET_BYTES) { "SECRET_TOO_LARGE" }
        val copy = value.copyOf()
        val session = sessions.getOrPut(sessionId, ::Session)
        session.entries[name]?.bytes?.fill(0)
        session.entries[name] = Entry(SecretSlot(sessionId, name, label, source, value.size, SecretSlotState.AVAILABLE), copy)
        publish(session)
    }

    fun revealBytes(sessionId: String, name: String): ByteArray? = synchronized(lock) {
        sessions[sessionId]?.entries?.get(name)?.bytes?.copyOf()
    }

    /** 从已有公开参数恢复缺失描述，不恢复值，也不覆盖本进程已登记的槽位。 */
    fun rememberUnavailable(sessionId: String, name: String, label: String, source: SecretSource) = synchronized(lock) {
        require(sessionId.isNotBlank()) { "SECRET_SESSION_INVALID" }
        require(SecretIoLimits.SLOT_NAME.matches(name)) { "SECRET_SLOT_INVALID" }
        require(label.isNotBlank() && label.length <= SecretIoLimits.MAX_LABEL_CHARS) { "SECRET_LABEL_INVALID" }
        val session = sessions.getOrPut(sessionId, ::Session)
        if (name !in session.entries) {
            session.entries[name] = Entry(SecretSlot(sessionId, name, label, source, 0, SecretSlotState.DESTROYED), null)
            publish(session)
        }
    }

    /** 仅匹配完整的环境变量引用，常规系统环境变量保持由执行器处理。 */
    fun environment(sessionId: String, source: String): Map<String, String> = synchronized(lock) {
        val entries = sessions[sessionId]?.entries ?: return@synchronized emptyMap()
        val result = linkedMapOf<String, String>()
        VARIABLE_REFERENCE.findAll(source).forEach { match ->
            val name = match.groups[1]?.value ?: match.groups[2]!!.value
            val entry = entries[name] ?: return@forEach
            val bytes = entry.bytes ?: throw IllegalStateException("SECRET_SLOT_UNAVAILABLE")
            if (bytes.any { it == 0.toByte() }) throw IllegalStateException("SECRET_ENV_INVALID")
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalStateException("SECRET_ENV_INVALID")
            }
            result[name] = decoded
        }
        result
    }

    fun observeSlots(sessionId: String): Flow<List<SecretSlot>> = synchronized(lock) {
        sessions.getOrPut(sessionId, ::Session).slots.asStateFlow()
    }

    fun show(sessionId: String, assistantMessageId: String, toolCallId: String, name: String, purpose: String) = synchronized(lock) {
        require(purpose.isNotBlank() && purpose.length <= SecretIoLimits.MAX_PURPOSE_CHARS) { "SECRET_PURPOSE_INVALID" }
        require(assistantMessageId.isNotBlank() && toolCallId.isNotBlank()) { "SECRET_CARD_INVALID" }
        val session = sessions[sessionId] ?: throw IllegalStateException("SECRET_SLOT_UNAVAILABLE")
        val entry = session.entries[name]?.takeIf { it.bytes != null } ?: throw IllegalStateException("SECRET_SLOT_UNAVAILABLE")
        val card = SecretOutputCard(sessionId, assistantMessageId, toolCallId, entry.slot, purpose)
        session.cards.value = session.cards.value.filterNot {
            it.assistantMessageId == assistantMessageId && it.toolCallId == toolCallId
        } + card
    }

    fun observeCards(sessionId: String): Flow<List<SecretOutputCard>> = synchronized(lock) {
        sessions.getOrPut(sessionId, ::Session).cards.asStateFlow()
    }

    fun destroy(sessionId: String, name: String) = synchronized(lock) {
        val session = sessions[sessionId] ?: return@synchronized
        val entry = session.entries[name] ?: return@synchronized
        entry.bytes?.fill(0)
        entry.bytes = null
        entry.slot = entry.slot.copy(state = SecretSlotState.DESTROYED)
        publish(session)
    }

    fun clearSession(sessionId: String) = synchronized(lock) {
        val session = sessions.remove(sessionId) ?: return@synchronized
        session.entries.values.forEach { it.bytes?.fill(0); it.bytes = null }
        session.entries.clear()
        session.slots.value = emptyList()
        session.cards.value = emptyList()
    }

    /** 仅供遮蔽器短暂使用；调用方必须清零所有副本。 */
    internal fun redactionValues(sessionId: String): List<Pair<String, ByteArray>> = synchronized(lock) {
        sessions[sessionId]?.entries?.mapNotNull { (name, entry) -> entry.bytes?.let { name to it.copyOf() } }.orEmpty()
    }

    private fun publish(session: Session) {
        session.slots.value = session.entries.values.map { it.slot }
        session.cards.value = session.cards.value.map { card ->
            session.entries[card.slot.name]?.let { card.copy(slot = it.slot) } ?: card
        }
    }

    private companion object {
        val VARIABLE_REFERENCE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)")
    }
}

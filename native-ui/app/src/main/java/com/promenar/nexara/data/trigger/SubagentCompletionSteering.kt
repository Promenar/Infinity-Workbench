package com.promenar.nexara.data.trigger

import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.domain.trigger.SystemTriggerMessage
import com.promenar.nexara.domain.trigger.SystemTurnDelivery
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.domain.trigger.SystemTurnOutcome
import com.promenar.nexara.domain.trigger.SystemTurnRequest
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** outbox 保持持久所有权；此桥只在父轮的步骤边界消费尚未送达的通知。 */
class SubagentCompletionSteering(private val applicationScope: CoroutineScope) {
    internal class Pending(val request: SystemTurnRequest, val ticket: SystemTurnAdmissionTicket) {
        val outcome = CompletableDeferred<SystemTurnOutcome>()
    }

    internal data class Delivered(val request: SystemTurnRequest, val userMessageId: String, val text: String)

    private val lock = Any()
    private val pending = mutableMapOf<String, MutableList<Pending>>()
    private val byTicket = mutableMapOf<SystemTurnAdmissionTicket, Pending>()
    private val lanes = mutableMapOf<String, Mutex>()

    internal fun enqueue(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): Pending = synchronized(lock) {
        byTicket[ticket]?.let { return@synchronized it }
        val normalized = request.copy(deliveryIds = request.deliveryIds.ifEmpty {
            listOf("${request.sourceId}:${request.sequence ?: 0}")
        })
        Pending(normalized, ticket).also {
            pending.getOrPut(request.sessionId) { mutableListOf() }.add(it)
            byTicket[ticket] = it
        }
    }

    internal fun remove(entry: Pending) = synchronized(lock) {
        byTicket.remove(entry.ticket)
        detach(entry)
    }

    internal fun remove(ticket: SystemTurnAdmissionTicket) = synchronized(lock) {
        byTicket.remove(ticket)?.let(::detach)
    }

    private fun detach(entry: Pending) {
        pending[entry.request.sessionId]?.let { entries ->
            entries.remove(entry)
            if (entries.isEmpty()) pending.remove(entry.request.sessionId)
        }
    }

    /** 接受之后的写入与回执不可被调用方取消拆开；重复请求通过持久消息标记直接回执。 */
    internal suspend fun deliver(sessionId: String, store: ChatStore, messages: MessageManager, consumerAssistantId: String? = null): Delivered? {
        val lane = synchronized(lock) { lanes.getOrPut(sessionId) { Mutex() } }
        return lane.withLock {
            withContext(NonCancellable) {
                val entries = synchronized(lock) { pending[sessionId]?.toList().orEmpty() }
                    .filter { !it.ticket.isWithdrawn }
                if (entries.isEmpty()) return@withContext null
                val first = entries.first().request
                val selected = entries.filter { it.request.kind == first.kind &&
                    (first.kind != com.promenar.nexara.domain.trigger.SystemTurnKind.SESSION_MESSAGE || it.request.sourceId == first.sourceId) }
                    .let { if (first.kind == com.promenar.nexara.domain.trigger.SystemTurnKind.SESSION_MESSAGE) it.sortedBy { p -> p.request.sequence } else it }
                val claims = mutableListOf<SystemTurnDelivery.Claim>()
                val accepted = mutableListOf<Pending>()
                try {
                    for (entry in selected) {
                        val claim = entry.request.delivery?.claim()
                        if (entry.request.delivery != null && claim == null) {
                            entry.outcome.complete(SystemTurnOutcome.Completed)
                            synchronized(lock) { detach(entry) }
                            continue
                        }
                        if (entry.ticket.isOpen) entry.ticket.tryAccept("subagent-delivery:${entry.request.deliveryIds.first()}")
                        if (entry.ticket.isWithdrawn) {
                            claim?.release()
                            continue
                        }
                        if (claim != null) claims += claim
                        accepted += entry
                    }
                    if (accepted.isEmpty()) return@withContext null
                    val session = checkNotNull(store.getSession(sessionId)) { "完成通知的父会话不存在" }
                    val deliveredIds = session.messages.asSequence()
                        .filter { it.name == SystemTriggerMessage.MESSAGE_NAME }
                        .flatMap { SystemTriggerMessage.parse(it.content)?.deliveryIds.orEmpty().asSequence() }
                        .toMutableSet()
                    val fresh = accepted.filter { entry ->
                        val unseen = entry.request.deliveryIds.any { it !in deliveredIds }
                        deliveredIds.addAll(entry.request.deliveryIds)
                        unseen
                    }
                    val delivered = if (fresh.isEmpty()) null else {
                        val requests = fresh.map { it.request }
                        val combined = requests.first().copy(
                            title = requests.joinToString(" · ") { it.title },
                            body = if (requests.size == 1) requests.single().body else requests.joinToString("\n\n") {
                                "[${it.sourceId}] ${it.title}\n${it.body}"
                            },
                            deliveryIds = requests.flatMap { it.deliveryIds }.distinct(),
                            consumerAssistantId = consumerAssistantId,
                        )
                        val text = SystemTriggerMessage.compose(combined, OffsetDateTime.now())
                        val userId = "user_${UUID.randomUUID()}"
                        messages.addMessage(sessionId, Message(
                            id = userId, role = MessageRole.USER, content = text,
                            name = SystemTriggerMessage.MESSAGE_NAME,
                        ))
                        Delivered(combined, userId, text)
                    }
                    claims.forEach { claim ->
                        try { claim.confirmPersistedMessage() }
                        catch (failure: Exception) {
                            NexaraLogger.log("[SubagentCompletion] 消息已送达，持久水位将在后续检查点或启动时复核：${failure::class.java.simpleName}")
                        }
                    }
                    accepted.forEach { it.outcome.complete(SystemTurnOutcome.Completed) }
                    synchronized(lock) { accepted.forEach(::detach) }
                    delivered
                } catch (failure: Throwable) {
                    NexaraLogger.log("[SubagentCompletion] 完成通知送达失败，将重试：${failure::class.java.simpleName}")
                    if (first.kind != com.promenar.nexara.domain.trigger.SystemTurnKind.SESSION_MESSAGE) {
                        accepted.forEach { it.outcome.complete(SystemTurnOutcome.Failed("完成通知持久化失败")) }
                        synchronized(lock) { accepted.forEach(::detach) }
                    }
                    null
                } finally {
                    claims.asReversed().forEach { it.release() }
                }
            }
        }
    }

    internal fun trackGeneration(block: suspend () -> Unit) {
        applicationScope.launch { block() }
    }
}

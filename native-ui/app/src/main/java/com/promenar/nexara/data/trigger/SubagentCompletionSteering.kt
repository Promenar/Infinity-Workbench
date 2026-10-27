package com.promenar.nexara.data.trigger

import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.domain.trigger.SystemTriggerMessage
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
    internal suspend fun deliver(sessionId: String, store: ChatStore, messages: MessageManager): Delivered? {
        val lane = synchronized(lock) { lanes.getOrPut(sessionId) { Mutex() } }
        return lane.withLock {
            withContext(NonCancellable) {
                val entries = synchronized(lock) { pending[sessionId]?.toList().orEmpty() }
                    .filter { it.ticket.tryAccept("subagent-delivery:${it.request.deliveryIds.first()}") }
                if (entries.isEmpty()) return@withContext null
                try {
                    val session = checkNotNull(store.getSession(sessionId)) { "完成通知的父会话不存在" }
                    val deliveredIds = session.messages.asSequence()
                        .filter { it.name == SystemTriggerMessage.MESSAGE_NAME }
                        .flatMap { SystemTriggerMessage.parse(it.content)?.deliveryIds.orEmpty().asSequence() }
                        .toMutableSet()
                    val fresh = entries.filter { entry ->
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
                        )
                        val text = SystemTriggerMessage.compose(combined, OffsetDateTime.now())
                        val userId = "user_${UUID.randomUUID()}"
                        messages.addMessage(sessionId, Message(
                            id = userId, role = MessageRole.USER, content = text,
                            name = SystemTriggerMessage.MESSAGE_NAME,
                        ))
                        Delivered(combined, userId, text)
                    }
                    entries.forEach { it.outcome.complete(SystemTurnOutcome.Completed) }
                    delivered
                } catch (failure: Throwable) {
                    entries.forEach { it.outcome.completeExceptionally(failure) }
                    throw failure
                } finally {
                    synchronized(lock) { entries.forEach(::detach) }
                }
            }
        }
    }

    internal fun trackGeneration(block: suspend () -> Unit) {
        applicationScope.launch { block() }
    }
}

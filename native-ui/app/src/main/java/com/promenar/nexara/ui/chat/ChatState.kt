package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatState(
    val sessions: List<Session> = emptyList(),
    val currentGeneratingSessionId: String? = null,
    val activeKGExtractions: Set<String> = emptySet(),
    internal val sessionCatalogVersion: Long = 0,
)

class ChatStore {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    val current: ChatState get() = _state.value

    fun get(): ChatState = _state.value

    fun update(transform: (ChatState) -> ChatState) {
        while (true) {
            val current = _state.value
            val transformed = transform(current)
            val nextVersion = if (sameSessionMembership(current.sessions, transformed.sessions)) {
                current.sessionCatalogVersion
            } else {
                current.sessionCatalogVersion + 1
            }
            val next = transformed.copy(sessionCatalogVersion = nextVersion)
            if (_state.compareAndSet(current, next)) return
        }
    }

    fun replaceSessionsIfCatalogUnchanged(expectedVersion: Long, replacement: List<Session>): Boolean {
        val current = _state.value
        if (current.sessionCatalogVersion != expectedVersion) return false
        val currentById = current.sessions.associateBy { it.id }
        val merged = replacement.map { persisted -> currentById[persisted.id] ?: persisted }
        val nextVersion = if (sameSessionMembership(current.sessions, merged)) {
            current.sessionCatalogVersion
        } else {
            current.sessionCatalogVersion + 1
        }
        return _state.compareAndSet(
            current,
            current.copy(sessions = merged, sessionCatalogVersion = nextVersion),
        )
    }

    fun upsertSession(session: Session, prepend: Boolean = true) {
        update { state ->
            val remaining = state.sessions.filterNot { it.id == session.id }
            state.copy(sessions = if (prepend) listOf(session) + remaining else remaining + session)
        }
    }

    fun removeSession(sessionId: String) {
        update { state -> state.copy(sessions = state.sessions.filterNot { it.id == sessionId }) }
    }

    private fun sameSessionMembership(left: List<Session>, right: List<Session>): Boolean =
        left.size == right.size && left.mapTo(mutableSetOf()) { it.id } == right.mapTo(mutableSetOf()) { it.id }

    fun getSession(id: String): Session? {
        return _state.value.sessions.find { it.id == id }
    }

    fun updateSession(id: String, transform: (Session) -> Session) {
        update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == id) transform(s) else s
                }
            )
        }
    }

    fun updateMessageInSession(
        sessionId: String,
        messageId: String,
        transform: (com.promenar.nexara.data.model.Message) -> com.promenar.nexara.data.model.Message
    ) {
        update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            messages = s.messages.map { m ->
                                if (m.id == messageId) transform(m) else m
                            }
                        )
                    } else s
                }
            )
        }
    }

    fun clear() {
        update { ChatState() }
    }
}

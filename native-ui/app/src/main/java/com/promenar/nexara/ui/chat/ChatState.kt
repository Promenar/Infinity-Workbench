package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ChatState(
    val sessions: List<Session> = emptyList(),
    val currentGeneratingSessionId: String? = null,
    val activeKGExtractions: Set<String> = emptySet()
)

class ChatStore {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    val current: ChatState get() = _state.value

    fun get(): ChatState = _state.value

    fun update(transform: (ChatState) -> ChatState) {
        _state.update(transform)
    }

    fun getSession(id: String): Session? {
        return _state.value.sessions.find { it.id == id }
    }

    fun updateSession(id: String, transform: (Session) -> Session) {
        _state.update { state ->
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
        _state.update { state ->
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
}

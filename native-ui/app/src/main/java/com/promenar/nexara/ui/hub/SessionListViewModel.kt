package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val store: ChatStore,
    private val sessionRepository: ISessionRepository,
    private val agentRepository: AgentRepository
) : ViewModel() {

    private val sessionManager = SessionManager(store, sessionRepository)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _currentAgentId = MutableStateFlow("")

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName

    private val _agentColor = MutableStateFlow("#C0C1FF")
    val agentColor: StateFlow<String> = _agentColor

    val sessions: StateFlow<List<Session>> = combine(
        store.state,
        _currentAgentId,
        _searchQuery
    ) { state, agentId, query ->
        val forAgent = if (agentId.isNotEmpty()) {
            state.sessions.filter { it.agentId == agentId }
        } else {
            state.sessions
        }
        val filtered = if (query.isBlank()) {
            forAgent
        } else {
            forAgent.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.lastMessage?.contains(query, ignoreCase = true) == true
            }
        }
        filtered.sortedWith(
            compareByDescending<Session> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadSessions(agentId: String) {
        _currentAgentId.value = agentId
        viewModelScope.launch {
            try {
                val agent = agentRepository.observeById(agentId).first()
                _agentName.value = agent?.name ?: "Agent"
                _agentColor.value = agent?.color ?: "#C0C1FF"
            } catch (_: Exception) {}

            try {
                val all = sessionRepository.getAll()
                store.update { state ->
                    state.copy(sessions = all)
                }
            } catch (_: Exception) {}
        }
    }

    fun createSession(agentId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val agent = try { agentRepository.observeById(agentId).first() } catch (_: Exception) { null }
            val sessionId = IdGenerator.session()
            val session = Session(
                id = sessionId,
                agentId = agentId,
                modelId = agent?.modelId,
                inferenceParams = if (agent != null) {
                    com.promenar.nexara.data.model.InferenceParams(
                        temperature = agent.temperature,
                        topP = agent.topP,
                        maxTokens = agent.maxTokens
                    )
                } else null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            sessionManager.addSession(session)
            onCreated(sessionId)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionManager.deleteSession(id)
        }
    }

    fun searchSessions(query: String) {
        _searchQuery.update { query }
    }

    fun pinSession(id: String) {
        viewModelScope.launch {
            sessionManager.toggleSessionPin(id)
        }
    }

    fun selectSession(sessionId: String) {
        store.update { state ->
            state.copy()
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return SessionListViewModel(
                        store = app.chatStore,
                        sessionRepository = app.sessionRepository,
                        agentRepository = app.agentRepository
                    ) as T
                }
            }
    }
}

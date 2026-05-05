package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.dao.AgentDao
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val store: ChatStore,
    private val sessionRepository: ISessionRepository,
    private val agentDao: AgentDao
) : ViewModel() {

    private val sessionManager = SessionManager(store, sessionRepository)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _currentAgentId = MutableStateFlow("")

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName

    private val _agentColor = MutableStateFlow("#C0C1FF")
    val agentColor: StateFlow<String> = _agentColor

    val sessions: StateFlow<List<Session>> = store.state
        .map { state ->
            val agentId = _currentAgentId.value
            val forAgent = if (agentId.isNotEmpty()) {
                state.sessions.filter { it.agentId == agentId }
            } else {
                state.sessions
            }
            val filtered = if (_searchQuery.value.isBlank()) {
                forAgent
            } else {
                forAgent.filter {
                    it.title.contains(_searchQuery.value, ignoreCase = true) ||
                    it.lastMessage?.contains(_searchQuery.value, ignoreCase = true) == true
                }
            }
            filtered.sortedWith(
                compareByDescending<Session> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadSessions(agentId: String) {
        _currentAgentId.value = agentId
        viewModelScope.launch {
            try {
                val agent = agentDao.getById(agentId)
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
            val sessionId = "session_${System.currentTimeMillis()}"
            val session = Session(
                id = sessionId,
                agentId = agentId,
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
                        agentDao = app.database.agentDao()
                    ) as T
                }
            }
    }
}

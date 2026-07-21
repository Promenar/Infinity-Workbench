package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionListViewModel(
    private val store: ChatStore,
    private val sessionRepository: ISessionRepository,
    private val agentRepository: AgentRepository
) : ViewModel() {

    private val sessionManager = SessionManager(store, sessionRepository)
    private val sessionMutationMutex = Mutex()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _currentAgentId = MutableStateFlow("")

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName

    private val _agentColor = MutableStateFlow(Agent(id = "", name = "").color)
    val agentColor: StateFlow<String> = _agentColor

    private val _operationFailed = MutableStateFlow(false)
    val operationFailed: StateFlow<Boolean> = _operationFailed.asStateFlow()

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
                _agentColor.value = agent?.color ?: Agent(id = agentId, name = "").color
            } catch (_: Exception) {}

            try {
                sessionMutationMutex.withLock {
                    refreshSessionsFromRepository()
                }
            } catch (_: Exception) {}
        }
    }

    fun createSession(agentId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _operationFailed.value = false
            val agent = try { agentRepository.observeById(agentId).first() } catch (_: Exception) { null }
            val defaultModelId = agent?.modelId
                ?.takeIf { it.isNotBlank() }
                ?: resolveDefaultModelId()
            val sessionId = IdGenerator.session()
            val session = Session(
                id = sessionId,
                agentId = agentId,
                modelId = defaultModelId,
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
            try {
                sessionMutationMutex.withLock {
                    sessionManager.addSession(session)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _operationFailed.value = true
                return@launch
            }
            onCreated(sessionId)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            runSessionOperation { sessionManager.deleteSession(id) }
        }
    }

    fun searchSessions(query: String) {
        _searchQuery.update { query }
    }

    fun pinSession(id: String) {
        viewModelScope.launch {
            runSessionOperation { sessionManager.toggleSessionPin(id) }
        }
    }

    fun dismissOperationFailure() {
        _operationFailed.value = false
    }

    fun selectSession(sessionId: String) {
        store.update { state ->
            state.copy()
        }
    }

    private fun resolveDefaultModelId(): String? {
        return runCatching {
            val providerManager = ProviderManager.getInstance()
            providerManager.summaryModelId.value.takeIf { it.isNotBlank() }
                ?: providerManager.getMainConfiguredModelId()
        }.getOrNull()
    }

    private suspend fun runSessionOperation(operation: suspend () -> Unit) {
        _operationFailed.value = false
        try {
            sessionMutationMutex.withLock { operation() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            _operationFailed.value = true
        }
    }

    private suspend fun refreshSessionsFromRepository() {
        repeat(2) {
            val expectedVersion = store.get().sessionCatalogVersion
            val all = sessionRepository.getAll()
            if (store.replaceSessionsIfCatalogUnchanged(expectedVersion, all)) return
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

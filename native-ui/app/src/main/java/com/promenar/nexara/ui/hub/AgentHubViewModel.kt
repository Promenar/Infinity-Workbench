package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.dao.AgentDao
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.model.Agent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentHubViewModel(
    private val agentDao: AgentDao,
    private val defaultAgents: List<Agent>
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _dbAgents = MutableStateFlow<List<Agent>>(emptyList())

    val agents: StateFlow<List<Agent>> = combine(_dbAgents, _searchQuery) { list, query ->
        val filtered = if (query.isBlank()) list
        else list.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
        }
        filtered.sortedWith(compareByDescending<Agent> { it.isPinned }.thenByDescending { it.createdAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    init {
        viewModelScope.launch {
            val saved = agentDao.getAll()
            if (saved.isNotEmpty()) {
                _dbAgents.value = saved.map { it.toAgent() }
            } else {
                for (agent in defaultAgents) {
                    agentDao.insert(agent.toEntity())
                }
                _dbAgents.value = defaultAgents
            }
        }
    }

    fun createAgent(name: String, description: String, model: String, systemPrompt: String) {
        viewModelScope.launch {
            val id = "agent_${System.currentTimeMillis()}"
            val entity = AgentEntity(
                id = id, name = name, description = description,
                model = model, systemPrompt = systemPrompt,
                createdAt = System.currentTimeMillis()
            )
            agentDao.insert(entity)
            _dbAgents.update { it + entity.toAgent() }
        }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            agentDao.deleteById(agentId)
            _dbAgents.update { it.filter { a -> a.id != agentId } }
        }
    }

    fun togglePin(agentId: String) {
        viewModelScope.launch {
            val current = _dbAgents.value.find { it.id == agentId } ?: return@launch
            val newPinned = !current.isPinned
            agentDao.insert(current.toEntity().copy(isPinned = if (newPinned) 1 else 0))
            _dbAgents.update { list ->
                list.map { a -> if (a.id == agentId) a.copy(isPinned = newPinned) else a }
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return AgentHubViewModel(
                        agentDao = app.database.agentDao(),
                        defaultAgents = app.defaultAgents
                    ) as T
                }
            }
    }
}

private fun AgentEntity.toAgent() = Agent(
    id = id, name = name, description = description,
    systemPrompt = systemPrompt, model = model, icon = icon, color = color,
    isPinned = isPinned != 0, createdAt = createdAt
)

private fun Agent.toEntity() = AgentEntity(
    id = id, name = name, description = description,
    systemPrompt = systemPrompt, model = model, icon = icon, color = color,
    isPinned = if (isPinned) 1 else 0,
    createdAt = if (createdAt == 0L) System.currentTimeMillis() else createdAt
)

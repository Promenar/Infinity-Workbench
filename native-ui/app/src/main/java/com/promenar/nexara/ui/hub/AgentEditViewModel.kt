package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.model.Agent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NexaraApplication
    private val agentDao = app.database.agentDao()

    private val _initialAgent = MutableStateFlow<Agent?>(null)
    private val _name = MutableStateFlow("")
    private val _description = MutableStateFlow("")
    private val _systemPrompt = MutableStateFlow("")
    private val _selectedModel = MutableStateFlow("gpt-4o")
    private val _selectedColor = MutableStateFlow("#C0C1FF")
    private val _selectedIcon = MutableStateFlow("✨")
    private val _temperature = MutableStateFlow(0.7f)
    private val _topP = MutableStateFlow(0.9f)
    private val _isPinned = MutableStateFlow(false)

    val name: StateFlow<String> = _name.asStateFlow()
    val description: StateFlow<String> = _description.asStateFlow()
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    val selectedColor: StateFlow<String> = _selectedColor.asStateFlow()
    val selectedIcon: StateFlow<String> = _selectedIcon.asStateFlow()
    val temperature: StateFlow<Float> = _temperature.asStateFlow()
    val topP: StateFlow<Float> = _topP.asStateFlow()
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()

    val hasChanges: StateFlow<Boolean> = combine(
        combine(_initialAgent, _name, _description) { initial, n, d -> Triple(initial, n, d) },
        combine(_systemPrompt, _selectedModel, _selectedColor) { sp, sm, sc -> Triple(sp, sm, sc) },
        combine(_selectedIcon, _temperature, _topP, _isPinned) { si, temp, tp, pin -> listOf(si, temp.toDouble(), tp.toDouble(), pin) }
    ) { (initial, name, desc), (prompt, model, color), (icon, _, _, _) ->
        initial == null || initial.name != name || initial.description != desc ||
        initial.systemPrompt != prompt || initial.model != model ||
        initial.color != color || initial.icon != icon
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var saveJob: Job? = null

    fun loadAgent(agentId: String) {
        viewModelScope.launch {
            val entity = agentDao.getById(agentId)
            if (entity != null) {
                val agent = entity.toAgent()
                _initialAgent.value = agent
                _name.value = agent.name
                _description.value = agent.description
                _systemPrompt.value = agent.systemPrompt
                _selectedModel.value = agent.model
                _selectedColor.value = agent.color
                _selectedIcon.value = agent.icon
                _isPinned.value = agent.isPinned
                _temperature.value = 0.7f
                _topP.value = 0.9f
            }
        }
    }

    fun setName(value: String) {
        _name.value = value
        scheduleSave()
    }

    fun setDescription(value: String) {
        _description.value = value
        scheduleSave()
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value
        scheduleSave()
    }

    fun setModel(value: String) {
        _selectedModel.value = value
        scheduleSave()
    }

    fun setColor(value: String) {
        _selectedColor.value = value
        scheduleSave()
    }

    fun setIcon(value: String) {
        _selectedIcon.value = value
        scheduleSave()
    }

    fun setTemperature(value: Float) {
        _temperature.value = value
        scheduleSave()
    }

    fun setTopP(value: Float) {
        _topP.value = value
        scheduleSave()
    }

    fun deleteAgent(agentId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            agentDao.deleteById(agentId)
            onDeleted()
        }
    }

    fun saveAgent(agentId: String) {
        viewModelScope.launch {
            val entity = AgentEntity(
                id = agentId,
                name = _name.value,
                description = _description.value,
                systemPrompt = _systemPrompt.value,
                model = _selectedModel.value,
                icon = _selectedIcon.value,
                color = _selectedColor.value,
                isPinned = if (_isPinned.value) 1 else 0,
                createdAt = _initialAgent.value?.createdAt ?: System.currentTimeMillis()
            )
            agentDao.insert(entity)
            _initialAgent.value = entity.toAgent()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000)
            _initialAgent.value?.let { agent ->
                if (hasChanges.value) {
                    saveAgent(agent.id)
                }
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return AgentEditViewModel(application) as T
                }
            }

        private fun AgentEntity.toAgent() = Agent(
            id = id,
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            model = model,
            icon = icon,
            color = color,
            isPinned = isPinned != 0,
            createdAt = createdAt
        )
    }
}

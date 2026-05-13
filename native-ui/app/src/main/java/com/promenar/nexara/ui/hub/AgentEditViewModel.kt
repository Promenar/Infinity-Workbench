package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentEditViewModel(
    private val agentRepository: AgentRepository,
    private val ragConfigPersistence: RagConfigPersistence
) : ViewModel() {

    private val _initialAgent = MutableStateFlow<Agent?>(null)
    private val _name = MutableStateFlow("")
    private val _description = MutableStateFlow("")
    private val _systemPrompt = MutableStateFlow("")
    private val _selectedModel = MutableStateFlow("")
    private val _selectedColor = MutableStateFlow("#C0C1FF")
    private val _selectedIcon = MutableStateFlow("✨")
    private val _avatarPath = MutableStateFlow<String?>(null)
    private val _temperature = MutableStateFlow(0.7f)
    private val _topP = MutableStateFlow(0.9f)
    private val _isPinned = MutableStateFlow(false)
    private val _useInheritedConfig = MutableStateFlow(true)
    private val _ragConfig = MutableStateFlow(com.promenar.nexara.data.agent.AgentRagConfig())
    private val _retrievalConfig = MutableStateFlow(com.promenar.nexara.data.agent.AgentRetrievalConfig())

    val name: StateFlow<String> = _name.asStateFlow()
    val description: StateFlow<String> = _description.asStateFlow()
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    val selectedColor: StateFlow<String> = _selectedColor.asStateFlow()
    val selectedIcon: StateFlow<String> = _selectedIcon.asStateFlow()
    val avatarPath: StateFlow<String?> = _avatarPath.asStateFlow()
    val temperature: StateFlow<Float> = _temperature.asStateFlow()
    val topP: StateFlow<Float> = _topP.asStateFlow()
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()
    val useInheritedConfig: StateFlow<Boolean> = _useInheritedConfig.asStateFlow()
    val ragConfig: StateFlow<com.promenar.nexara.data.agent.AgentRagConfig> = _ragConfig.asStateFlow()
    val retrievalConfig: StateFlow<com.promenar.nexara.data.agent.AgentRetrievalConfig> = _retrievalConfig.asStateFlow()

    val hasChanges: StateFlow<Boolean> = combine(
        combine(_initialAgent, _name, _description) { initial, n, d -> Triple(initial, n, d) },
        combine(_systemPrompt, _selectedModel, _selectedColor) { sp, sm, sc -> Triple(sp, sm, sc) },
        combine(_selectedIcon, _avatarPath, _isPinned) { si, ap, pin -> Triple(si, ap, pin) },
        combine(_temperature, _topP) { temp, tp -> Pair(temp, tp) },
        combine(_useInheritedConfig, _ragConfig, _retrievalConfig) { u, r, ret -> Triple(u, r, ret) }
    ) { (initial, name, desc), (prompt, model, color), (icon, path, pin), (temp, tp), (useIn, rag, retr) ->
        initial == null || initial.name != name || initial.description != desc ||
        initial.systemPrompt != prompt || initial.modelId != model ||
        initial.color != color || initial.icon != icon || initial.avatarPath != path || initial.isPinned != pin ||
        initial.useInheritedConfig != useIn || initial.ragConfig != rag || initial.retrievalConfig != retr
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var saveJob: Job? = null

    fun loadAgent(agentId: String) {
        viewModelScope.launch {
            val agent = agentRepository.observeById(agentId).first()
            if (agent != null) {
                _initialAgent.value = agent
                _name.value = agent.name
                _description.value = agent.description
                _systemPrompt.value = agent.systemPrompt
                _selectedModel.value = agent.modelId
                _selectedColor.value = agent.color
                _selectedIcon.value = agent.icon
                _avatarPath.value = agent.avatarPath
                _isPinned.value = agent.isPinned
                _temperature.value = agent.temperature?.toFloat() ?: 0.7f
                _topP.value = agent.topP?.toFloat() ?: 0.9f
                _useInheritedConfig.value = agent.useInheritedConfig

                if (agent.useInheritedConfig) {
                    _ragConfig.value = getGlobalRagConfig()
                    _retrievalConfig.value = getGlobalRetrievalConfig()
                } else {
                    _ragConfig.value = agent.ragConfig ?: getGlobalRagConfig()
                    _retrievalConfig.value = agent.retrievalConfig ?: getGlobalRetrievalConfig()
                }
            }
        }
    }

    private fun getGlobalRagConfig(): com.promenar.nexara.data.agent.AgentRagConfig {
        return ragConfigPersistence.loadRagConfig()
    }

    private fun getGlobalRetrievalConfig(): com.promenar.nexara.data.agent.AgentRetrievalConfig {
        return ragConfigPersistence.loadRetrievalConfig()
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
        _avatarPath.value = null
        scheduleSave()
    }

    fun setAvatarPath(value: String?) {
        _avatarPath.value = value
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

    fun setPinned(value: Boolean) {
        _isPinned.value = value
        scheduleSave()
    }

    fun setUseInheritedConfig(value: Boolean) {
        _useInheritedConfig.value = value
        if (value) {
            _ragConfig.value = getGlobalRagConfig()
            _retrievalConfig.value = getGlobalRetrievalConfig()
        }
        scheduleSave()
    }

    fun updateRagConfig(transform: (com.promenar.nexara.data.agent.AgentRagConfig) -> com.promenar.nexara.data.agent.AgentRagConfig) {
        _ragConfig.update(transform)
        _useInheritedConfig.value = false
        scheduleSave()
    }

    fun updateRetrievalConfig(transform: (com.promenar.nexara.data.agent.AgentRetrievalConfig) -> com.promenar.nexara.data.agent.AgentRetrievalConfig) {
        _retrievalConfig.update(transform)
        _useInheritedConfig.value = false
        scheduleSave()
    }

    fun resetToGlobal() {
        _useInheritedConfig.value = true
        _ragConfig.value = getGlobalRagConfig()
        _retrievalConfig.value = getGlobalRetrievalConfig()
        scheduleSave()
    }

    fun deleteAgent(agentId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            agentRepository.delete(agentId)
            onDeleted()
        }
    }

    fun saveAgent(agentId: String) {
        viewModelScope.launch {
            val agent = Agent(
                id = agentId,
                name = _name.value,
                description = _description.value,
                systemPrompt = _systemPrompt.value,
                modelId = _selectedModel.value,
                icon = _selectedIcon.value,
                color = _selectedColor.value,
                avatarPath = _avatarPath.value,
                isPinned = _isPinned.value,
                temperature = _temperature.value.toDouble(),
                topP = _topP.value.toDouble(),
                maxTokens = 4096,
                ragConfig = if (_useInheritedConfig.value) null else _ragConfig.value,
                retrievalConfig = if (_useInheritedConfig.value) null else _retrievalConfig.value,
                useInheritedConfig = _useInheritedConfig.value,
                executionMode = _initialAgent.value?.executionMode ?: com.promenar.nexara.domain.model.ExecutionMode.SEMI,
                skills = _initialAgent.value?.skills ?: emptyList(),
                createdAt = _initialAgent.value?.createdAt ?: System.currentTimeMillis()
            )
            agentRepository.update(agent)
            _initialAgent.value = agent
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
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return AgentEditViewModel(
                        agentRepository = app.agentRepository,
                        ragConfigPersistence = RagConfigPersistence(
                            app.getSharedPreferences("rag_settings", 0)
                        )
                    ) as T
                }
            }
    }
}

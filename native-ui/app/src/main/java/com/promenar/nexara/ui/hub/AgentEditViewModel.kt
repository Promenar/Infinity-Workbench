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
        initial.systemPrompt != prompt || initial.model != model ||
        initial.color != color || initial.icon != icon || initial.avatarPath != path || initial.isPinned != pin ||
        initial.useInheritedConfig != useIn || initial.ragConfig != rag || initial.retrievalConfig != retr
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
        val prefs = app.getSharedPreferences("rag_settings", 0)
        return com.promenar.nexara.data.agent.AgentRagConfig(
            docChunkSize = prefs.getInt("doc_chunk_size", 800).toFloat(),
            chunkOverlap = prefs.getInt("chunk_overlap", 100).toFloat(),
            memoryChunkSize = prefs.getInt("memory_chunk_size", 1000).toFloat(),
            contextWindow = prefs.getInt("context_window", 20),
            summaryThreshold = prefs.getInt("summary_threshold", 10)
        )
    }

    private fun getGlobalRetrievalConfig(): com.promenar.nexara.data.agent.AgentRetrievalConfig {
        val prefs = app.getSharedPreferences("rag_settings", 0)
        return com.promenar.nexara.data.agent.AgentRetrievalConfig(
            memoryLimit = prefs.getInt("memory_limit", 5),
            memoryThreshold = prefs.getFloat("memory_threshold", 0.7f),
            docLimit = prefs.getInt("doc_limit", 8),
            docThreshold = prefs.getFloat("doc_threshold", 0.45f),
            enableRerank = prefs.getBoolean("enable_rerank", false),
            rerankTopK = prefs.getInt("rerank_top_k", 30),
            rerankFinalK = prefs.getInt("rerank_final_k", 5),
            enableQueryRewrite = prefs.getBoolean("enable_query_rewrite", false),
            queryRewriteStrategy = prefs.getString("query_rewrite_strategy", "multi-query") ?: "multi-query",
            queryRewriteCount = prefs.getInt("query_rewrite_count", 3),
            enableHybridSearch = prefs.getBoolean("enable_hybrid_search", false),
            hybridAlpha = prefs.getFloat("hybrid_alpha", 0.6f),
            hybridBM25Boost = prefs.getFloat("hybrid_bm25_boost", 1.0f)
        )
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
        _avatarPath.value = null // Clear custom image if preset icon selected
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
                avatarPath = _avatarPath.value,
                isPinned = if (_isPinned.value) 1 else 0,
                createdAt = _initialAgent.value?.createdAt ?: System.currentTimeMillis(),
                temperature = _temperature.value.toDouble(),
                top_p = _topP.value.toDouble(),
                max_tokens = 4096, // Default for now
                ragConfig = if (_useInheritedConfig.value) null else _ragConfig.value,
                retrievalConfig = if (_useInheritedConfig.value) null else _retrievalConfig.value,
                useInheritedConfig = _useInheritedConfig.value
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
            avatarPath = avatarPath,
            isPinned = isPinned != 0,
            temperature = temperature,
            topP = top_p,
            maxTokens = max_tokens,
            ragConfig = ragConfig,
            retrievalConfig = retrievalConfig,
            useInheritedConfig = useInheritedConfig,
            createdAt = createdAt
        )
    }
}

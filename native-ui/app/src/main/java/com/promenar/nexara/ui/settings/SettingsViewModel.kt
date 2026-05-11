package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.model.MODEL_SPECS
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.repository.ISkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelInfo(
    val name: String,
    val id: String,
    val description: String,
    val enabled: Boolean,
    val type: String = "chat",
    val contextLength: Int = 8192,
    val capabilities: List<String> = emptyList(),
    val providerName: String = "Cloud",
    val testStatus: String? = null
)

data class ProviderStats(
    val name: String,
    val totalTokens: Long,
    val cost: Double,
    val models: List<ModelStat>
)

data class ModelStat(
    val name: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cost: Double
)

data class McpServerUiModel(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val isConnected: Boolean,
    val isEnabled: Boolean,
    val isDefault: Boolean,
    val callIntervalMs: Long,
    val tools: List<String>
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

data class ProviderListItem(
    val id: String = "",
    val name: String = "",
    val typeName: String = "",
    val baseUrl: String = "",
    val model: String = ""
)

data class SearchSettings(
    val engine: String,
    val tavilyKey: String,
    val searxngUrl: String,
    val depth: String,
    val resultCount: Int
)

class SettingsViewModel(application: Application) : ViewModel() {

    private val app = application as NexaraApplication
    private val prefs: SharedPreferences =
        application.getSharedPreferences("nexara_settings", 0)

    private val _providerModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val providerModels: StateFlow<List<ModelInfo>> = _providerModels.asStateFlow()

    private val _tokenStats = MutableStateFlow<List<ProviderStats>>(emptyList())
    val tokenStats: StateFlow<List<ProviderStats>> = _tokenStats.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    private val _userSkills = MutableStateFlow<List<CustomSkillEntity>>(emptyList())
    val userSkills: StateFlow<List<CustomSkillEntity>> = _userSkills.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerUiModel>>(emptyList())
    val mcpServers: StateFlow<List<McpServerUiModel>> = _mcpServers.asStateFlow()

    private val _userName = MutableStateFlow(application.getString(R.string.settings_default_user_name))
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    private val _language = MutableStateFlow("zh")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _themeMode = MutableStateFlow("dark")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderListItem>>(emptyList())
    val providers: StateFlow<List<ProviderListItem>> = _providers.asStateFlow()

    private val _selectedSettingsTab = MutableStateFlow(0) // 0: App, 1: Provider
    val selectedSettingsTab: StateFlow<Int> = _selectedSettingsTab.asStateFlow()

    fun setSelectedSettingsTab(index: Int) {
        _selectedSettingsTab.value = index
    }

    private val _currentModelSummary = MutableStateFlow("")
    val currentModelSummary: StateFlow<String> = _currentModelSummary.asStateFlow()

    private val _activeSourcesCount = MutableStateFlow(0)
    val activeSourcesCount: StateFlow<Int> = _activeSourcesCount.asStateFlow()

    private val _tokenCostThisMonth = MutableStateFlow("$0.00")
    val tokenCostThisMonth: StateFlow<String> = _tokenCostThisMonth.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _summaryModelId = MutableStateFlow("")
    val summaryModelId = _summaryModelId.asStateFlow()

    private val _imageModelId = MutableStateFlow("")
    val imageModelId = _imageModelId.asStateFlow()

    private val _embeddingModelId = MutableStateFlow("")
    val embeddingModelId = _embeddingModelId.asStateFlow()

    private val _rerankModelId = MutableStateFlow("")
    val rerankModelId = _rerankModelId.asStateFlow()

    private val _searchSettings = MutableStateFlow(SearchSettings("duckduckgo", "", "https://searx.be", "basic", 5))
    val searchSettings = _searchSettings.asStateFlow()


    private val searchPrefs: SharedPreferences =
        application.getSharedPreferences("nexara_search", 0)

    private val _loopLimit = MutableStateFlow(prefs.getInt("loop_limit", 15))
    val loopLimit: StateFlow<Int> = _loopLimit.asStateFlow()

    fun updateLoopLimit(limit: Int) {
        _loopLimit.value = limit
        prefs.edit().putInt("loop_limit", limit).apply()
    }


    init {
        loadAll()
    }

    private fun loadAll() {
        loadUserProfile()
        loadPreferences()
        loadProviders()
        loadModels()
        loadTokenStats()
        loadKnowledgeStats()
        loadSkills()
        observeSkills()
    }

    private fun observeSkills() {
        viewModelScope.launch {
            app.skillRepository.getAllCustomSkills().collectLatest { all ->
                // Filter out any skills that might have IDs matching preset skills to avoid duplication in UI
                val presetIds = setOf("web_search", "calculator", "current_time", "weather_lookup", "create_tool")
                _userSkills.value = all.filter { it.id !in presetIds }
            }
        }
        viewModelScope.launch {
            app.skillRepository.getAllMcpServers().collectLatest { entities ->
                _mcpServers.value = entities.map { entity ->
                    // Map entity to UI model, for now just use defaults for connected status
                    McpServerUiModel(
                        id = entity.id,
                        name = entity.name,
                        url = entity.url,
                        type = entity.type,
                        isConnected = false, // Will be updated via sync
                        isEnabled = entity.enabled,
                        isDefault = entity.isDefault,
                        callIntervalMs = entity.callIntervalMs,
                        tools = emptyList() // Fetching tools would be part of sync
                    )
                }
            }
        }
    }

    private fun loadUserProfile() {
        val defaultName = app.getString(R.string.settings_default_user_name)
        _userName.value = prefs.getString("user_name", defaultName) ?: defaultName
        _userAvatar.value = prefs.getString("user_avatar", null)
    }

    private fun loadPreferences() {
        _language.value = prefs.getString("language", "zh") ?: "zh"
        _themeMode.value = prefs.getString("theme_mode", "dark") ?: "dark"
        _hapticEnabled.value = prefs.getBoolean("haptic_enabled", true)

        _loopLimit.value = prefs.getInt("loop_limit", 15)

        _summaryModelId.value = prefs.getString("preset_summary_model", "") ?: ""
        _imageModelId.value = prefs.getString("preset_image_model", "") ?: ""
        _embeddingModelId.value = prefs.getString("preset_embedding_model", "") ?: ""
        _rerankModelId.value = prefs.getString("preset_rerank_model", "") ?: ""

        loadSearchSettings()
    }

    private fun loadSearchSettings() {
        _searchSettings.value = SearchSettings(
            engine = searchPrefs.getString("search_engine", "duckduckgo") ?: "duckduckgo",
            tavilyKey = searchPrefs.getString("tavily_api_key", "") ?: "",
            searxngUrl = searchPrefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be",
            depth = searchPrefs.getString("search_depth", "basic") ?: "basic",
            resultCount = searchPrefs.getInt("search_result_count", 5)
        )
    }

    fun updateSearchSettings(settings: SearchSettings) {
        _searchSettings.value = settings
        searchPrefs.edit()
            .putString("search_engine", settings.engine)
            .putString("tavily_api_key", settings.tavilyKey)
            .putString("searxng_url", settings.searxngUrl)
            .putString("search_depth", settings.depth)
            .putInt("result_count", settings.resultCount)
            .apply()
    }

    fun refreshProviders() {
        loadProviders()
    }

    private fun loadProviders() {
        val savedConfig = app.getSavedProviderConfig()
        val items = mutableListOf<ProviderListItem>()
        if (savedConfig != null && savedConfig.apiKey.isNotBlank()) {
            val typeName = when (savedConfig.protocolId) {
                ProtocolId.OPENAI -> "OpenAI"
                ProtocolId.ANTHROPIC -> "Anthropic"
                ProtocolId.VERTEX_AI -> "Vertex AI"
                ProtocolId.LOCAL -> "本地模型"
            }
            items.add(
                ProviderListItem(
                    id = "default",
                    name = savedConfig.name ?: typeName,
                    typeName = typeName,
                    baseUrl = savedConfig.baseUrl,
                    model = savedConfig.model
                )
            )
            _currentModelSummary.value = savedConfig.model
        }
        val count = prefs.getInt("extra_providers_count", 0)
        for (i in 0 until count) {
            val prefix = "extra_provider_$i"
            val name = prefs.getString("${prefix}_name", null) ?: continue
            items.add(
                ProviderListItem(
                    id = "extra_$i",
                    name = name,
                    typeName = prefs.getString("${prefix}_type", "") ?: "",
                    baseUrl = prefs.getString("${prefix}_base_url", "") ?: "",
                    model = prefs.getString("${prefix}_model", "") ?: ""
                )
            )
        }
        _providers.value = items
    }

    private fun loadModels() {
        val allIds = prefs.getStringSet("all_models", null)
        val enabledSet = prefs.getStringSet("enabled_models", null)
        
        if (allIds == null) {
            // Initial load or first time
            val savedConfig = app.getSavedProviderConfig()
            val currentModel = savedConfig?.model
            
            if (currentModel == null) {
                _providerModels.value = emptyList()
                persistModels(emptyList())
                return
            }

            val spec = com.promenar.nexara.data.model.findModelSpec(currentModel)
            val initialModels = listOf(
                ModelInfo(
                    name = spec?.note ?: currentModel,
                    id = currentModel,
                    description = spec?.note ?: "Initial model",
                    enabled = true,
                    type = spec?.type?.name?.lowercase() ?: "chat",
                    contextLength = spec?.contextLength ?: 8192
                )
            )
            _providerModels.value = initialModels
            persistModels(initialModels)
            
            // Trigger fetch to populate model list after initializing the default
            refreshModels()
            return
        }

        val models = allIds.map { id ->
            val prefix = "model_info_$id"
            val caps = prefs.getStringSet("${prefix}_caps", emptySet()) ?: emptySet()
            ModelInfo(
                name = prefs.getString("${prefix}_name", id) ?: id,
                id = id,
                description = "", 
                enabled = enabledSet?.contains(id) ?: false,
                type = prefs.getString("${prefix}_type", "chat") ?: "chat",
                contextLength = prefs.getInt("${prefix}_context", 8192),
                capabilities = caps.toList(),
                providerName = prefs.getString("${prefix}_provider", "Cloud") ?: "Cloud"
            )
        }.sortedByDescending { it.enabled }
        
        _providerModels.value = models
    }

    private fun loadTokenStats() {
        viewModelScope.launch {
            try {
                val vectorDao = app.database.vectorDao()
                val totalCount = vectorDao.getAll().size
                val savedConfig = app.getSavedProviderConfig()
                _tokenStats.value = listOf(
                    ProviderStats(
                        savedConfig?.name ?: "Provider",
                        (totalCount * 500L),
                        totalCount * 0.02,
                        listOf(
                            ModelStat(
                                app.getString(R.string.token_active_model),
                                (totalCount * 300L),
                                (totalCount * 200L),
                                totalCount * 0.02
                            )
                        )
                    )
                )
                _tokenCostThisMonth.value = "$%.2f".format(if (totalCount > 0) totalCount * 0.02 else 0.0)
            } catch (_: Exception) {
                _tokenStats.value = emptyList()
            }
        }
    }

    private fun loadSkills() {
        val enabledSet = prefs.getStringSet("enabled_skills", setOf(
            "web_search", "calculator", "current_time", "weather_lookup", "create_tool"
        ))
        _skills.value = listOf(
            SkillInfo("web_search", app.getString(R.string.skill_web_search), app.getString(R.string.skill_web_search_desc), enabledSet?.contains("web_search") ?: true),
            SkillInfo("search_tavily", app.getString(R.string.skill_tavily), app.getString(R.string.skill_tavily_desc), enabledSet?.contains("search_tavily") ?: true),
            SkillInfo("search_searxng", app.getString(R.string.skill_searxng), app.getString(R.string.skill_searxng_desc), enabledSet?.contains("search_searxng") ?: true),
            SkillInfo("calculator", app.getString(R.string.skill_calculator), app.getString(R.string.skill_calculator_desc), enabledSet?.contains("calculator") ?: true),
            SkillInfo("current_time", app.getString(R.string.skill_current_time), app.getString(R.string.skill_current_time_desc), enabledSet?.contains("current_time") ?: true),
            SkillInfo("weather_lookup", app.getString(R.string.skill_weather), app.getString(R.string.skill_weather_desc), enabledSet?.contains("weather_lookup") ?: true),
            SkillInfo("create_tool", app.getString(R.string.skill_create_tool), app.getString(R.string.skill_create_tool_desc), enabledSet?.contains("create_tool") ?: true)
        )
    }

    private fun loadKnowledgeStats() {
        viewModelScope.launch {
            try {
                val docCount = app.database.documentDao().getGlobalDocuments().size
                _activeSourcesCount.value = docCount
            } catch (_: Exception) { }
        }
    }

    fun updateUserName(name: String) {
        _userName.value = name
        prefs.edit().putString("user_name", name).apply()
    }

    fun updateUserAvatar(uriStr: String?) {
        if (uriStr != null) {
            val uri = Uri.parse(uriStr)
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Not a persistable URI, ignore
            }
        }
        _userAvatar.value = uriStr
        prefs.edit().putString("user_avatar", uriStr).apply()
    }

    fun setLanguage(lang: String) {
        _language.value = lang
        prefs.edit().putString("language", lang).apply()
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun setHaptic(enabled: Boolean) {
        _hapticEnabled.value = enabled
        prefs.edit().putBoolean("haptic_enabled", enabled).apply()
        app.hapticEnabled = enabled
    }

    fun deleteProvider(providerId: String) {
        _providers.update { list ->
            list.filter { it.id != providerId }
        }
        persistProviders(_providers.value)
    }

    fun addProvider(item: ProviderListItem) {
        _providers.update { list ->
            list + item
        }
        persistProviders(_providers.value)
    }

    private fun persistProviders(providers: List<ProviderListItem>) {
        val extraProviders = providers.filter { it.id != "default" }
        val editor = prefs.edit()
        editor.putInt("extra_providers_count", extraProviders.size)
        extraProviders.forEachIndexed { index, item ->
            val prefix = "extra_provider_$index"
            editor.putString("${prefix}_name", item.name)
            editor.putString("${prefix}_type", item.typeName)
            editor.putString("${prefix}_base_url", item.baseUrl)
            editor.putString("${prefix}_model", item.model)
        }
        editor.apply()
    }

    fun updateModel(updatedModel: ModelInfo) {
        _providerModels.update { models ->
            val updated = models.map {
                if (it.id == updatedModel.id) updatedModel else it
            }
            saveEnabledModels(updated)
            updated
        }
    }

    fun toggleModel(id: String) {
        _providerModels.update { models ->
            val updated = models.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
            saveEnabledModels(updated)
            updated
        }
    }

    private fun saveEnabledModels(models: List<ModelInfo>) {
        val enabled = models.filter { it.enabled }.map { it.id }.toSet()
        prefs.edit().putStringSet("enabled_models", enabled).apply()
        persistModels(models)
    }

    private fun persistModels(models: List<ModelInfo>) {
        val allIds = models.map { it.id }.toSet()
        val editor = prefs.edit()
        editor.putStringSet("all_models", allIds)
        
        models.forEach { model ->
            val prefix = "model_info_${model.id}"
            editor.putString("${prefix}_name", model.name)
            editor.putString("${prefix}_type", model.type)
            editor.putInt("${prefix}_context", model.contextLength)
            editor.putStringSet("${prefix}_caps", model.capabilities.toSet())
            editor.putString("${prefix}_provider", model.providerName)
        }
        editor.apply()
    }

    fun refreshModels() {
        viewModelScope.launch {
            _isFetchingModels.value = true
            try {
                val fetchedIds = app.llmProvider.listModels()
                if (fetchedIds.isNotEmpty()) {
                    val currentModels = _providerModels.value.toMutableList()
                    val existingIds = currentModels.map { it.id }.toSet()
                    
                    val newModels = fetchedIds.filter { it !in existingIds }.map { id ->
                        val spec = com.promenar.nexara.data.model.findModelSpec(id)
                        val type = spec?.type?.name?.lowercase() ?: "chat"
                        ModelInfo(
                            name = id,
                            id = id,
                            description = spec?.note ?: "Fetched model",
                            enabled = false,
                            type = type,
                            contextLength = spec?.contextLength ?: 8192,
                            providerName = _providers.value.firstOrNull { it.id == "default" }?.name ?: "Cloud",
                            capabilities = buildList {
                                add("chat") // Ensure chat models have chat capability
                                spec?.capabilities?.let { caps ->
                                    if (caps.vision && type != "vision") add("vision")
                                    if (caps.internet && type != "internet") add("internet")
                                }
                                if (type == "reasoning") add("reasoning")
                            }
                        )
                    }
                    
                    val updated = currentModels + newModels
                    _providerModels.value = updated
                    persistModels(updated)
                }
            } catch (_: Exception) {
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    fun disableAllModels() {
        _providerModels.update { models ->
            val updated = models.map { it.copy(enabled = false) }
            saveEnabledModels(updated)
            updated
        }
    }

    fun deleteAllModels() {
        _providerModels.value = emptyList()
        prefs.edit()
            .remove("enabled_models")
            .remove("all_models")
            .apply()
        // Also remove individual model info (simplified here)
    }

    fun addCustomModel(id: String, name: String) {
        val spec = com.promenar.nexara.data.model.findModelSpec(id)
        val type = spec?.type?.name?.lowercase() ?: "chat"
        val newModel = ModelInfo(
            name = name.ifEmpty { id },
            id = id,
            description = spec?.note ?: "Custom model",
            enabled = true,
            type = type,
            contextLength = spec?.contextLength ?: 8192,
            providerName = _providers.value.firstOrNull { it.id == "default" }?.name ?: "Cloud",
            capabilities = buildList {
                add("chat")
                spec?.capabilities?.let { caps ->
                    if (caps.vision && type != "vision") add("vision")
                    if (caps.internet && type != "internet") add("internet")
                }
                if (type == "reasoning") add("reasoning")
            }
        )
        _providerModels.update { it + newModel }
        saveEnabledModels(_providerModels.value)
    }

    fun toggleSkill(id: String) {
        if (id.startsWith("user_")) {
            viewModelScope.launch {
                val skill = _userSkills.value.find { it.id == id }
                if (skill != null) {
                    app.skillRepository.updateCustomSkillEnabled(id, !skill.enabled)
                }
            }
        } else {
            _skills.update { skills ->
                val updated = skills.map {
                    if (it.id == id) it.copy(enabled = !it.enabled) else it
                }
                saveEnabledSkills(updated)
                updated
            }
        }
    }

    private fun saveEnabledSkills(skills: List<SkillInfo>) {
        val enabled = skills.filter { it.enabled }.map { it.id }.toSet()
        prefs.edit().putStringSet("enabled_skills", enabled).apply()
    }

    // MCP Methods
    fun addMcpServer(name: String, url: String, type: String) {
        viewModelScope.launch {
            val server = McpServerEntity(
                id = "mcp_${System.currentTimeMillis()}",
                name = name,
                url = url,
                type = type
            )
            app.skillRepository.insertMcpServer(server)
        }
    }

    fun deleteMcpServer(id: String) {
        viewModelScope.launch {
            val server = _mcpServers.value.find { it.id == id }
            if (server != null) {
                app.skillRepository.deleteMcpServer(com.promenar.nexara.data.local.db.entity.McpServerEntity(
                    id = server.id,
                    name = server.name,
                    url = server.url,
                    type = server.type,
                    enabled = server.isEnabled,
                    callIntervalMs = server.callIntervalMs,
                    isDefault = server.isDefault
                ))
            }
        }
    }

    fun toggleMcpServer(id: String, enabled: Boolean) {
        viewModelScope.launch {
            app.skillRepository.updateMcpServerEnabled(id, enabled)
        }
    }

    fun updateMcpServerDefault(id: String, isDefault: Boolean) {
        viewModelScope.launch {
            app.skillRepository.updateMcpServerDefault(id, isDefault)
        }
    }

    // User Skill Methods
    fun addCustomSkill(name: String, description: String, schema: String, code: String, id: String? = null) {
        viewModelScope.launch {
            val skill = CustomSkillEntity(
                id = id ?: "user_${System.currentTimeMillis()}",
                name = name,
                description = description,
                parametersSchema = schema,
                code = code,
                enabled = true
            )
            app.skillRepository.insertCustomSkill(skill)
        }
    }

    fun deleteCustomSkill(id: String) {
        viewModelScope.launch {
            val skill = _userSkills.value.find { it.id == id }
            if (skill != null) {
                app.skillRepository.deleteCustomSkill(skill)
            }
        }
    }

    fun syncMcpServer(id: String) {
        viewModelScope.launch {
            val server = _mcpServers.value.find { it.id == id } ?: return@launch
            try {
                val client = com.promenar.nexara.data.remote.mcp.McpClient(app.httpClient, server.url)
                val tools = client.listTools()
                _mcpServers.update { list ->
                    list.map { 
                        if (it.id == id) it.copy(isConnected = true, tools = tools.map { t -> t.name })
                        else it
                    }
                }
            } catch (e: Exception) {
                _mcpServers.update { list ->
                    list.map { 
                        if (it.id == id) it.copy(isConnected = false)
                        else it
                    }
                }
            }
        }
    }

    fun clearTokenStats() {
        _tokenStats.value = emptyList()
    }


    fun refreshAll() {
        loadAll()
    }

    fun deleteModel(id: String) {
        val current = _providerModels.value.filter { it.id != id }
        _providerModels.value = current
        persistModels(current)
    }

    fun setPresetModel(type: String, modelId: String) {
        when (type) {
            "summary" -> {
                _summaryModelId.value = modelId
                prefs.edit().putString("preset_summary_model", modelId).apply()
                _currentModelSummary.value = modelId
            }
            "image" -> {
                _imageModelId.value = modelId
                prefs.edit().putString("preset_image_model", modelId).apply()
            }
            "embedding" -> {
                _embeddingModelId.value = modelId
                prefs.edit().putString("preset_embedding_model", modelId).apply()
            }
            "rerank" -> {
                _rerankModelId.value = modelId
                prefs.edit().putString("preset_rerank_model", modelId).apply()
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(application) as T
                }
            }
    }
}

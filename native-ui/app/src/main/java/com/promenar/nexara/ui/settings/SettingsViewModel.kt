package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.model.MODEL_SPECS
import com.promenar.nexara.data.remote.protocol.ProtocolId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

data class ProviderListItem(
    val id: String,
    val name: String,
    val typeName: String,
    val baseUrl: String,
    val model: String
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

    private val _logEnabled = MutableStateFlow(false)
    val logEnabled: StateFlow<Boolean> = _logEnabled.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderListItem>>(emptyList())
    val providers: StateFlow<List<ProviderListItem>> = _providers.asStateFlow()

    private val _selectedSettingsTab = MutableStateFlow(0) // 0: App, 1: Provider
    val selectedSettingsTab: StateFlow<Int> = _selectedSettingsTab.asStateFlow()

    fun setSelectedSettingsTab(index: Int) {
        _selectedSettingsTab.value = index
    }

    private val _currentModelSummary = MutableStateFlow("gpt-4o")
    val currentModelSummary: StateFlow<String> = _currentModelSummary.asStateFlow()

    private val _activeSourcesCount = MutableStateFlow(0)
    val activeSourcesCount: StateFlow<Int> = _activeSourcesCount.asStateFlow()

    private val _tokenCostThisMonth = MutableStateFlow("$0.00")
    val tokenCostThisMonth: StateFlow<String> = _tokenCostThisMonth.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        loadUserProfile()
        loadPreferences()
        loadProviders()
        loadModels()
        loadTokenStats()
        loadSkills()
        loadKnowledgeStats()
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
        _logEnabled.value = prefs.getBoolean("log_enabled", false)
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
            return
        }

        val models = allIds.map { id ->
            val prefix = "model_info_$id"
            val caps = prefs.getStringSet("${prefix}_caps", emptySet()) ?: emptySet()
            ModelInfo(
                name = prefs.getString("${prefix}_name", id) ?: id,
                id = id,
                description = "", // Not strictly needed for UI list
                enabled = enabledSet?.contains(id) ?: false,
                type = prefs.getString("${prefix}_type", "chat") ?: "chat",
                contextLength = prefs.getInt("${prefix}_context", 8192),
                capabilities = caps.toList()
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
            "web_search", "code_interpreter", "knowledge_retrieval"
        ))
        _skills.value = listOf(
            SkillInfo("web_search", "Web Search", "Search the internet for real-time information", enabledSet?.contains("web_search") ?: true),
            SkillInfo("code_interpreter", "Code Interpreter", "Execute Python code in a sandboxed environment", enabledSet?.contains("code_interpreter") ?: true),
            SkillInfo("image_generation", "Image Generation", "Generate images from text descriptions", enabledSet?.contains("image_generation") ?: false),
            SkillInfo("knowledge_retrieval", "Knowledge Retrieval", "Search through uploaded documents and knowledge bases", enabledSet?.contains("knowledge_retrieval") ?: true),
            SkillInfo("weather_lookup", "Weather Lookup", "Get current weather conditions for any location", enabledSet?.contains("weather_lookup") ?: false),
            SkillInfo("calendar", "Calendar", "Manage events and reminders", enabledSet?.contains("calendar") ?: false)
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
    }

    fun setLogEnabled(enabled: Boolean) {
        _logEnabled.value = enabled
        prefs.edit().putBoolean("log_enabled", enabled).apply()
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
                        ModelInfo(
                            name = spec?.note ?: id,
                            id = id,
                            description = spec?.note ?: "Fetched model",
                            enabled = false,
                            type = spec?.type?.name?.lowercase() ?: "chat",
                            contextLength = spec?.contextLength ?: 8192,
                            capabilities = buildList {
                                spec?.capabilities?.let { caps ->
                                    if (caps.vision) add("vision")
                                    if (caps.internet) add("internet")
                                    if (caps.reasoning) add("reasoning")
                                }
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
        val newModel = ModelInfo(
            name = name.ifEmpty { spec?.note ?: id },
            id = id,
            description = spec?.note ?: "Custom model",
            enabled = true,
            type = spec?.type?.name?.lowercase() ?: "chat",
            contextLength = spec?.contextLength ?: 8192,
            capabilities = buildList {
                spec?.capabilities?.let { caps ->
                    if (caps.vision) add("vision")
                    if (caps.internet) add("internet")
                    if (caps.reasoning) add("reasoning")
                }
            }
        )
        _providerModels.update { it + newModel }
        saveEnabledModels(_providerModels.value)
    }

    fun toggleSkill(id: String) {
        _skills.update { skills ->
            val updated = skills.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
            saveEnabledSkills(updated)
            updated
        }
    }

    private fun saveEnabledSkills(skills: List<SkillInfo>) {
        val enabled = skills.filter { it.enabled }.map { it.id }.toSet()
        prefs.edit().putStringSet("enabled_skills", enabled).apply()
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

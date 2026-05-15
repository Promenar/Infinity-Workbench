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
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.MODEL_SPECS
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

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
    val models: List<ModelStat>,
    val estimated: Boolean = false
)

data class ModelStat(
    val name: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cost: Double,
    val estimated: Boolean = false
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

// ProviderListItem 已迁移至 data/model/ProviderModels.kt，此处通过 import 引入。
// 保留此注释以防止 git diff 混乱。

class SettingsViewModel(
    application: Application,
    private val vectorRepository: IVectorRepository,
    private val tokenStatsRepository: ITokenStatsRepository,
    private val mcpSkillRegistry: McpSkillRegistry? = null
) : ViewModel() {

    private val app = application as NexaraApplication
    private val prefs: SharedPreferences =
        application.getSharedPreferences("nexara_settings", 0)

    /** 统一单例数据源 — 所有提供商/模型操作均通过 ProviderManager */
    private val pm: ProviderManager = ProviderManager.getInstance()

    val providerModels: StateFlow<List<ModelInfo>> = pm.providerModels

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

    val providers: StateFlow<List<ProviderListItem>> = pm.providers

    private val _selectedSettingsTab = MutableStateFlow(0) // 0: App, 1: Provider
    val selectedSettingsTab: StateFlow<Int> = _selectedSettingsTab.asStateFlow()

    fun setSelectedSettingsTab(index: Int) {
        _selectedSettingsTab.value = index
    }

    val currentModelSummary: StateFlow<String> = pm.currentModelSummary

    private val _activeSourcesCount = MutableStateFlow(0)
    val activeSourcesCount: StateFlow<Int> = _activeSourcesCount.asStateFlow()

    private val _tokenCostThisMonth = MutableStateFlow("$0.00")
    val tokenCostThisMonth: StateFlow<String> = _tokenCostThisMonth.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    val summaryModelId: StateFlow<String> = pm.summaryModelId
    val imageModelId: StateFlow<String> = pm.imageModelId
    val embeddingModelId: StateFlow<String> = pm.embeddingModelId
    val rerankModelId: StateFlow<String> = pm.rerankModelId

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
        loadTokenStats()
        loadKnowledgeStats()
        loadSkills()
        observeSkills()
        // 提供商和模型已由 ProviderManager 在 init 时加载
    }

    private fun observeSkills() {
        viewModelScope.launch {
            app.skillRepository.getAllCustomSkills().collectLatest { all ->
                // Filter out any skills that might have IDs matching preset skills to avoid duplication in UI
                val presetIds = setOf("web_search", "calculator", "create_tool", "image_generation")
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
    }

    fun refreshProviders() {
        pm.refreshAll()
    }

    /** 刷新模型列表（触发 ProviderManager 重新加载 + 远程获取） */
    fun refreshProviderModels() {
        pm.loadModels()
        refreshModels()
    }

    /**
     * 根据 providerId 获取完整提供商配置（用于编辑回填）。
     * - "default" → 主提供商
     * - "extra_N" → 额外提供商
     */
    fun getProviderConfig(providerId: String): ProviderConfig? {
        return pm.getProviderConfig(providerId)
    }

    private fun loadTokenStats() {
        viewModelScope.launch {
            try {
                val totalUsage = tokenStatsRepository.getTotalUsage()
                val byModel = tokenStatsRepository.getUsageByModel()
                val config = pm.getMainProviderConfig()
                val providerName = config?.name ?: "Provider"

                val modelStats = byModel.map { stats ->
                    val spec = com.promenar.nexara.data.model.findModelSpec(stats.modelId)
                    val name = spec?.note ?: stats.modelId
                    ModelStat(
                        name = name,
                        inputTokens = stats.usage.inputTokens,
                        outputTokens = stats.usage.outputTokens,
                        cost = 0.0,
                        estimated = stats.usage.estimated
                    )
                }

                _tokenStats.value = if (modelStats.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ProviderStats(
                            name = providerName,
                            totalTokens = totalUsage.totalTokens,
                            cost = 0.0,
                            models = modelStats,
                            estimated = totalUsage.estimated
                        )
                    )
                }
                _tokenCostThisMonth.value = "$%.2f".format(0.0)
            } catch (_: Exception) {
                _tokenStats.value = emptyList()
            }
        }
    }

    private fun loadSkills() {
        val enabledSet = prefs.getStringSet("enabled_skills", setOf(
            "web_search", "calculator", "create_tool",
            "file_read", "file_list", "file_search", "exec_js"
        ))
        _skills.value = listOf(
            SkillInfo("web_search", app.getString(R.string.skill_web_search), app.getString(R.string.skill_web_search_desc), enabledSet?.contains("web_search") ?: true),
            SkillInfo("search_tavily", app.getString(R.string.skill_tavily), app.getString(R.string.skill_tavily_desc), enabledSet?.contains("search_tavily") ?: true),
            SkillInfo("search_searxng", app.getString(R.string.skill_searxng), app.getString(R.string.skill_searxng_desc), enabledSet?.contains("search_searxng") ?: true),
            SkillInfo("calculator", app.getString(R.string.skill_calculator), app.getString(R.string.skill_calculator_desc), enabledSet?.contains("calculator") ?: true),
            SkillInfo("create_tool", app.getString(R.string.skill_create_tool), app.getString(R.string.skill_create_tool_desc), enabledSet?.contains("create_tool") ?: true),
            SkillInfo("image_generation", app.getString(R.string.skill_image_generation), app.getString(R.string.skill_image_generation_desc), enabledSet?.contains("image_generation") ?: true),
            SkillInfo("file_read", "File Read", "Read file contents from workspace", enabledSet?.contains("file_read") ?: true),
            SkillInfo("file_write", "File Write", "Write/create files in workspace", enabledSet?.contains("file_write") ?: true),
            SkillInfo("file_list", "List Directory", "List workspace directory contents", enabledSet?.contains("file_list") ?: true),
            SkillInfo("file_search", "Search Files", "Search files by name pattern", enabledSet?.contains("file_search") ?: true),
            SkillInfo("exec_js", "JS Sandbox", "Execute JavaScript in a sandbox for calculations and data processing", enabledSet?.contains("exec_js") ?: true)
        )
    }

    private fun loadKnowledgeStats() {
        _activeSourcesCount.value = 0
    }

    fun updateUserName(name: String) {
        _userName.value = name
        prefs.edit().putString("user_name", name).apply()
    }

    fun updateUserAvatar(uriStr: String?) {
        if (uriStr == null) {
            _userAvatar.value = null
            prefs.edit().remove("user_avatar").apply()
            return
        }

        viewModelScope.launch {
            val localPath = saveAvatarToInternalStorage(Uri.parse(uriStr))
            if (localPath != null) {
                _userAvatar.value = localPath
                prefs.edit().putString("user_avatar", localPath).apply()
            }
        }
    }

    private fun saveAvatarToInternalStorage(uri: Uri): String? {
        return try {
            val avatarDir = File(app.filesDir, "avatars")
            if (!avatarDir.exists()) avatarDir.mkdirs()
            
            val outFile = File(avatarDir, "user_avatar_${System.currentTimeMillis()}.jpg")
            
            // 清理旧头像
            avatarDir.listFiles()?.forEach { it.delete() }

            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
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

    fun deleteProvider(providerId: String) = pm.deleteProvider(providerId)

    fun addProvider(item: ProviderListItem) {
        pm.addProvider(item)
        refreshModels()
    }

    fun updateModel(updatedModel: ModelInfo) = pm.updateModel(updatedModel)

    fun toggleModel(id: String) = pm.toggleModel(id)

    fun refreshModels() {
        viewModelScope.launch {
            _isFetchingModels.value = true
            try {
                val fetchedIds = app.llmProvider.listModels()
                if (fetchedIds.isNotEmpty()) {
                    val currentModels = pm.providerModels.value.toMutableList()
                    val existingIds = currentModels.map { it.id }.toSet()
                    val newModels = fetchedIds.filter { it !in existingIds }.map { id ->
                        val spec = com.promenar.nexara.data.model.findModelSpec(id)
                        val type = spec?.type?.name?.lowercase() ?: "chat"
                        ModelInfo(
                            name = spec?.note ?: id, id = id,
                            description = spec?.note ?: "Fetched model",
                            enabled = false, type = type,
                            contextLength = spec?.contextLength ?: 8192,
                            providerName = pm.providers.value.firstOrNull { it.id == "default" }?.name ?: "Cloud",
                            capabilities = pm.buildModelCapabilities(type, spec)
                        )
                    }
                    newModels.forEach { pm.addModel(it) }
                }
            } catch (_: Exception) { } finally {
                _isFetchingModels.value = false
            }
        }
    }

    fun disableAllModels() = pm.disableAllModels()

    fun deleteAllModels() = pm.deleteAllModels()

    fun addCustomModel(id: String, name: String) {
        val spec = com.promenar.nexara.data.model.findModelSpec(id)
        val type = spec?.type?.name?.lowercase() ?: "chat"
        val newModel = ModelInfo(
            name = name.ifEmpty { spec?.note ?: id }, id = id,
            description = spec?.note ?: "Custom model",
            enabled = true, type = type,
            contextLength = spec?.contextLength ?: 8192,
            providerName = pm.providers.value.firstOrNull { it.id == "default" }?.name ?: "Cloud",
            capabilities = pm.buildModelCapabilities(type, spec)
        )
        pm.addModel(newModel)
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
                id = "mcp_${IdGenerator.uuid()}",
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
                id = id ?: IdGenerator.uuid(),
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
                mcpSkillRegistry?.updateMcpTools(server.name, tools, server.url)
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
        viewModelScope.launch {
            try {
                tokenStatsRepository.resetStats()
                _tokenStats.value = emptyList()
            } catch (_: Exception) {
                _tokenStats.value = emptyList()
            }
        }
    }


    fun refreshAll() {
        loadAll()
    }

    fun deleteModel(id: String) = pm.deleteModel(id)

    fun setPresetModel(type: String, modelId: String) = pm.setPresetModel(type, modelId)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return SettingsViewModel(application, app.vectorRepository, app.tokenStatsRepository, app.mcpSkillRegistry) as T
                }
            }
    }
}

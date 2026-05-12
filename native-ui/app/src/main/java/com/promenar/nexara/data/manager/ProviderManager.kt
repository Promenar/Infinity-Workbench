package com.promenar.nexara.data.manager

import android.app.Application
import android.content.SharedPreferences
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.settings.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 统一提供商与模型管理单例 — 全站唯一数据源。
 *
 * 替代原先散落在 SettingsViewModel 和 NexaraApplication 中的提供商
 * CRUD、模型持久化、预设模型管理逻辑。
 */
class ProviderManager private constructor(private val app: Application) {

    // ── SharedPreferences ────────────────────────────────────────────
    private val providerPrefs: SharedPreferences =
        app.getSharedPreferences("nexara_provider", 0)
    private val settingsPrefs: SharedPreferences =
        app.getSharedPreferences("nexara_settings", 0)

    // ── StateFlow: 提供商列表 ────────────────────────────────────────
    private val _providers = MutableStateFlow<List<ProviderListItem>>(emptyList())
    val providers: StateFlow<List<ProviderListItem>> = _providers.asStateFlow()

    // ── StateFlow: 模型列表 ──────────────────────────────────────────
    private val _providerModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val providerModels: StateFlow<List<ModelInfo>> = _providerModels.asStateFlow()

    // ── StateFlow: 4 个预设模型 ──────────────────────────────────────
    private val _summaryModelId = MutableStateFlow("")
    val summaryModelId: StateFlow<String> = _summaryModelId.asStateFlow()

    private val _imageModelId = MutableStateFlow("")
    val imageModelId: StateFlow<String> = _imageModelId.asStateFlow()

    private val _embeddingModelId = MutableStateFlow("")
    val embeddingModelId: StateFlow<String> = _embeddingModelId.asStateFlow()

    private val _rerankModelId = MutableStateFlow("")
    val rerankModelId: StateFlow<String> = _rerankModelId.asStateFlow()

    // ── StateFlow: 当前模型摘要 ──────────────────────────────────────
    private val _currentModelSummary = MutableStateFlow("")
    val currentModelSummary: StateFlow<String> = _currentModelSummary.asStateFlow()

    // ── 初始化 ───────────────────────────────────────────────────────
    init {
        loadProviders()
        loadModels()
        loadPresetModels()
    }

    // ── 主提供商配置读写 ─────────────────────────────────────────────

    /**
     * 写入主提供商配置（SharedPreferences "nexara_provider"）。
     */
    fun updateMainProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        apiKey: String,
        model: String,
        name: String? = null
    ) {
        providerPrefs.edit()
            .putString("protocol_id", protocolType::class.simpleName)
            .putString("protocol_id_name", protocolType.displayName) // 新增：人类可读协议名
            .putString("base_url", baseUrl)
            .putString("api_key", apiKey)
            .putString("model", model)
            .apply()
        if (name != null) {
            providerPrefs.edit().putString("provider_name", name).apply()
        }
        // 重建提供商列表
        loadProviders()
    }

    /**
     * 读取主提供商完整配置。兼容旧 ProtocolId 枚举名。
     */
    fun getMainProviderConfig(): ProviderConfig? {
        val protocolName = providerPrefs.getString("protocol_id", null) ?: return null
        val protocolType = ProtocolType.fromLegacyName(protocolName)
        return ProviderConfig(
            protocolType = protocolType,
            baseUrl = providerPrefs.getString("base_url", "") ?: "",
            apiKey = providerPrefs.getString("api_key", "") ?: "",
            model = providerPrefs.getString("model", "") ?: "",
            name = providerPrefs.getString("provider_name", null)
        )
    }

    // ── 提供商查询（单个，用于编辑回填）───────────────────────────────

    /**
     * 根据 providerId 获取完整配置。
     * - "default" → 主提供商配置
     * - "extra_N" → 额外提供商配置
     */
    fun getProviderConfig(providerId: String): ProviderConfig? {
        if (providerId == "default") return getMainProviderConfig()
        if (providerId.startsWith("extra_")) {
            val index = providerId.removePrefix("extra_")
            val prefix = "extra_provider_$index"
            val protocolName = settingsPrefs.getString("${prefix}_protocol", null) ?: return null
            return ProviderConfig(
                protocolType = ProtocolType.fromLegacyName(protocolName),
                baseUrl = settingsPrefs.getString("${prefix}_base_url", "") ?: "",
                apiKey = settingsPrefs.getString("${prefix}_api_key", "") ?: "",
                model = settingsPrefs.getString("${prefix}_model", "") ?: "",
                name = settingsPrefs.getString("${prefix}_name", null)
            )
        }
        return null
    }

    // ── 提供商列表 ───────────────────────────────────────────────────

    fun loadProviders() {
        val items = mutableListOf<ProviderListItem>()
        val config = getMainProviderConfig()
        if (config != null && config.apiKey.isNotBlank()) {
            val typeName = config.protocolType.displayName
            items.add(
                ProviderListItem(
                    id = "default",
                    name = config.name ?: typeName,
                    typeName = typeName,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    protocolType = config.protocolType,
                    apiKey = config.apiKey
                )
            )
            _currentModelSummary.value = config.model
        }
        val count = settingsPrefs.getInt("extra_providers_count", 0)
        for (i in 0 until count) {
            val prefix = "extra_provider_$i"
            val name = settingsPrefs.getString("${prefix}_name", null) ?: continue
            val protoName = settingsPrefs.getString("${prefix}_protocol", null)
                ?: settingsPrefs.getString("${prefix}_type", null) ?: ""
            items.add(
                ProviderListItem(
                    id = "extra_$i",
                    name = name,
                    typeName = protoName,
                    baseUrl = settingsPrefs.getString("${prefix}_base_url", "") ?: "",
                    model = settingsPrefs.getString("${prefix}_model", "") ?: "",
                    protocolType = ProtocolType.fromLegacyName(protoName),
                    apiKey = settingsPrefs.getString("${prefix}_api_key", "") ?: ""
                )
            )
        }
        _providers.value = items
    }

    fun addProvider(item: ProviderListItem) {
        _providers.update { it + item }
        persistExtraProviders()
    }

    fun deleteProvider(providerId: String) {
        _providers.update { it.filter { p -> p.id != providerId } }
        persistExtraProviders()
    }

    private fun persistExtraProviders() {
        val extras = _providers.value.filter { it.id != "default" }
        settingsPrefs.edit()
            .putInt("extra_providers_count", extras.size)
            .apply()
        extras.forEachIndexed { index, item ->
            val prefix = "extra_provider_$index"
            settingsPrefs.edit()
                .putString("${prefix}_name", item.name)
                .putString("${prefix}_protocol", item.protocolType::class.simpleName)
                .putString("${prefix}_base_url", item.baseUrl)
                .putString("${prefix}_model", item.model)
                .putString("${prefix}_api_key", item.apiKey)
                .apply()
        }
    }

    // ── 模型管理 ────────────────────────────────────────────────────

    fun loadModels() {
        val allIds = settingsPrefs.getStringSet("all_models", null)
        if (allIds == null) {
            _providerModels.value = emptyList()
            return
        }
        val enabledSet = settingsPrefs.getStringSet("enabled_models", emptySet()) ?: emptySet()
        val models = allIds.map { id ->
            val prefix = "model_info_$id"
            val caps = settingsPrefs.getStringSet("${prefix}_caps", emptySet()) ?: emptySet()
            ModelInfo(
                name = settingsPrefs.getString("${prefix}_name", id) ?: id,
                id = id,
                description = "",
                enabled = enabledSet.contains(id),
                type = settingsPrefs.getString("${prefix}_type", "chat") ?: "chat",
                contextLength = settingsPrefs.getInt("${prefix}_context", 8192),
                capabilities = caps.toList(),
                providerName = settingsPrefs.getString("${prefix}_provider", "Cloud") ?: "Cloud"
            )
        }.sortedByDescending { it.enabled }
        _providerModels.value = models
    }

    fun toggleModel(id: String) {
        _providerModels.update { models ->
            models.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        }
        persistModels()
    }

    fun addModel(model: ModelInfo) {
        _providerModels.update { it + model }
        persistModels()
    }

    fun updateModel(updated: ModelInfo) {
        _providerModels.update { models ->
            models.map { if (it.id == updated.id) updated else it }
        }
        persistModels()
    }

    fun deleteModel(id: String) {
        _providerModels.update { it.filter { m -> m.id != id } }
        persistModels()
    }

    fun disableAllModels() {
        _providerModels.update { it.map { m -> m.copy(enabled = false) } }
        persistModels()
    }

    fun deleteAllModels() {
        _providerModels.value = emptyList()
        settingsPrefs.edit()
            .remove("enabled_models")
            .remove("all_models")
            .apply()
    }

    private fun persistModels() {
        val models = _providerModels.value
        val allIds = models.map { it.id }.toSet()
        val enabled = models.filter { it.enabled }.map { it.id }.toSet()
        settingsPrefs.edit()
            .putStringSet("all_models", allIds)
            .putStringSet("enabled_models", enabled)
            .apply()
        models.forEach { model ->
            val prefix = "model_info_${model.id}"
            settingsPrefs.edit()
                .putString("${prefix}_name", model.name)
                .putString("${prefix}_type", model.type)
                .putInt("${prefix}_context", model.contextLength)
                .putStringSet("${prefix}_caps", model.capabilities.toSet())
                .putString("${prefix}_provider", model.providerName)
                .apply()
        }
    }

    // ── 预设模型管理 ────────────────────────────────────────────────

    fun loadPresetModels() {
        _summaryModelId.value = settingsPrefs.getString("preset_summary_model", "") ?: ""
        _imageModelId.value = settingsPrefs.getString("preset_image_model", "") ?: ""
        _embeddingModelId.value = settingsPrefs.getString("preset_embedding_model", "") ?: ""
        _rerankModelId.value = settingsPrefs.getString("preset_rerank_model", "") ?: ""
    }

    fun setPresetModel(type: String, modelId: String) {
        val key = when (type) {
            "summary" -> {
                _summaryModelId.value = modelId
                _currentModelSummary.value = modelId
                "preset_summary_model"
            }
            "image" -> {
                _imageModelId.value = modelId
                "preset_image_model"
            }
            "embedding" -> {
                _embeddingModelId.value = modelId
                "preset_embedding_model"
            }
            "rerank" -> {
                _rerankModelId.value = modelId
                "preset_rerank_model"
            }
            else -> return
        }
        settingsPrefs.edit().putString(key, modelId).apply()
    }

    // ── 全局刷新 ────────────────────────────────────────────────────

    fun refreshAll() {
        loadProviders()
        loadModels()
        loadPresetModels()
    }

    // ── 单例 ────────────────────────────────────────────────────────

    companion object {
        @Volatile
        private var INSTANCE: ProviderManager? = null

        fun init(app: Application): ProviderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderManager(app.applicationContext as Application).also {
                    INSTANCE = it
                }
            }
        }

        fun getInstance(): ProviderManager {
            return INSTANCE ?: throw IllegalStateException(
                "ProviderManager not initialized. Call ProviderManager.init(app) first."
            )
        }
    }
}

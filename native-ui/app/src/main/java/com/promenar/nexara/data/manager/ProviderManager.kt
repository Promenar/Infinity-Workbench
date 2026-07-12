package com.promenar.nexara.data.manager

import android.app.Application
import android.content.SharedPreferences
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.ProviderSummary
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.security.AndroidKeystoreSecretStore
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.ui.settings.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 统一提供商与模型管理单例 — 全站唯一数据源。
 *
 * 替代原先散落在 SettingsViewModel 和 NexaraApplication 中的提供商
 * CRUD、模型持久化、预设模型管理逻辑。
 */
class ProviderManager private constructor(
    private val app: Application,
    private val secretStore: SecretStore,
) {

    // ── SharedPreferences ────────────────────────────────────────────
    private val providerPrefs: SharedPreferences =
        app.getSharedPreferences("nexara_provider", 0)
    private val settingsPrefs: SharedPreferences =
        app.getSharedPreferences("nexara_settings", 0)

    // ── StateFlow: 提供商列表 ────────────────────────────────────────
    private val _providers = MutableStateFlow<List<ProviderListItem>>(emptyList())
    val providers: StateFlow<List<ProviderListItem>> = _providers.asStateFlow()

    private val _configurationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configurationChanges: SharedFlow<Unit> = _configurationChanges

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
        migrateLegacySecrets()
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
        credentialUpdate: CredentialUpdate,
        model: String,
        name: String? = null
    ) {
        val existingType = providerPrefs.getString("protocol_id", null)
            ?.let(ProtocolType::fromLegacyName)
        validateCredentialTransition(existingType, protocolType, credentialUpdate)
        providerPrefs.edit()
            .putString("protocol_id", protocolType::class.simpleName)
            .putString("protocol_id_name", protocolType.displayName) // 新增：人类可读协议名
            .putString("base_url", baseUrl)
            .putString("model", model)
            .apply()
        applyCredentialUpdate("default", protocolType, credentialUpdate)
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
            apiKey = readSecret(SecretCatalog.providerApiKey("default")),
            model = providerPrefs.getString("model", "") ?: "",
            name = providerPrefs.getString("provider_name", null),
            vertexServiceAccountJson = readSecret(SecretCatalog.vertexServiceAccount("default")),
        )
    }

    // ── 提供商查询 ──────────────────────────────────────────────────

    /**
     * 根据模型 ID 反向查找其所属提供商的完整配置。
     *
     * 查找策略：
     * 1. 在模型列表中按 ID 匹配
     * 2. 使用模型的 providerId 精确查找
     * 3. 若 providerId 为空，按 providerName 在提供商列表中模糊匹配
     */
    fun getProviderConfigByModelId(modelId: String): ProviderConfig? {
        val model = _providerModels.value.find { it.id == modelId }
        if (model == null) {
            android.util.Log.w("ProviderManager", "[getProviderConfigByModelId] 模型未找到: modelId=$modelId, 已加载模型IDs=${_providerModels.value.map { it.id }}")
            return null
        }
        val pid = model.providerId
            ?: _providers.value.find { it.name == model.providerName }?.id
        if (pid == null) {
            android.util.Log.w("ProviderManager", "[getProviderConfigByModelId] providerId 无法解析: modelId=$modelId providerId=null providerName=${model.providerName}, 已加载提供商=${_providers.value.map { "${it.id}->${it.name}" }}")
            return null
        }
        val config = getProviderConfig(pid)
        if (config == null) {
            android.util.Log.w("ProviderManager", "[getProviderConfigByModelId] 提供商配置未找到: modelId=$modelId pid=$pid")
        } else if (config.baseUrl.isBlank()) {
            android.util.Log.w("ProviderManager", "[getProviderConfigByModelId] baseUrl 为空: modelId=$modelId pid=$pid protocolType=${config.protocolType::class.simpleName}")
        }
        return config
    }

    /**
     * 根据 providerId 获取完整配置。
     * - "default" → 主提供商配置
     * - "extra_N" → 额外提供商配置（新格式按索引，也支持按真实 ID 查找）
     */
    fun getProviderConfig(providerId: String): ProviderConfig? {
        if (providerId == "default") return getMainProviderConfig()
        val count = settingsPrefs.getInt("extra_providers_count", 0)
        for (i in 0 until count) {
            val prefix = "extra_provider_$i"
            val resolvedId = resolveExtraProviderId(i)
            if (resolvedId == providerId) {
                val protocolName = settingsPrefs.getString("${prefix}_protocol", null)
                    ?: settingsPrefs.getString("${prefix}_type", null) ?: return null
                return ProviderConfig(
                    protocolType = ProtocolType.fromLegacyName(protocolName),
                    baseUrl = settingsPrefs.getString("${prefix}_base_url", "") ?: "",
                    apiKey = readSecret(SecretCatalog.providerApiKey(resolvedId)),
                    model = settingsPrefs.getString("${prefix}_model", "") ?: "",
                    name = settingsPrefs.getString("${prefix}_name", null),
                    vertexServiceAccountJson = readSecret(
                        SecretCatalog.vertexServiceAccount(resolvedId)
                    ),
                )
            }
        }
        return null
    }

    /** 仅供当前可见配置页临时显示；调用方取得数组所有权并负责及时清零。 */
    fun revealCredential(providerId: String, vertex: Boolean): CharArray? {
        val id = if (vertex) {
            SecretCatalog.vertexServiceAccount(providerId)
        } else {
            SecretCatalog.providerApiKey(providerId)
        }
        val bytes = secretStore.get(id) ?: return null
        return try {
            bytes.toString(Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    // ── 提供商列表 ───────────────────────────────────────────────────

    fun loadProviders() {
        val items = mutableListOf<ProviderListItem>()
        val config = getMainProviderConfig()
        if (config != null && (config.apiKey.isNotBlank() || config.vertexServiceAccountJson.isNotBlank() || config.protocolType is ProtocolType.Local)) {
            val typeName = config.protocolType.displayName
            items.add(
                ProviderListItem(
                    id = "default",
                    name = config.name ?: typeName,
                    typeName = typeName,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    protocolType = config.protocolType,
                    hasApiKey = config.apiKey.isNotBlank(),
                    hasVertexCredentials = config.vertexServiceAccountJson.isNotBlank(),
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
            // 优先使用持久化的真实 ID，回退到索引生成（兼容旧数据）
            val realId = resolveExtraProviderId(i)
            items.add(
                ProviderListItem(
                    id = realId,
                    name = name,
                    typeName = protoName,
                    baseUrl = settingsPrefs.getString("${prefix}_base_url", "") ?: "",
                    model = settingsPrefs.getString("${prefix}_model", "") ?: "",
                    protocolType = ProtocolType.fromLegacyName(protoName),
                    hasApiKey = secretStore.contains(SecretCatalog.providerApiKey(realId)),
                    hasVertexCredentials = secretStore.contains(SecretCatalog.vertexServiceAccount(realId)),
                )
            )
        }
        _providers.value = items
    }

    fun addProvider(
        item: ProviderListItem,
        credentialUpdate: CredentialUpdate = CredentialUpdate.Preserve,
    ) {
        require(item.id != "default" && getProviderConfig(item.id) == null && _providers.value.none { it.id == item.id }) {
            "Provider ID 已存在: ${item.id}"
        }
        applyCredentialUpdate(item.id, item.protocolType, credentialUpdate)
        _providers.update { it + item }
        persistExtraProviders()
        loadProviders()
        _configurationChanges.tryEmit(Unit)
    }

    fun updateExtraProvider(
        id: String,
        item: ProviderListItem,
        credentialUpdate: CredentialUpdate = CredentialUpdate.Preserve,
    ) {
        val existingType = getProviderConfig(id)?.protocolType
        validateCredentialTransition(existingType, item.protocolType, credentialUpdate)
        if (id != item.id) {
            moveSecret(SecretCatalog.providerApiKey(id), SecretCatalog.providerApiKey(item.id))
            moveSecret(SecretCatalog.vertexServiceAccount(id), SecretCatalog.vertexServiceAccount(item.id))
        }
        applyCredentialUpdate(item.id, item.protocolType, credentialUpdate)
        _providers.update { list ->
            list.map { if (it.id == id) item else it }
        }
        persistExtraProviders()
        loadProviders()
        _configurationChanges.tryEmit(Unit)
    }

    fun deleteProvider(providerId: String) {
        secretStore.remove(SecretCatalog.providerApiKey(providerId))
        secretStore.remove(SecretCatalog.vertexServiceAccount(providerId))
        _providers.update { it.filter { p -> p.id != providerId } }
        persistExtraProviders()
        _configurationChanges.tryEmit(Unit)
    }

    private fun persistExtraProviders() {
        val extras = _providers.value.filter { it.id != "default" }
        settingsPrefs.edit()
            .putInt("extra_providers_count", extras.size)
            .putString("extra_providers_ids", extras.joinToString(",") { it.id })
            .apply()
        extras.forEachIndexed { index, item ->
            val prefix = "extra_provider_$index"
            settingsPrefs.edit()
                .putString("${prefix}_name", item.name)
                .putString("${prefix}_protocol", item.protocolType::class.simpleName)
                .putString("${prefix}_base_url", item.baseUrl)
                .putString("${prefix}_model", item.model)
                .putString("${prefix}_id", item.id)
                .apply()
        }
    }

    fun getProviderSummary(providerId: String): ProviderSummary? {
        val config = getProviderConfig(providerId) ?: return null
        return ProviderSummary(
            id = providerId,
            name = config.name ?: config.protocolType.displayName,
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            model = config.model,
            hasApiKey = secretStore.contains(SecretCatalog.providerApiKey(providerId)),
            hasVertexCredentials = secretStore.contains(SecretCatalog.vertexServiceAccount(providerId)),
        )
    }

    private fun migrateLegacySecrets() {
        migratePreference(providerPrefs, "api_key", SecretCatalog.providerApiKey("default"))
        migratePreference(providerPrefs, "embedding_api_key", SecretCatalog.embeddingApiKey)
        migratePreference(providerPrefs, "vertex_service_account_json", SecretCatalog.vertexServiceAccount("default"))

        val count = settingsPrefs.getInt("extra_providers_count", 0)
        for (index in 0 until count) {
            val prefix = "extra_provider_$index"
            val providerId = resolveExtraProviderId(index)
            migratePreference(settingsPrefs, "${prefix}_api_key", SecretCatalog.providerApiKey(providerId))
            migratePreference(
                settingsPrefs,
                "${prefix}_vertex_service_account_json",
                SecretCatalog.vertexServiceAccount(providerId),
            )
        }

        val searchPrefs = app.getSharedPreferences("nexara_search", 0)
        migratePreference(searchPrefs, "tavily_api_key", SecretCatalog.tavilyApiKey)
        val backupPrefs = app.getSharedPreferences("nexara_backup_settings", 0)
        migratePreference(backupPrefs, "webdav_pass", SecretCatalog.webDavPassword)
        migratePreference(backupPrefs, "automatic_backup_password", SecretCatalog.automaticBackupPassword)
    }

    private fun migratePreference(prefs: SharedPreferences, key: String, secretId: SecretId) {
        if (!prefs.contains(key)) return
        val plaintext = prefs.getString(key, null) ?: return
        if (plaintext.isNotEmpty() && !secretStore.contains(secretId)) {
            val bytes = plaintext.toByteArray(Charsets.UTF_8)
            try {
                secretStore.put(secretId, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        check(prefs.edit().remove(key).commit()) { "旧明文凭证删除失败" }
    }

    private fun readSecret(id: SecretId): String =
        secretStore.get(id)?.toString(Charsets.UTF_8).orEmpty()

    private fun writeSecret(id: SecretId, value: String) {
        if (value.isBlank()) secretStore.remove(id)
        else secretStore.put(id, value.toByteArray(Charsets.UTF_8))
        _configurationChanges.tryEmit(Unit)
    }

    private fun writeProviderCredential(providerId: String, protocolType: ProtocolType, credential: String) {
        if (protocolType is ProtocolType.Google_VertexAI) {
            writeSecret(SecretCatalog.vertexServiceAccount(providerId), credential)
            secretStore.remove(SecretCatalog.providerApiKey(providerId))
        } else {
            writeSecret(SecretCatalog.providerApiKey(providerId), credential)
            secretStore.remove(SecretCatalog.vertexServiceAccount(providerId))
        }
    }

    private fun applyCredentialUpdate(
        providerId: String,
        protocolType: ProtocolType,
        update: CredentialUpdate,
    ) {
        when (update) {
            CredentialUpdate.Preserve -> Unit
            CredentialUpdate.Clear -> {
                secretStore.remove(SecretCatalog.providerApiKey(providerId))
                secretStore.remove(SecretCatalog.vertexServiceAccount(providerId))
                _configurationChanges.tryEmit(Unit)
            }
            is CredentialUpdate.Replace -> writeProviderCredential(providerId, protocolType, update.value)
        }
    }

    private fun resolveExtraProviderId(index: Int): String {
        val prefix = "extra_provider_$index"
        settingsPrefs.getString("${prefix}_id", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return settingsPrefs.getString("extra_providers_ids", null)
            ?.split(',')
            ?.getOrNull(index)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "extra_$index"
    }

    private fun validateCredentialTransition(
        existingType: ProtocolType?,
        newType: ProtocolType,
        update: CredentialUpdate,
    ) {
        if (update !is CredentialUpdate.Preserve || existingType == null) return
        val existingUsesVertexJson = existingType is ProtocolType.Google_VertexAI
        val newUsesVertexJson = newType is ProtocolType.Google_VertexAI
        require(existingUsesVertexJson == newUsesVertexJson) {
            "跨凭证类别切换必须 Replace 或 Clear"
        }
    }

    private fun moveSecret(from: SecretId, to: SecretId) {
        secretStore.get(from)?.let { value ->
            secretStore.put(to, value)
            secretStore.remove(from)
        }
    }

    // ── 模型管理 ────────────────────────────────────────────────────

    fun loadModels() {
        val allIds = settingsPrefs.getStringSet("all_models", null)
        if (allIds == null) {
            _providerModels.value = emptyList()
            return
        }
        // 优先从有序 ID 列表恢复顺序（跨 session 稳定排序）
        val orderedIds = settingsPrefs.getString("all_models_order", null)
            ?.split(",")?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        val idOrder = if (orderedIds != null && orderedIds.size == allIds.size) {
            // 以持久化顺序为主，同时兼容新增/缺失的 ID
            val idIndex = orderedIds.withIndex().associate { (i, id) -> id to i }
            allIds.sortedBy { idIndex[it] ?: Int.MAX_VALUE }
        } else {
            allIds.toList()
        }
        val enabledSet = settingsPrefs.getStringSet("enabled_models", emptySet()) ?: emptySet()
        var migrated = false
        // 稳定排序：enabled 降序 → 持久化顺序 → name 字母序
        val models = idOrder.map { id ->
            val prefix = "model_info_$id"
            val storedName = settingsPrefs.getString("${prefix}_name", id) ?: id
            val storedCaps = settingsPrefs.getStringSet("${prefix}_caps", emptySet()) ?: emptySet()
            val type = settingsPrefs.getString("${prefix}_type", "chat") ?: "chat"
            val contextLength = settingsPrefs.getInt("${prefix}_context", 8192)
            val providerName = settingsPrefs.getString("${prefix}_provider", "Cloud") ?: "Cloud"
            val storedProviderId = settingsPrefs.getString("${prefix}_provider_id", null)
            val enabled = enabledSet.contains(id)
            val maxOutput = settingsPrefs.getInt("${prefix}_maxoutput", 0)
            val cutoff = settingsPrefs.getString("${prefix}_cutoff", null)

            var model = ModelInfo(
                name = storedName,
                id = id,
                description = "",
                enabled = enabled,
                type = type,
                contextLength = contextLength,
                capabilities = storedCaps.toList(),
                providerName = providerName,
                providerId = storedProviderId,
                maxOutputTokens = maxOutput,
                knowledgeCutoff = cutoff
            )

            // 迁移逻辑：自动修复名称和能力
            val hasStoredCaps = settingsPrefs.contains("${prefix}_caps")
            val hasStoredContext = settingsPrefs.contains("${prefix}_context")
            val hasStoredMaxOutput = settingsPrefs.contains("${prefix}_maxoutput")
            val hasStoredCutoff = settingsPrefs.contains("${prefix}_cutoff")

            var migratedModel = migrateModelIfNeeded(
                model,
                hasStoredCaps = hasStoredCaps,
                hasStoredContext = hasStoredContext,
                hasStoredMaxOutput = hasStoredMaxOutput,
                hasStoredCutoff = hasStoredCutoff
            )
            if (migratedModel !== model) {
                migrated = true
            }
            // 回填 providerId：旧数据未存储 providerId，按 providerName 匹配
            if (migratedModel.providerId == null && migratedModel.providerName != "Cloud") {
                val matchedPid = _providers.value.find { it.name == migratedModel.providerName }?.id
                if (matchedPid != null) {
                    migratedModel = migratedModel.copy(providerId = matchedPid)
                    migrated = true
                }
            }
            migratedModel
         }.sortedWith(compareByDescending<ModelInfo> { it.enabled }.thenBy { it.name.lowercase() })

        _providerModels.value = models

        // 如果发生了迁移，自动保存以持久化修正后的数据
        if (migrated) {
            persistModels()
        }
    }

    /**
     * 检测并修复模型的元数据（名称和能力）。
     * 针对旧版本中 name = id 或 capabilities 构建不全的问题。
     */
    private fun migrateModelIfNeeded(
        model: ModelInfo,
        hasStoredCaps: Boolean,
        hasStoredContext: Boolean,
        hasStoredMaxOutput: Boolean,
        hasStoredCutoff: Boolean
    ): ModelInfo {
        val spec = com.promenar.nexara.data.model.findModelSpec(model.id) ?: return model
        var changed = false

        // 1. 修复名称：如果是原始 ID 且 Spec 中有更好的名字，则替换
        val newName = if (model.name == model.id && spec.note?.isNotEmpty() == true) {
            changed = true
            spec.note!!
        } else model.name

        // 2. 修复能力：仅当 SharedPreferences 中没有存储过能力集时，才从 Spec 按需对齐初始化
        val newCaps = if (!hasStoredCaps) {
            val correctCaps = buildModelCapabilities(model.type, spec)
            if (model.capabilities.toSet() != correctCaps.toSet()) {
                changed = true
                correctCaps
            } else model.capabilities
        } else model.capabilities

        // 3. 修复上下文长度：仅当 SharedPreferences 中没有存储过上下文长度时，才从 Spec 按需对齐初始化
        val newContext = if (!hasStoredContext && spec.contextLength > 0 && model.contextLength != spec.contextLength) {
            changed = true
            spec.contextLength
        } else model.contextLength

        // 4. 修复最大输出 token：仅当 SharedPreferences 中没有存储过最大输出时，才从 Spec 对齐初始化
        val newMaxOutput = if (!hasStoredMaxOutput && spec.maxOutputTokens > 0 && model.maxOutputTokens == 0) {
            changed = true
            spec.maxOutputTokens
        } else model.maxOutputTokens

        // 5. 修复知识截止日期：仅当 SharedPreferences 中没有存储过截止日期时，才从 Spec 对齐初始化
        val newCutoff = if (!hasStoredCutoff && spec.knowledgeCutoff != null && model.knowledgeCutoff == null) {
            changed = true
            spec.knowledgeCutoff
        } else model.knowledgeCutoff

        return if (changed) {
            model.copy(
                name = newName,
                capabilities = newCaps,
                contextLength = newContext,
                maxOutputTokens = newMaxOutput,
                knowledgeCutoff = newCutoff
            )
        } else model
    }

    /**
     * 根据 ModelType 和 ModelSpec 构建完整的 capability 列表。
     */
    fun buildModelCapabilities(
        type: String,
        spec: com.promenar.nexara.data.model.ModelSpec?
    ): List<String> = buildList {
        // 根据 ModelType 推导基础 capability
        when (type) {
            "chat" -> add("chat")
            "reasoning" -> { add("chat"); add("reasoning") }
            "image" -> add("image")
            "embedding" -> add("embedding")
            "rerank" -> add("rerank")
            else -> add("chat")
        }
        // 从 ModelSpec.capabilities 补充细粒度能力
        spec?.capabilities?.let { caps ->
            if (caps.vision && "vision" !in this) add("vision")
            if (caps.internet && "internet" !in this) add("internet")
            if (caps.reasoning && "reasoning" !in this) add("reasoning")
            if (caps.image && "image" !in this) add("image")
            if (caps.embedding && "embedding" !in this) add("embedding")
            if (caps.rerank && "rerank" !in this) add("rerank")
            if (caps.audioInput) add("audioinput")
            if (caps.audioOutput) add("audiooutput")
            if (caps.videoUnderstanding) add("videounderstanding")
            if (caps.structuredOutput) add("structuredoutput")
            if (caps.promptCaching) add("promptcaching")
            if (caps.computerUse) add("computeruse")
        }
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
            .putString("all_models_order", models.joinToString(",") { it.id })
            .apply()
        models.forEach { model ->
            val prefix = "model_info_${model.id}"
            settingsPrefs.edit()
                .putString("${prefix}_name", model.name)
                .putString("${prefix}_type", model.type)
                .putInt("${prefix}_context", model.contextLength)
                .putStringSet("${prefix}_caps", model.capabilities.toSet())
                .putString("${prefix}_provider", model.providerName)
                .putString("${prefix}_provider_id", model.providerId)
                .putInt("${prefix}_maxoutput", model.maxOutputTokens)
                .apply()
            if (model.knowledgeCutoff != null) {
                settingsPrefs.edit()
                    .putString("${prefix}_cutoff", model.knowledgeCutoff)
                    .apply()
            }
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

        fun init(app: Application): ProviderManager =
            INSTANCE ?: init(app, AndroidKeystoreSecretStore(app))

        fun init(app: Application, secretStore: SecretStore): ProviderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderManager(app.applicationContext as Application, secretStore).also {
                    INSTANCE = it
                }
            }
        }

        internal fun createForTest(app: Application, secretStore: SecretStore): ProviderManager =
            ProviderManager(app, secretStore)

        fun getInstance(): ProviderManager {
            return INSTANCE ?: throw IllegalStateException(
                "ProviderManager not initialized. Call ProviderManager.init(app) first."
            )
        }
    }
}

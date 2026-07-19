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
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.parser.ErrorNormalizer
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.StreamChunk
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.common.status.UiStatusNotice
import com.promenar.nexara.ui.welcome.runOnboardingEndpointProbe
import com.promenar.nexara.ui.welcome.verifyOnboardingModelCandidate
import com.promenar.nexara.domain.generation.GenerationFailureCode

/**
 * `listModels` 只有模型 ID，不能据此证明未知模型具备聊天能力。
 * 已知规格优先；明显的专用模型按名称保守分类；其余保持 unknown，
 * 交由首次引导对具体模型执行最小聊天探测，避免无证据误放行。
 */
internal fun classifyFetchedModelType(
    remoteModelId: String,
    knownType: String?,
): String {
    knownType?.lowercase()?.let { return it }
    val normalized = remoteModelId.lowercase()
    return when {
        Regex("(?:^|[-_/.])(embed(?:ding)?|bge-m3)(?:$|[-_/.])").containsMatchIn(normalized) -> "embedding"
        Regex("(?:^|[-_/.])(rerank(?:er)?|bge-reranker)(?:$|[-_/.])").containsMatchIn(normalized) -> "rerank"
        Regex("(?:^|[-_/.])(image|dall-e|flux|stable-diffusion)(?:$|[-_/.])").containsMatchIn(normalized) -> "image"
        Regex("(?:^|[-_/.])(audio|speech|tts|whisper)(?:$|[-_/.])").containsMatchIn(normalized) -> "audio"
        else -> "unknown"
    }
}

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

internal fun normalizeSettingsTabIndex(index: Int): Int = index.coerceIn(0, 1)

internal fun UiStatusNotice.withFallbackWarning(usedFallback: Boolean): UiStatusNotice =
    if (usedFallback) {
        copy(
            severity = com.promenar.nexara.ui.common.status.NoticeSeverity.Warning,
            technical = "built_in_model_fallback_used",
        )
    } else {
        this
    }

sealed interface ModelTestState {
    data object Idle : ModelTestState
    data object Testing : ModelTestState
    data class Success(val latencyMs: Long) : ModelTestState {
        init {
            require(latencyMs >= 0)
        }
    }
    data class Error(
        val code: GenerationFailureCode,
        val retryAfterSeconds: Int? = null,
    ) : ModelTestState {
        override fun toString(): String = buildString {
            append("ModelTestState.Error(code=")
            append(code)
            retryAfterSeconds?.takeIf { it > 0 }?.let {
                append(", retryAfterSeconds=")
                append(it)
            }
            append(')')
        }
    }
}

/**
 * 模型连通性探测的进程内协调器。实际请求始终通过 [ProviderRequestRouter]
 * 与 [com.promenar.nexara.data.remote.UnifiedLlmClient]，不另建旁路协议客户端。
 */
internal class ProviderModelTestCoordinator(
    private val router: ProviderRequestRouter,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 15_000,
    private val nowNanos: () -> Long = System::nanoTime,
    private val beforeJobStartForTest: (String) -> Unit = {},
) {
    private val _states = MutableStateFlow<Map<String, ModelTestState>>(emptyMap())
    val states: StateFlow<Map<String, ModelTestState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private val jobsLock = Any()

    fun test(modelId: String) {
        val job: Job
        val generation: Long
        synchronized(jobsLock) {
            if (jobs[modelId] != null) return
            generation = (generations[modelId] ?: 0L) + 1
            generations[modelId] = generation
            _states.value = _states.value + (modelId to ModelTestState.Testing)
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val startedAt = nowNanos()
                    val outcome = withTimeout(timeoutMillis) { probe(modelId) }
                    val terminal = when (outcome) {
                        ProbeOutcome.Success -> ModelTestState.Success(
                            latencyMs = ((nowNanos() - startedAt) / 1_000_000L).coerceAtLeast(0),
                        )
                        is ProbeOutcome.Failed -> outcome.error
                    }
                    setState(modelId, terminal, generation)
                } catch (_: TimeoutCancellationException) {
                    setState(modelId, ModelTestState.Error(GenerationFailureCode.TIMEOUT), generation)
                } catch (cancelled: CancellationException) {
                    setState(modelId, ModelTestState.Idle, generation)
                    throw cancelled
                } catch (error: Exception) {
                    val normalized = ErrorNormalizer.normalize(error)
                    setState(modelId, ModelTestState.Error(normalized.toFailure().code), generation)
                }
            }.also { jobs[modelId] = it }
        }
        job.invokeOnCompletion {
            synchronized(jobsLock) {
                if (jobs[modelId] === job) jobs.remove(modelId)
            }
        }
        beforeJobStartForTest(modelId)
        job.start()
    }

    fun cancel(modelId: String) {
        val job: Job?
        synchronized(jobsLock) {
            job = jobs.remove(modelId)
            generations[modelId] = (generations[modelId] ?: 0L) + 1
            _states.value = _states.value + (modelId to ModelTestState.Idle)
        }
        job?.cancel()
    }

    private fun setState(modelId: String, state: ModelTestState, generation: Long) {
        synchronized(jobsLock) {
            if (generations[modelId] != generation) return
            _states.value = _states.value + (modelId to state)
        }
    }

    private suspend fun probe(modelId: String): ProbeOutcome {
        val resolved = when (val resolution = router.resolve(modelId)) {
            is ProviderResolution.Success -> resolution.value
            is ProviderResolution.Failure -> return ProbeOutcome.Failed(
                ModelTestState.Error(
                    code = when (resolution.reason) {
                        com.promenar.nexara.data.remote.ProviderResolutionError.API_KEY_MISSING,
                        com.promenar.nexara.data.remote.ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
                        -> GenerationFailureCode.AUTH
                        else -> GenerationFailureCode.INVALID_REQUEST
                    },
                ),
            )
        }
        var error: ModelTestState.Error? = null
        var sawPayload = false
        var sawDone = false
        router.createClient(resolved).sendStream(
            params = StreamTextParams(
                messages = listOf(ProtocolMessage(role = "user", content = "Reply OK.")),
                model = resolved.remoteModelId,
                maxOutputTokens = 1,
                streamTimeout = timeoutMillis,
            ),
            config = StreamConfig(),
        ).collect { chunk ->
            when (chunk) {
                is StreamChunk.Error -> {
                    if (error == null) {
                        error = ModelTestState.Error(chunk.code, chunk.retryAfterSeconds)
                    }
                }
                is StreamChunk.TextDelta -> if (chunk.content.isNotEmpty()) sawPayload = true
                is StreamChunk.Thinking -> if (chunk.content.isNotEmpty()) sawPayload = true
                is StreamChunk.Done -> sawDone = true
                else -> Unit
            }
        }
        error?.let { return ProbeOutcome.Failed(it) }
        return if (sawPayload && sawDone) ProbeOutcome.Success
        else ProbeOutcome.Failed(ModelTestState.Error(GenerationFailureCode.UNKNOWN))
    }

    private sealed interface ProbeOutcome {
        data object Success : ProbeOutcome
        data class Failed(val error: ModelTestState.Error) : ProbeOutcome
    }
}

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
        _selectedSettingsTab.value = normalizeSettingsTabIndex(index)
    }

    val currentModelSummary: StateFlow<String> = pm.currentModelSummary

    private val _activeSourcesCount = MutableStateFlow(0)
    val activeSourcesCount: StateFlow<Int> = _activeSourcesCount.asStateFlow()

    private val _tokenCostThisMonth = MutableStateFlow("$0.00")
    val tokenCostThisMonth: StateFlow<String> = _tokenCostThisMonth.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    /**
     * 同步模型后的结构化反馈：稳定 code + severity + 类型化 args。
     * UI 据 severity 着色、据 code+args 经 stringResource 双语格式化，**禁止**用展示串 startsWith 判定颜色。
     * [UiStatusNotice.technical] 仅用于日志/诊断，不直接展示给用户。
     */
    private val _modelSyncNotice = MutableStateFlow<UiStatusNotice?>(null)
    val modelSyncNotice: StateFlow<UiStatusNotice?> = _modelSyncNotice.asStateFlow()
    private val modelSyncGate = ModelSyncGate()

    private val modelTestCoordinator by lazy {
        ProviderModelTestCoordinator(app.providerRequestRouter, viewModelScope)
    }
    val modelTestStates: StateFlow<Map<String, ModelTestState>>
        get() = modelTestCoordinator.states

    fun clearSyncNotice() { _modelSyncNotice.value = null }

    fun testModel(modelId: String) = modelTestCoordinator.test(modelId)

    fun cancelModelTest(modelId: String) = modelTestCoordinator.cancel(modelId)

    val summaryModelId: StateFlow<String> = pm.summaryModelId
    val imageModelId: StateFlow<String> = pm.imageModelId
    val embeddingModelId: StateFlow<String> = pm.embeddingModelId
    val rerankModelId: StateFlow<String> = pm.rerankModelId

    private val _loopLimit = MutableStateFlow(prefs.getInt("loop_limit", 50))
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
        _loopLimit.value = prefs.getInt("loop_limit", 50)
    }

    fun refreshProviders() {
        pm.refreshAll()
    }

    /** 刷新模型列表（远程获取 + ModelSpecs 数据库回退 + 元数据更新） */
    fun refreshProviderModels(providerId: String) {
        if (!modelSyncGate.tryAcquire()) return
        _isFetchingModels.value = true
        _modelSyncNotice.value = ModelSyncNotice.loading()
        viewModelScope.launch {
            try {
                val config = pm.getProviderConfig(providerId)
                if (config == null) {
                    _modelSyncNotice.value = ModelSyncNotice.providerNotFound()
                    return@launch
                }
                val providerName = pm.providers.value.find { it.id == providerId }?.name ?: "Provider"

                // 步骤 1: 尝试远程拉取模型列表
                var fetchedIds: List<String> = emptyList()
                var usedBuiltInFallback = false
                try {
                    val tmpProvider = if (config.protocolType is com.promenar.nexara.data.remote.protocol.ProtocolType.Local) {
                        com.promenar.nexara.data.remote.provider.LlmProvider.local(app.localInferenceEngine, config.model)
                    } else {
                        com.promenar.nexara.data.remote.provider.LlmProvider.builder()
                            .protocolType(config.protocolType)
                            .baseUrl(config.baseUrl)
                            .apiKey(config.apiKey)
                            .model(config.model)
                            .build()
                    }
                    fetchedIds = tmpProvider.listModels()
                    currentCoroutineContext().ensureActive()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) { /* 远程拉取失败，回退到数据库 */ }

                // 步骤 2: 回退 — 从 ModelSpecs 数据库匹配该协议类型的已知模型
                if (fetchedIds.isEmpty()) {
                    usedBuiltInFallback = true
                    fetchedIds = getFallbackModelIds(config.protocolType)
                }

                // 步骤 3: 合并模型（新增 + 更新元数据）
                if (fetchedIds.isNotEmpty()) {
                    var newCount = 0
                    var updatedCount = 0

                    for (id in fetchedIds) {
                        when (pm.syncModelMetadata(providerId, providerName, id)) {
                            com.promenar.nexara.data.manager.ModelSyncResult.ADDED -> newCount++
                            com.promenar.nexara.data.manager.ModelSyncResult.UPDATED -> updatedCount++
                            com.promenar.nexara.data.manager.ModelSyncResult.UNCHANGED -> Unit
                        }
                    }

                    // 构造结构化反馈：新增/更新计数进入 typed args，展示层负责本地化
                    if (newCount > 0 || updatedCount > 0) {
                        val synced = ModelSyncNotice.synced(newCount, updatedCount)
                        _modelSyncNotice.value = synced.withFallbackWarning(usedBuiltInFallback)
                    } else {
                        _modelSyncNotice.value = ModelSyncNotice.upToDate()
                            .withFallbackWarning(usedBuiltInFallback)
                    }
                } else {
                    _modelSyncNotice.value = ModelSyncNotice.noModelsFound()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _modelSyncNotice.value = ModelSyncNotice.syncFailed(e.message)
            } finally {
                _isFetchingModels.value = false
                modelSyncGate.release()
            }
        }
    }

    suspend fun verifyOnboardingModel(providerId: String, model: ModelInfo): ModelInfo? {
        val verified = verifyOnboardingModelCandidate(model) { remoteModelId ->
            val config = pm.getProviderConfig(providerId) ?: return@verifyOnboardingModelCandidate false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runOnboardingEndpointProbe {
                    val provider = if (config.protocolType is ProtocolType.Local) {
                        com.promenar.nexara.data.remote.provider.LlmProvider.local(
                            app.localInferenceEngine,
                            remoteModelId,
                        )
                    } else {
                        com.promenar.nexara.data.remote.provider.LlmProvider.builder()
                            .protocolType(config.protocolType)
                            .baseUrl(config.baseUrl)
                            .apiKey(config.apiKey)
                            .serviceAccountJson(config.vertexServiceAccountJson)
                            .model(remoteModelId)
                            .build()
                    }
                    provider.sendPromptSync(
                        com.promenar.nexara.data.remote.protocol.PromptRequest(
                            messages = listOf(
                                com.promenar.nexara.data.remote.protocol.ProtocolMessage(
                                    role = "user",
                                    content = "Reply OK.",
                                )
                            ),
                            model = remoteModelId,
                            maxTokens = 1,
                            tools = null,
                            stream = false,
                        )
                    )
                }
            }
        }
        if (verified != null && verified != model) pm.replaceModelFromInternalFlow(verified)
        return verified
    }

    /**
     * 当远程 API 不可用时，从 ModelSpecs 数据库匹配已知模型 ID。
     * 按协议类型的关键词匹配 ModelSpec 条目。
     */
    private fun getFallbackModelIds(protocolType: com.promenar.nexara.data.remote.protocol.ProtocolType): List<String> {
        val keywords = when (protocolType) {
            is com.promenar.nexara.data.remote.protocol.ProtocolType.Anthropic_Messages -> listOf("claude")
            is com.promenar.nexara.data.remote.protocol.ProtocolType.Google_VertexAI -> listOf("gemini")
            is com.promenar.nexara.data.remote.protocol.ProtocolType.DeepSeek -> listOf("deepseek")
            is com.promenar.nexara.data.remote.protocol.ProtocolType.Mistral_Chat -> listOf("mistral")
            is com.promenar.nexara.data.remote.protocol.ProtocolType.Cohere_Chat -> listOf("command")
            is com.promenar.nexara.data.remote.protocol.ProtocolType.OpenAI_ChatCompletions -> listOf("gpt", "o1", "o3", "o4")
            is com.promenar.nexara.data.remote.protocol.ProtocolType.Local -> return emptyList()
            else -> return emptyList()
        }
        // 扫描 MODEL_SPECS，匹配 StringPattern 包含任一关键词的条目
        return com.promenar.nexara.data.model.MODEL_SPECS
            .filter { spec ->
                keywords.any { kw -> spec.pattern.matches(kw) }
            }
            .mapNotNull { spec ->
                when (val p = spec.pattern) {
                    is com.promenar.nexara.data.model.ModelPattern.StringPattern -> p.value
                    else -> null
                }
            }
            .distinct()
    }

    /**
     * 根据 providerId 获取完整提供商配置（用于编辑回填）。
     * - "default" → 主提供商
     * - "extra_N" → 额外提供商
     */
    fun getProviderConfig(providerId: String): ProviderConfig? {
        return pm.getProviderConfig(providerId)
    }

    suspend fun getProviderSummary(providerId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pm.getProviderSummary(providerId)
        }

    suspend fun revealProviderCredential(providerId: String, vertex: Boolean): CharArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pm.revealCredential(providerId, vertex)
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
        val allPresetSkills = setOf(
            "web_search", "web_fetch", "search_tavily", "search_searxng",
            "calculator", "create_tool", "image_generation",
            "file_read", "file_write", "file_list", "file_search", "file_diff", "file_patch",
            "exec_js", "initialize_plan", "update_plan", "get_plan", "drop_plan"
        )
        if (!prefs.getBoolean("preset_skills_migrated_v3", false)) {
            prefs.edit()
                .putStringSet("enabled_skills", allPresetSkills)
                .putBoolean("preset_skills_migrated_v3", true)
                .apply()
        }

        val enabledSet = prefs.getStringSet("enabled_skills", allPresetSkills)
        _skills.value = listOf(
            SkillInfo("web_search", app.getString(R.string.skill_web_search), app.getString(R.string.skill_web_search_desc), enabledSet?.contains("web_search") ?: true),
            SkillInfo("web_fetch", app.getString(R.string.skill_web_fetch), app.getString(R.string.skill_web_fetch_desc), enabledSet?.contains("web_fetch") ?: true),
            SkillInfo("search_tavily", app.getString(R.string.skill_tavily), app.getString(R.string.skill_tavily_desc), enabledSet?.contains("search_tavily") ?: true),
            SkillInfo("search_searxng", app.getString(R.string.skill_searxng), app.getString(R.string.skill_searxng_desc), enabledSet?.contains("search_searxng") ?: true),
            SkillInfo("calculator", app.getString(R.string.skill_calculator), app.getString(R.string.skill_calculator_desc), enabledSet?.contains("calculator") ?: true),
            SkillInfo("create_tool", app.getString(R.string.skill_create_tool), app.getString(R.string.skill_create_tool_desc), enabledSet?.contains("create_tool") ?: true),
            SkillInfo("image_generation", app.getString(R.string.skill_image_generation), app.getString(R.string.skill_image_generation_desc), enabledSet?.contains("image_generation") ?: true),
            SkillInfo("file_read", app.getString(R.string.skill_file_read), app.getString(R.string.skill_file_read_desc), enabledSet?.contains("file_read") ?: true),
            SkillInfo("file_write", app.getString(R.string.skill_file_write), app.getString(R.string.skill_file_write_desc), enabledSet?.contains("file_write") ?: true),
            SkillInfo("file_list", app.getString(R.string.skill_file_list), app.getString(R.string.skill_file_list_desc), enabledSet?.contains("file_list") ?: true),
            SkillInfo("file_search", app.getString(R.string.skill_file_search), app.getString(R.string.skill_file_search_desc), enabledSet?.contains("file_search") ?: true),
            SkillInfo("file_diff", app.getString(R.string.skill_file_diff), app.getString(R.string.skill_file_diff_desc), enabledSet?.contains("file_diff") ?: true),
            SkillInfo("file_patch", app.getString(R.string.skill_file_patch), app.getString(R.string.skill_file_patch_desc), enabledSet?.contains("file_patch") ?: true),
            SkillInfo("exec_js", app.getString(R.string.skill_exec_js), app.getString(R.string.skill_exec_js_desc), enabledSet?.contains("exec_js") ?: true),
            SkillInfo("initialize_plan", app.getString(R.string.skill_initialize_plan), app.getString(R.string.skill_initialize_plan_desc), enabledSet?.contains("initialize_plan") ?: true),
            SkillInfo("update_plan", app.getString(R.string.skill_update_plan), app.getString(R.string.skill_update_plan_desc), enabledSet?.contains("update_plan") ?: true),
            SkillInfo("get_plan", app.getString(R.string.skill_get_plan), app.getString(R.string.skill_get_plan_desc), enabledSet?.contains("get_plan") ?: true),
            SkillInfo("drop_plan", app.getString(R.string.skill_drop_plan), app.getString(R.string.skill_drop_plan_desc), enabledSet?.contains("drop_plan") ?: true)
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
        if (com.promenar.nexara.util.LocaleController.setApplicationLanguage(app, lang)) {
            _language.value = lang
        }
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

    fun updateExtraProvider(id: String, item: ProviderListItem) {
        pm.updateExtraProvider(id, item)
    }

    fun addProvider(item: ProviderListItem) {
        pm.addProvider(item)
    }

    fun updateModel(updatedModel: ModelInfo) {
        pm.replaceModelFromInternalFlow(updatedModel)
    }

    fun updateUserModel(updatedModel: ModelInfo) = pm.applyUserModelUpdate(updatedModel)

    fun toggleModel(id: String) = pm.toggleModel(id)



    fun disableAllModels(providerId: String) = pm.disableAllModels(providerId)

    fun deleteAllModels(providerId: String) = pm.deleteAllModels(providerId)

    fun addCustomModel(providerId: String, id: String, name: String): Boolean =
        pm.addCustomModel(providerId, id, name)

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

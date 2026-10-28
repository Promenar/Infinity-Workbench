package com.promenar.nexara.ui.hub

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.agent.AgentMemoryPolicy
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.agent.AgentRetrievalMode
import com.promenar.nexara.data.agent.KnowledgeFileAccess
import com.promenar.nexara.data.agent.PresetAgents
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.model.params.SamplingValues
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AgentEditErrorCode { NOT_FOUND, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED, AVATAR_IMPORT_FAILED }

class AgentEditViewModel(
    private val agentRepository: AgentRepository,
    private val avatarImporter: suspend (Uri, String) -> String? = { _, _ -> null },
    private val avatarCleaner: (Uri) -> Unit = {},
    /** 已登记的自选工作区，用于显示默认工作区的名称与路径。 */
    workspaces: Flow<List<WorkspaceEntity>> = flowOf(emptyList()),
    /** 按模型保存的参数方案；为 null 时不提供方案。 */
    private val parameterSchemeRepository: com.promenar.nexara.data.repository.ParameterSchemeRepository? = null,
    private val deleteAgentUseCase: com.promenar.nexara.domain.usecase.DeleteAgentUseCase? = null,
    /** 全局 MCP 服务器，供“新会话默认”选择。 */
    mcpServers: Flow<List<McpServerEntity>> = flowOf(emptyList()),
    /** 该智能体当前的会话数，删除确认据此写明将关闭几个会话。 */
    private val countAgentSessions: suspend (String) -> Int = { 0 },
) : ViewModel() {

    private val _initialAgent = MutableStateFlow<Agent?>(null)
    private val _initialDisplayName = MutableStateFlow("")
    private val _initialDisplayDescription = MutableStateFlow("")
    private val _name = MutableStateFlow("")
    private val _description = MutableStateFlow("")
    private val _systemPrompt = MutableStateFlow("")
    private val _selectedModel = MutableStateFlow("")
    private val _selectedColor = MutableStateFlow(Agent(id = "", name = "").color)
    private val _selectedIcon = MutableStateFlow("✨")
    private val _avatarPath = MutableStateFlow<String?>(null)
    private val _sampling = MutableStateFlow(SamplingValues())
    private val _reasoningEffort = MutableStateFlow<String?>(null)
    /** 本次编辑中最近选用的方案，只用于标出“已修改”，不落库。 */
    private val _baseSchemeId = MutableStateFlow<String?>(null)
    private val _isPinned = MutableStateFlow(false)
    /** 智能体检索配置：界面只编辑检索方式与知识库文件访问，其余旧字段原样保留、运行时不读取。 */
    private val _retrievalConfig = MutableStateFlow(AgentRetrievalConfig())
    private val _memoryPolicy = MutableStateFlow(AgentMemoryPolicy())
    private val _saveError = MutableStateFlow<AgentEditErrorCode?>(null)
    private val _defaultWorkspaceId = MutableStateFlow<String?>(null)
    private val _executionMode = MutableStateFlow(ExecutionMode.WORKSPACE)
    private val _mcpServerIds = MutableStateFlow<List<String>>(emptyList())
    private val _sessionCount = MutableStateFlow(0)

    val name: StateFlow<String> = _name.asStateFlow()
    val description: StateFlow<String> = _description.asStateFlow()
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    val selectedColor: StateFlow<String> = _selectedColor.asStateFlow()
    val selectedIcon: StateFlow<String> = _selectedIcon.asStateFlow()
    val avatarPath: StateFlow<String?> = _avatarPath.asStateFlow()
    /** 智能体会话默认的采样参数；为空的项按模型默认。 */
    val sampling: StateFlow<SamplingValues> = _sampling.asStateFlow()
    val reasoningEffort: StateFlow<String?> = _reasoningEffort.asStateFlow()
    val baseSchemeId: StateFlow<String?> = _baseSchemeId.asStateFlow()

    /** 智能体当前模型的已存方案。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val parameterSchemes: StateFlow<List<com.promenar.nexara.data.model.params.ParameterScheme>> = _selectedModel
        .flatMapLatest { modelKey ->
            val repository = parameterSchemeRepository
            if (repository == null || modelKey.isBlank()) flowOf(emptyList()) else repository.observeForModel(modelKey)
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()
    val retrievalConfig: StateFlow<AgentRetrievalConfig> = _retrievalConfig.asStateFlow()
    val memoryPolicy: StateFlow<AgentMemoryPolicy> = _memoryPolicy.asStateFlow()
    val saveError: StateFlow<AgentEditErrorCode?> = _saveError.asStateFlow()
    val defaultWorkspaceId: StateFlow<String?> = _defaultWorkspaceId.asStateFlow()
    /** 新会话默认的执行模式与 MCP 服务器；只影响之后新建的会话。 */
    val executionMode: StateFlow<ExecutionMode> = _executionMode.asStateFlow()
    val mcpServerIds: StateFlow<List<String>> = _mcpServerIds.asStateFlow()
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()
    val mcpServers: StateFlow<List<McpServerEntity>> = mcpServers
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** 默认工作区的登记记录；id 已设置但登记被删除时为 null，界面显示“已删除的工作区”。 */
    val defaultWorkspace: StateFlow<WorkspaceEntity?> = combine(_defaultWorkspaceId, workspaces) { id, all ->
        id?.let { wanted -> all.firstOrNull { it.id == wanted } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val hasChanges: StateFlow<Boolean> = combine(
        combine(
            _initialAgent,
            _name,
            _description,
            _initialDisplayName,
            _initialDisplayDescription,
        ) { initial, n, d, baselineName, baselineDescription ->
            TextEditState(initial, n, d, baselineName, baselineDescription)
        },
        combine(_systemPrompt, _selectedModel, _selectedColor) { sp, sm, sc -> Triple(sp, sm, sc) },
        combine(_selectedIcon, _avatarPath, _isPinned) { si, ap, pin -> Triple(si, ap, pin) },
        combine(_sampling, _reasoningEffort) { values, effort -> Pair(values, effort) },
        combine(_retrievalConfig, _defaultWorkspaceId, _memoryPolicy, _executionMode, _mcpServerIds) { ret, ws, policy, mode, mcp ->
            DefaultsEditState(ret, ws, policy, mode, mcp)
        }
    ) { text, (prompt, model, color), (icon, path, pin), (values, effort), defaults ->
        val initial = text.initial
        initial == null || text.baselineName != text.name || text.baselineDescription != text.description ||
        initial.defaultWorkspaceId != defaults.defaultWorkspaceId ||
        initial.systemPrompt != prompt || initial.modelId != model ||
        initial.color != color || initial.icon != icon || initial.avatarPath != path || initial.isPinned != pin ||
        initial.memoryPolicy != defaults.memoryPolicy ||
        initial.executionMode != defaults.executionMode || initial.mcpServerIds != defaults.mcpServerIds ||
        (initial.retrievalConfig?.retrievalMode ?: AgentRetrievalMode.AUTO) != defaults.retrievalConfig.retrievalMode ||
        (initial.retrievalConfig?.knowledgeFileAccess ?: KnowledgeFileAccess.ON_REQUEST) != defaults.retrievalConfig.knowledgeFileAccess ||
        initial.samplingValues() != values || initial.reasoningEffort != effort
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var saveJob: Job? = null
    private var avatarImportJob: Job? = null
    private var retryAction: (() -> Unit)? = null

    fun loadAgent(
        agentId: String,
        localizedPresetName: String? = null,
        localizedPresetDescription: String? = null,
    ) {
        viewModelScope.launch {
            try {
                val agent = agentRepository.observeById(agentId).first()
                if (agent != null) {
                val displayName = if (
                    PresetAgents.isPreset(agentId) && !agent.nameCustomized
                ) localizedPresetName ?: agent.name else agent.name
                val displayDescription = if (
                    PresetAgents.isPreset(agentId) && !agent.descriptionCustomized
                ) localizedPresetDescription ?: agent.description else agent.description
                _initialAgent.value = agent
                _initialDisplayName.value = displayName
                _initialDisplayDescription.value = displayDescription
                _name.value = displayName
                _description.value = displayDescription
                _systemPrompt.value = agent.systemPrompt
                _selectedModel.value = agent.modelId
                _selectedColor.value = agent.color
                _selectedIcon.value = agent.icon
                _avatarPath.value = agent.avatarPath
                _isPinned.value = agent.isPinned
                _sampling.value = agent.samplingValues()
                _reasoningEffort.value = agent.reasoningEffort
                _defaultWorkspaceId.value = agent.defaultWorkspaceId
                _memoryPolicy.value = agent.memoryPolicy
                _retrievalConfig.value = agent.retrievalConfig ?: AgentRetrievalConfig()
                _executionMode.value = agent.executionMode
                _mcpServerIds.value = agent.mcpServerIds
                refreshSessionCount()
                _saveError.value = null
                retryAction = null
                } else {
                    _saveError.value = AgentEditErrorCode.NOT_FOUND
                    retryAction = { loadAgent(agentId, localizedPresetName, localizedPresetDescription) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = AgentEditErrorCode.LOAD_FAILED
                retryAction = { loadAgent(agentId, localizedPresetName, localizedPresetDescription) }
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
        _avatarPath.value = null
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // 头像选择是一次明确提交；离开页面不能像普通文本防抖一样取消它。
            withContext(NonCancellable) {
                persistCurrentAgent(agentId)
            }
        }
    }

    fun setAvatarPath(value: String?) {
        _avatarPath.value = value
        scheduleSave()
    }

    fun importAvatar(uri: Uri) {
        val agentId = _initialAgent.value?.id ?: return
        avatarImportJob?.cancel()
        avatarImportJob = viewModelScope.launch {
            val saved = try {
                avatarImporter(uri, "agent-$agentId")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (saved == null) {
                _saveError.value = AgentEditErrorCode.AVATAR_IMPORT_FAILED
                retryAction = { importAvatar(uri) }
                return@launch
            }
            val candidate = buildAgent(agentId).copy(avatarPath = saved)
            val persisted = persistCandidate(candidate) {
                _avatarPath.value = saved
            }
            if (persisted.isSuccess) {
                runCatching { avatarCleaner(uri) }
            } else {
                retryAction = { importAvatar(uri) }
            }
        }
    }

    fun reportAvatarImportFailure() {
        _saveError.value = AgentEditErrorCode.AVATAR_IMPORT_FAILED
        retryAction = null
    }

    fun setSampling(values: SamplingValues) {
        _sampling.value = values
        scheduleSave()
    }

    fun applyModelDefaultSampling() {
        _baseSchemeId.value = com.promenar.nexara.data.model.params.MODEL_DEFAULT_SCHEME_ID
        setSampling(SamplingValues())
    }

    fun applyParameterScheme(scheme: com.promenar.nexara.data.model.params.ParameterScheme) {
        _baseSchemeId.value = scheme.id
        setSampling(scheme.values)
    }

    fun setSamplingParam(param: com.promenar.nexara.data.model.params.SamplingParam, value: Double?) =
        setSampling(_sampling.value.with(param, value))

    fun saveParameterScheme(name: String, values: SamplingValues) {
        val repository = parameterSchemeRepository ?: return
        val modelKey = _selectedModel.value.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            val scheme = repository.create(modelKey, name, values)
            _baseSchemeId.value = scheme.id
            setSampling(values)
        }
    }

    fun overwriteParameterScheme(scheme: com.promenar.nexara.data.model.params.ParameterScheme, values: SamplingValues) {
        val repository = parameterSchemeRepository ?: return
        viewModelScope.launch {
            if (repository.overwrite(scheme.id, values)) {
                _baseSchemeId.value = scheme.id
                setSampling(values)
            }
        }
    }

    fun renameParameterScheme(scheme: com.promenar.nexara.data.model.params.ParameterScheme, name: String) {
        val repository = parameterSchemeRepository ?: return
        viewModelScope.launch { repository.rename(scheme.id, name) }
    }

    fun deleteParameterScheme(scheme: com.promenar.nexara.data.model.params.ParameterScheme) {
        val repository = parameterSchemeRepository ?: return
        viewModelScope.launch { repository.delete(scheme.id) }
    }

    fun setReasoningEffort(value: String?) {
        _reasoningEffort.value = value
        scheduleSave()
    }

    fun setPinned(value: Boolean) {
        _isPinned.value = value
        scheduleSave()
    }

    /** 检索方式：明确提交，立即持久化。与知识库文件访问互不联动。 */
    fun setRetrievalMode(mode: AgentRetrievalMode) {
        _retrievalConfig.update { it.copy(retrievalMode = mode) }
        commitNow()
    }

    fun setKnowledgeFileAccess(access: KnowledgeFileAccess) {
        _retrievalConfig.update { it.copy(knowledgeFileAccess = access) }
        commitNow()
    }

    /** 新会话默认的执行模式：明确提交，立即持久化，只影响之后新建的会话。 */
    fun setExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
        commitNow()
    }

    /** 新会话默认连接的 MCP 服务器；保留原有顺序，只增删给出的一项。 */
    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        _mcpServerIds.update { ids -> if (enabled) (ids + serverId).distinct() else ids - serverId }
        commitNow()
    }

    /** 删除确认打开前刷新会话数，避免显示进入编辑页之后新建或删除的会话。 */
    fun refreshSessionCount() {
        val agentId = _initialAgent.value?.id ?: return
        viewModelScope.launch {
            _sessionCount.value = try {
                countAgentSessions(agentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _sessionCount.value
            }
        }
    }

    /** 记忆权限明确提交并立即持久化；上限调低时默认档位随之收紧。只影响之后的检索与新建会话的默认值。 */
    fun updateMemoryPolicy(
        transform: (AgentMemoryPolicy) -> AgentMemoryPolicy,
    ) {
        _memoryPolicy.update { transform(it).normalized() }
        commitNow()
    }

    /** 设置或清除（null）默认工作区：明确提交，立即持久化，离开页面也不取消。只影响之后新建的会话。 */
    fun setDefaultWorkspace(workspaceId: String?) {
        _defaultWorkspaceId.value = workspaceId
        commitNow()
    }

    fun deleteAgent(agentId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                if (deleteAgentUseCase != null) deleteAgentUseCase.invoke(agentId) else agentRepository.delete(agentId)
                _saveError.value = null
                retryAction = null
                onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = AgentEditErrorCode.DELETE_FAILED
                retryAction = { deleteAgent(agentId, onDeleted) }
            }
        }
    }

    fun retryLastFailure() {
        retryAction?.invoke()
    }

    fun saveAgent(agentId: String) {
        viewModelScope.launch { persistCurrentAgent(agentId) }
    }

    /** Prompt 编辑器必须等待 Room 持久化成功，不能把延迟自动保存视为成功。 */
    suspend fun saveSystemPrompt(value: String): Result<Unit> {
        saveJob?.cancelAndJoin()
        val initial = _initialAgent.value
            ?: return Result.failure(IllegalStateException("Agent 尚未加载"))
        val candidate = buildAgent(initial.id).copy(systemPrompt = value)
        return persistCandidate(candidate) {
            _systemPrompt.value = value
        }
    }

    private suspend fun persistCurrentAgent(agentId: String): Result<Unit> =
        persistCandidate(buildAgent(agentId))

    private fun buildAgent(agentId: String): Agent {
        val initial = _initialAgent.value
        val nameChanged = _name.value != _initialDisplayName.value
        val descriptionChanged = _description.value != _initialDisplayDescription.value
        return Agent(
            id = agentId,
            name = if (nameChanged) _name.value else initial?.name.orEmpty(),
            description = if (descriptionChanged) _description.value else initial?.description.orEmpty(),
            nameCustomized = initial?.nameCustomized == true || nameChanged,
            descriptionCustomized = initial?.descriptionCustomized == true || descriptionChanged,
            systemPrompt = _systemPrompt.value,
            modelId = _selectedModel.value,
            icon = _selectedIcon.value,
            color = _selectedColor.value,
            avatarPath = _avatarPath.value,
            isPinned = _isPinned.value,
            temperature = _sampling.value.temperature,
            topP = _sampling.value.topP,
            maxTokens = _sampling.value.maxTokens,
            topK = _sampling.value.topK,
            frequencyPenalty = _sampling.value.frequencyPenalty,
            presencePenalty = _sampling.value.presencePenalty,
            repetitionPenalty = _sampling.value.repetitionPenalty,
            reasoningEffort = _reasoningEffort.value,
            // 智能体不再覆盖检索参数：旧的自定义参数原样保留以便旧数据往返，运行时不读取。
            ragConfig = initial?.ragConfig,
            retrievalConfig = _retrievalConfig.value.copy(memoryPolicy = _memoryPolicy.value)
                .takeUnless { initial?.retrievalConfig == null && it == AgentRetrievalConfig() },
            useInheritedConfig = initial?.useInheritedConfig ?: true,
            executionMode = _executionMode.value,
            // 技能名单运行时不读取（用户技能按全局启用名单生效），只原样保留。
            skills = initial?.skills ?: emptyList(),
            mcpServerIds = _mcpServerIds.value,
            defaultWorkspaceId = _defaultWorkspaceId.value,
            userRules = initial?.userRules.orEmpty(),
            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
        )
    }

    private suspend fun persistCandidate(
        agent: Agent,
        commitLocalState: () -> Unit = {},
    ): Result<Unit> {
        try {
            agentRepository.update(agent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _saveError.value = AgentEditErrorCode.SAVE_FAILED
            retryAction = { saveAgent(agent.id) }
            return Result.failure(error)
        }
        commitLocalState()
        _saveError.value = null
        retryAction = null
        _initialAgent.value = agent
        _initialDisplayName.value = _name.value
        _initialDisplayDescription.value = _description.value
        return Result.success(Unit)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000)
            _initialAgent.value?.let { agent ->
                persistCurrentAgent(agent.id)
            }
        }
    }

    /** 明确提交的设置立即持久化；离开页面也不取消。 */
    private fun commitNow() {
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            withContext(NonCancellable) {
                persistCurrentAgent(agentId)
            }
        }
    }

    private data class DefaultsEditState(
        val retrievalConfig: AgentRetrievalConfig,
        val defaultWorkspaceId: String?,
        val memoryPolicy: AgentMemoryPolicy,
        val executionMode: ExecutionMode,
        val mcpServerIds: List<String>,
    )

    private data class TextEditState(
        val initial: Agent?,
        val name: String,
        val description: String,
        val baselineName: String,
        val baselineDescription: String,
    )

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    return AgentEditViewModel(
                        deleteAgentUseCase = app.deleteAgentUseCase,
                        parameterSchemeRepository = app.parameterSchemeRepository,
                        agentRepository = app.agentRepository,
                        avatarImporter = { uri, slot ->
                            withContext(Dispatchers.IO) {
                                com.promenar.nexara.ui.settings.AvatarStore(
                                    directory = java.io.File(app.filesDir, "avatars"),
                                    mimeTypeOf = { source ->
                                        app.contentResolver.getType(source)
                                            ?: java.net.URLConnection.guessContentTypeFromName(source.path)
                                    },
                                    openInput = app.contentResolver::openInputStream,
                                ).save(uri, slot)
                            }
                        },
                        avatarCleaner = { uri ->
                            com.promenar.nexara.ui.avatar.AvatarCropFiles.deleteIfOwned(
                                app.cacheDir,
                                uri,
                            )
                        },
                        workspaces = app.database.workspaceDao().observeAll(),
                        mcpServers = app.skillRepository.getAllMcpServers(),
                        countAgentSessions = { agentId ->
                            withContext(Dispatchers.IO) {
                                app.sessionRepository.getAll().count { it.agentId == agentId }
                            }
                        },
                    ) as T
                }
            }
    }
}

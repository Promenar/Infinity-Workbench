package com.promenar.nexara.ui.hub

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.agent.PresetAgents
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.usecase.RagConfigPersistence
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AgentEditErrorCode { NOT_FOUND, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED, AVATAR_IMPORT_FAILED }

class AgentEditViewModel(
    private val agentRepository: AgentRepository,
    private val ragConfigPersistence: RagConfigPersistence,
    private val avatarImporter: suspend (Uri, String) -> String? = { _, _ -> null },
    private val avatarCleaner: (Uri) -> Unit = {},
    /** 已登记的自选工作区，用于显示默认工作区的名称与路径。 */
    workspaces: Flow<List<WorkspaceEntity>> = flowOf(emptyList()),
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
    private val _temperature = MutableStateFlow(0.7f)
    private val _topP = MutableStateFlow(0.9f)
    private val _isPinned = MutableStateFlow(false)
    private val _useInheritedConfig = MutableStateFlow(true)
    private val _ragConfig = MutableStateFlow(com.promenar.nexara.data.agent.AgentRagConfig())
    private val _retrievalConfig = MutableStateFlow(com.promenar.nexara.data.agent.AgentRetrievalConfig())
    /** 记忆权限独立于“继承全局检索参数”：继承时仍按助手单独保存。 */
    private val _memoryPolicy = MutableStateFlow(com.promenar.nexara.data.agent.AgentMemoryPolicy())
    private val _saveError = MutableStateFlow<AgentEditErrorCode?>(null)
    private val _defaultWorkspaceId = MutableStateFlow<String?>(null)

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
    val memoryPolicy: StateFlow<com.promenar.nexara.data.agent.AgentMemoryPolicy> = _memoryPolicy.asStateFlow()
    val saveError: StateFlow<AgentEditErrorCode?> = _saveError.asStateFlow()
    val defaultWorkspaceId: StateFlow<String?> = _defaultWorkspaceId.asStateFlow()
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
        combine(_temperature, _topP) { temp, tp -> Pair(temp, tp) },
        combine(_useInheritedConfig, _ragConfig, _retrievalConfig, _defaultWorkspaceId, _memoryPolicy) { u, r, ret, ws, policy ->
            InheritanceEditState(u, r, ret.copy(memoryPolicy = policy), ws, policy)
        }
    ) { text, (prompt, model, color), (icon, path, pin), (temp, tp), (useIn, rag, retr, workspace, policy) ->
        val initial = text.initial
        initial == null || text.baselineName != text.name || text.baselineDescription != text.description ||
        initial.defaultWorkspaceId != workspace ||
        initial.systemPrompt != prompt || initial.modelId != model ||
        initial.color != color || initial.icon != icon || initial.avatarPath != path || initial.isPinned != pin ||
        initial.useInheritedConfig != useIn || initial.memoryPolicy != policy ||
        (!useIn && (initial.ragConfig != rag || initial.retrievalConfig != retr))
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
                _temperature.value = agent.temperature?.toFloat() ?: 0.7f
                _topP.value = agent.topP?.toFloat() ?: 0.9f
                _useInheritedConfig.value = agent.useInheritedConfig
                _defaultWorkspaceId.value = agent.defaultWorkspaceId
                _memoryPolicy.value = agent.memoryPolicy

                if (agent.useInheritedConfig) {
                    _ragConfig.value = getGlobalRagConfig()
                    _retrievalConfig.value = getGlobalRetrievalConfig()
                } else {
                    _ragConfig.value = agent.ragConfig ?: getGlobalRagConfig()
                    _retrievalConfig.value = agent.retrievalConfig ?: getGlobalRetrievalConfig()
                }
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

    /** 记忆权限明确提交并立即持久化；上限调低时默认档位随之收紧。只影响之后的检索与新建会话的默认值。 */
    fun updateMemoryPolicy(
        transform: (com.promenar.nexara.data.agent.AgentMemoryPolicy) -> com.promenar.nexara.data.agent.AgentMemoryPolicy,
    ) {
        _memoryPolicy.update { transform(it).normalized() }
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            withContext(NonCancellable) {
                persistCurrentAgent(agentId)
            }
        }
    }

    /** 设置或清除（null）默认工作区：明确提交，立即持久化，离开页面也不取消。只影响之后新建的会话。 */
    fun setDefaultWorkspace(workspaceId: String?) {
        _defaultWorkspaceId.value = workspaceId
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            withContext(NonCancellable) {
                persistCurrentAgent(agentId)
            }
        }
    }

    fun deleteAgent(agentId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                agentRepository.delete(agentId)
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

    /** Agent RAG Prompt 与系统 Prompt 使用相同的严格持久化契约。 */
    suspend fun saveRagConfig(
        transform: (com.promenar.nexara.data.agent.AgentRagConfig) ->
            com.promenar.nexara.data.agent.AgentRagConfig,
    ): Result<Unit> {
        saveJob?.cancelAndJoin()
        val initial = _initialAgent.value
            ?: return Result.failure(IllegalStateException("Agent 尚未加载"))
        val value = transform(_ragConfig.value)
        val candidate = buildAgent(initial.id).copy(
            ragConfig = value,
            retrievalConfig = _retrievalConfig.value.copy(memoryPolicy = _memoryPolicy.value),
            useInheritedConfig = false,
        )
        return persistCandidate(candidate) {
            _ragConfig.value = value
            _useInheritedConfig.value = false
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
            temperature = _temperature.value.toDouble(),
            topP = _topP.value.toDouble(),
            maxTokens = 4096,
            ragConfig = if (_useInheritedConfig.value) null else _ragConfig.value,
            retrievalConfig = if (_useInheritedConfig.value) {
                _memoryPolicy.value.takeIf { it != com.promenar.nexara.data.agent.AgentMemoryPolicy() }
                    ?.let { com.promenar.nexara.data.agent.AgentRetrievalConfig(memoryPolicy = it) }
            } else {
                _retrievalConfig.value.copy(memoryPolicy = _memoryPolicy.value)
            },
            useInheritedConfig = _useInheritedConfig.value,
            executionMode = initial?.executionMode ?: com.promenar.nexara.domain.model.ExecutionMode.SEMI,
            skills = initial?.skills ?: emptyList(),
            mcpServerIds = initial?.mcpServerIds ?: emptyList(),
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

    private data class InheritanceEditState(
        val useInherited: Boolean,
        val ragConfig: com.promenar.nexara.data.agent.AgentRagConfig,
        val retrievalConfig: com.promenar.nexara.data.agent.AgentRetrievalConfig,
        val defaultWorkspaceId: String?,
        val memoryPolicy: com.promenar.nexara.data.agent.AgentMemoryPolicy,
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
                        agentRepository = app.agentRepository,
                        ragConfigPersistence = RagConfigPersistence(
                            app.getSharedPreferences("rag_settings", 0)
                        ),
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
                    ) as T
                }
            }
    }
}

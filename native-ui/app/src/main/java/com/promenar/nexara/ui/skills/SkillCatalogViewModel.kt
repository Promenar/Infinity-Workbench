package com.promenar.nexara.ui.skills

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.skills.DiscoveredSkill
import com.promenar.nexara.data.skills.SkillCatalog
import com.promenar.nexara.data.skills.SkillSnapshot
import com.promenar.nexara.data.skills.SkillWorkspace
import com.promenar.nexara.data.workspace.SessionWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 技能视图的界面状态；[projectAvailable] 为 false 时会话没有目录型工作区，不显示项目技能。 */
data class SkillCatalogUiState(
    val loading: Boolean = true,
    val snapshot: SkillSnapshot? = null,
    val projectAvailable: Boolean = false,
)

/**
 * 会话面板与设置页共用的技能视图状态。绑定会话时扫描用户级与该会话工作区的项目级技能，
 * 未绑定会话（设置页）时只扫描用户级；目录状态变化后自动重扫。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillCatalogViewModel(
    private val catalog: SkillCatalog,
    private val resolveWorkspace: suspend (String) -> SessionWorkspace,
) : ViewModel() {
    private val binding = MutableStateFlow<Binding?>(null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val state: StateFlow<SkillCatalogUiState> = combine(binding, catalog.revision) { bound, _ -> bound }
        .flatMapLatest { bound ->
            flow {
                if (bound == null) return@flow
                val workspace = bound.sessionId?.let { id -> runCatching { resolveWorkspace(id) }.getOrNull() }
                val skillWorkspace = (workspace as? SessionWorkspace.Directory)
                    ?.let { SkillWorkspace(key = it.workspace.id, root = it.root) }
                val snapshot = try {
                    catalog.scan(skillWorkspace)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _error.value = failure.message ?: failure::class.simpleName
                    null
                }
                emit(SkillCatalogUiState(loading = false, snapshot = snapshot, projectAvailable = skillWorkspace != null))
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillCatalogUiState())

    /** 绑定会话（null 表示只看用户级）；同一会话重复绑定时重新扫描，用于面板重新打开时刷新。 */
    fun bind(sessionId: String?) {
        binding.value = Binding(sessionId, (binding.value?.generation ?: 0) + 1)
    }

    fun consumeError() {
        _error.value = null
    }

    fun setEnabled(skill: DiscoveredSkill, enabled: Boolean): Job = mutate { catalog.setEnabled(skill, enabled) }

    fun trust(skill: DiscoveredSkill): Job = mutate { catalog.trust(skill) }

    fun acknowledgeModified(skill: DiscoveredSkill): Job = mutate { catalog.acknowledgeModified(skill) }

    fun delete(skill: DiscoveredSkill): Job = mutate { catalog.delete(skill) }

    /** 读取 SKILL.md 原文供只读查看；失败时返回 null 并给出错误。 */
    suspend fun loadDocument(skill: DiscoveredSkill): String? = try {
        catalog.readFile(skill, SKILL_FILE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        _error.value = failure.message ?: failure::class.simpleName
        null
    }

    private fun mutate(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _error.value = failure.message ?: failure::class.simpleName
        } finally {
            // 管理操作后重扫一次；目录修订号未变（例如操作失败）时也能刷新为磁盘上的真实状态。
            binding.value?.let { binding.value = it.copy(generation = it.generation + 1) }
        }
    }

    private data class Binding(val sessionId: String?, val generation: Int)

    companion object {
        private const val SKILL_FILE = "SKILL.md"

        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as NexaraApplication
                return SkillCatalogViewModel(app.skillCatalog, app.sessionWorkspaceResolver::resolve) as T
            }
        }
    }
}

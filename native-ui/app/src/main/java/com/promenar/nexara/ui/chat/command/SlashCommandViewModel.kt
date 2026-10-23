package com.promenar.nexara.ui.chat.command

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.skills.DiscoveredSkill
import com.promenar.nexara.data.skills.SkillCatalog
import com.promenar.nexara.data.skills.SkillWorkspace
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入框命令候选所需的技能列表：用户级与会话工作区的项目级技能。
 * 弹出候选时刷新一次，发送技能命令前再扫描一次确认可用；扫描在 IO 线程执行。
 */
class SlashCommandViewModel(
    private val catalog: SkillCatalog,
    private val resolveWorkspace: suspend (String) -> SessionWorkspace,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<DiscoveredSkill>>(emptyList())
    val skills: StateFlow<List<DiscoveredSkill>> = _skills.asStateFlow()
    private var refreshJob: Job? = null

    /** 弹出候选时调用；扫描失败时保留上次结果。 */
    fun refresh(sessionId: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { scan(sessionId) }
    }

    /** 扫描当前会话可见的技能；失败返回 null。 */
    suspend fun scan(sessionId: String): List<DiscoveredSkill>? = withContext(ioDispatcher) {
        try {
            val workspace = runCatching { resolveWorkspace(sessionId) }.getOrNull() as? SessionWorkspace.Directory
            catalog.scan(workspace?.let { SkillWorkspace(it.workspace.id, it.root) }).skills
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NexaraLogger.log("[SlashCommand] 技能扫描失败：${failure.javaClass.simpleName}")
            null
        }
    }.also { result -> result?.let { _skills.value = it } }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as NexaraApplication
                return SlashCommandViewModel(app.skillCatalog, app.sessionWorkspaceResolver::resolve) as T
            }
        }
    }
}

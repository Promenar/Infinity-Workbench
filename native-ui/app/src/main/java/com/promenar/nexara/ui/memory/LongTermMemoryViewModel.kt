package com.promenar.nexara.ui.memory

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.dao.AgentDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.memory.LongTermMemory
import com.promenar.nexara.data.memory.LongTermMemoryKind
import com.promenar.nexara.data.memory.LongTermMemoryPreferences
import com.promenar.nexara.data.memory.LongTermMemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 管理页列表中的一条记忆：附带来源助手名，供副标题显示。 */
data class LongTermMemoryListItem(val memory: LongTermMemory, val agentName: String?)

/** 编辑面板的来源说明：助手名与会话标题都可能已被删除。 */
data class LongTermMemorySourceLabel(val agentName: String?, val sessionTitle: String?)

/** 管理页的一次性提示；删除带撤销所需的旧值。 */
sealed interface LongTermMemoryNotice {
    data class Deleted(val memory: LongTermMemory) : LongTermMemoryNotice
    data class SaveFailed(val message: String) : LongTermMemoryNotice
}

class LongTermMemoryViewModel(
    private val repository: LongTermMemoryRepository,
    agentDao: AgentDao,
    private val sessionDao: SessionDao,
    private val settings: SharedPreferences,
) : ViewModel() {
    private val _enabled = MutableStateFlow(LongTermMemoryPreferences.isEnabled(settings))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val agentNames = agentDao.observeAll().map { agents -> agents.associate { it.id to it.name } }

    val items: StateFlow<List<LongTermMemoryListItem>> =
        combine(repository.observeAll(), agentNames) { memories, names ->
            memories.map { LongTermMemoryListItem(it, it.sourceAgentId?.let(names::get)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notice = MutableStateFlow<LongTermMemoryNotice?>(null)
    val notice: StateFlow<LongTermMemoryNotice?> = _notice.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        LongTermMemoryPreferences.setEnabled(settings, enabled)
        _enabled.value = enabled
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    suspend fun sourceLabel(memory: LongTermMemory, agentName: String?): LongTermMemorySourceLabel =
        LongTermMemorySourceLabel(agentName, memory.sourceSessionId?.let { sessionDao.getById(it)?.title })

    /** 保存成功返回 true；校验或冲突失败时给出提示，面板保持打开。 */
    suspend fun save(memory: LongTermMemory, kind: LongTermMemoryKind, title: String, content: String): Boolean =
        runCatching { repository.save(kind, title, content, id = memory.id) }
            .onFailure { _notice.value = LongTermMemoryNotice.SaveFailed(it.message.orEmpty()) }
            .isSuccess

    fun delete(memory: LongTermMemory) {
        viewModelScope.launch {
            repository.delete(memory.id)?.let { _notice.value = LongTermMemoryNotice.Deleted(it) }
        }
    }

    fun undoDelete(memory: LongTermMemory) {
        viewModelScope.launch { repository.restore(memory) }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as NexaraApplication
                return LongTermMemoryViewModel(
                    repository = app.longTermMemoryRepository,
                    agentDao = app.database.agentDao(),
                    sessionDao = app.database.sessionDao(),
                    settings = app.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE),
                ) as T
            }
        }
    }
}

/** 按搜索词过滤并按类型分组，组内保持仓库给出的最近更新优先顺序。 */
internal fun groupLongTermMemories(
    items: List<LongTermMemoryListItem>,
    query: String,
): List<Pair<LongTermMemoryKind, List<LongTermMemoryListItem>>> {
    val needle = query.trim()
    val visible = if (needle.isEmpty()) items else items.filter {
        it.memory.title.contains(needle, ignoreCase = true) || it.memory.content.contains(needle, ignoreCase = true)
    }
    return LongTermMemoryKind.entries.mapNotNull { kind ->
        visible.filter { it.memory.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
    }
}

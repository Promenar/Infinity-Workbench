package com.promenar.infinity.ui.linux.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobController
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxTerminalInfo
import com.promenar.infinity.domain.linux.LinuxTerminalLocation
import com.promenar.infinity.domain.linux.LinuxTerminalSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 运行中后台任务的实时输出尾部。 */
data class LiveTail(val text: String, val truncated: Boolean)

sealed interface TerminalTab {
    val key: String

    data class Shell(val info: LinuxTerminalInfo) : TerminalTab {
        override val key: String get() = shellKey(info.id)
    }

    /** Agent 后台任务的只读页签；[ordinal] 为该会话内按启动顺序的编号。 */
    data class AgentJob(val job: BackgroundJob, val ordinal: Int) : TerminalTab {
        override val key: String get() = jobKey(job.id)
    }

    companion object {
        fun shellKey(id: String) = "shell:$id"
        fun jobKey(id: String) = "job:$id"
    }
}

data class LinuxTerminalUiState(
    /** null 表示尚未读到运行时状态。 */
    val runtime: LinuxRuntimeState? = null,
    val tabs: List<TerminalTab> = emptyList(),
    val selectedKey: String? = null,
    val opening: Boolean = false,
    val openFailed: Boolean = false,
    val modifiers: TerminalModifiers = TerminalModifiers.NONE,
    /** 只读页签的输出尾部，按任务 id 保存；任务结束后保留最后一次内容。 */
    val jobTails: Map<String, LiveTail> = emptyMap(),
    /** 会话 id 到标题，用于副标题中的工作区名称。 */
    val sessionTitles: Map<String, String> = emptyMap(),
) {
    val ready: Boolean get() = runtime is LinuxRuntimeState.Ready

    val selectedTab: TerminalTab? get() = tabs.firstOrNull { it.key == selectedKey } ?: tabs.firstOrNull()
}

/** 向会话写入用户输入；与 [LinuxTerminalSessions] 分开以便测试。 */
interface TerminalInputSink {
    fun write(sessionId: String, text: String)
    fun cursorKeysApplicationMode(sessionId: String): Boolean
}

class SessionInputSink(private val terminals: LinuxTerminalSessions) : TerminalInputSink {
    override fun write(sessionId: String, text: String) {
        terminals.session(sessionId)?.write(text)
    }

    override fun cursorKeysApplicationMode(sessionId: String): Boolean =
        terminals.session(sessionId)?.emulator?.isCursorKeysApplicationMode == true
}

class LinuxTerminalViewModel(
    private val request: LinuxTerminalRequest?,
    val terminals: LinuxTerminalSessions,
    private val runtime: LinuxRuntime,
    private val jobs: BackgroundJobController?,
    /** 按实时输出键读取后台任务的输出尾部；命令结束后发出 null。 */
    private val liveTail: (key: String) -> Flow<LiveTail?>,
    private val sessionTitle: suspend (sessionId: String) -> String?,
    private val input: TerminalInputSink = SessionInputSink(terminals),
) : ViewModel() {
    private val _state = MutableStateFlow(LinuxTerminalUiState())
    val state: StateFlow<LinuxTerminalUiState> = _state.asStateFlow()

    private var initialHandled = false
    private var shells: List<LinuxTerminalInfo> = emptyList()
    private var allJobs: List<BackgroundJob> = emptyList()
    /** 已结束但仍被选中的任务页签保留显示，直到切走或关闭。 */
    private val retainedJobIds = mutableSetOf<String>()
    private val dismissedJobIds = mutableSetOf<String>()
    private var tailJob: Job? = null
    private var tailJobId: String? = null

    init {
        viewModelScope.launch {
            runtime.state.collect { value ->
                _state.update { it.copy(runtime = value) }
                maybeOpenInitial()
            }
        }
        viewModelScope.launch {
            terminals.sessions.collect { list ->
                shells = list.sortedBy { it.ordinal }
                rebuildTabs()
                maybeOpenInitial()
            }
        }
        jobs?.let { controller ->
            viewModelScope.launch {
                controller.observe(null).collect { list ->
                    allJobs = list
                    rebuildTabs()
                }
            }
        }
    }

    fun openNew() {
        val selected = _state.value.selectedTab
        val location = (selected as? TerminalTab.Shell)?.info?.location
            ?: request?.location
            ?: LinuxTerminalLocation.Directory()
        open(location, null)
    }

    fun retryOpen() {
        if (_state.value.tabs.isEmpty()) {
            initialHandled = false
            _state.update { it.copy(openFailed = false) }
            maybeOpenInitial()
        } else openNew()
    }

    fun select(key: String) {
        if (_state.value.selectedKey == key) return
        val previous = _state.value.selectedTab
        if (previous is TerminalTab.AgentJob && previous.job.state.isTerminal) retainedJobIds -= previous.job.id
        _state.update { it.copy(selectedKey = key, modifiers = TerminalModifiers.NONE) }
        rebuildTabs()
    }

    /** 关闭页签：终端页签结束会话，任务页签只是不再显示。 */
    fun close(key: String) {
        val tabs = _state.value.tabs
        val index = tabs.indexOfFirst { it.key == key }
        if (index < 0) return
        val neighbor = tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1)
        if (_state.value.selectedTab?.key == key) _state.update { it.copy(selectedKey = neighbor?.key) }
        when (val tab = tabs[index]) {
            is TerminalTab.Shell -> {
                terminals.close(tab.info.id)
                shells = shells.filterNot { it.id == tab.info.id }
            }
            is TerminalTab.AgentJob -> {
                retainedJobIds -= tab.job.id
                dismissedJobIds += tab.job.id
            }
        }
        rebuildTabs()
    }

    fun toggleCtrl() = _state.update { it.copy(modifiers = it.modifiers.toggleCtrl()) }

    fun toggleAlt() = _state.update { it.copy(modifiers = it.modifiers.toggleAlt()) }

    /** 供 TerminalView 在系统键盘输入时读取；读取即消耗。 */
    fun consumeCtrl(): Boolean {
        val active = _state.value.modifiers.ctrl
        if (active) _state.update { it.copy(modifiers = it.modifiers.copy(ctrl = false)) }
        return active
    }

    fun consumeAlt(): Boolean {
        val active = _state.value.modifiers.alt
        if (active) _state.update { it.copy(modifiers = it.modifiers.copy(alt = false)) }
        return active
    }

    fun sendKey(key: TerminalExtraKey) {
        val shell = _state.value.selectedTab as? TerminalTab.Shell ?: return
        if (!shell.info.running) return
        val modifiers = _state.value.modifiers
        val text = TerminalKeyEncoder.encode(key, modifiers, input.cursorKeysApplicationMode(shell.info.id))
        _state.update { it.copy(modifiers = TerminalModifiers.NONE) }
        input.write(shell.info.id, text)
    }

    private fun maybeOpenInitial() {
        if (initialHandled || _state.value.runtime !is LinuxRuntimeState.Ready) return
        initialHandled = true
        when {
            request != null -> open(request.location, request.initialCommand)
            terminals.sessions.value.isEmpty() -> open(LinuxTerminalLocation.Directory(), null)
            else -> {
                val last = terminals.sessions.value.maxByOrNull { it.ordinal } ?: return
                if (_state.value.selectedKey == null) _state.update { it.copy(selectedKey = TerminalTab.shellKey(last.id)) }
            }
        }
    }

    private fun open(location: LinuxTerminalLocation, initialCommand: String?) {
        if (_state.value.opening) return
        _state.update { it.copy(opening = true, openFailed = false) }
        viewModelScope.launch {
            try {
                val id = terminals.open(location, initialCommand)
                _state.update { it.copy(selectedKey = TerminalTab.shellKey(id), modifiers = TerminalModifiers.NONE) }
                rebuildTabs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(openFailed = true) }
            } finally {
                _state.update { it.copy(opening = false) }
            }
        }
    }

    /** 任务页签只显示与当前终端相关会话的任务：请求的会话与各终端绑定的会话工作区。 */
    private fun scopeSessionIds(): Set<String> = buildSet {
        (request?.location as? LinuxTerminalLocation.SessionWorkspace)?.let { add(it.sessionId) }
        shells.forEach { info -> (info.location as? LinuxTerminalLocation.SessionWorkspace)?.let { add(it.sessionId) } }
    }

    private fun rebuildTabs() {
        val scope = scopeSessionIds()
        val ordinals = allJobs.groupBy { it.sessionId }.flatMap { (_, sessionJobs) ->
            sessionJobs.sortedBy { it.startedAtMillis }.mapIndexed { index, job -> job.id to index + 1 }
        }.toMap()
        val visibleJobs = allJobs
            .filter { it.sessionId in scope && it.id !in dismissedJobIds }
            .filter { it.state == BackgroundJobState.RUNNING || it.id in retainedJobIds }
            .sortedBy { it.startedAtMillis }
        val selectedKey = _state.value.selectedKey
        visibleJobs.firstOrNull { TerminalTab.jobKey(it.id) == selectedKey }?.let { retainedJobIds += it.id }
        val tabs = shells.map { TerminalTab.Shell(it) } +
            visibleJobs.map { TerminalTab.AgentJob(it, ordinals[it.id] ?: 1) }
        _state.update { it.copy(tabs = tabs) }
        observeSelectedJob()
        resolveTitles(scope + visibleJobs.map { it.sessionId })
    }

    private fun observeSelectedJob() {
        val job = (_state.value.selectedTab as? TerminalTab.AgentJob)?.job
        if (job?.id == tailJobId) return
        tailJob?.cancel()
        tailJobId = job?.id
        if (job == null) return
        tailJob = viewModelScope.launch {
            liveTail(job.liveOutputKey).collect { tail ->
                if (tail != null) _state.update { it.copy(jobTails = it.jobTails + (job.id to tail)) }
            }
        }
    }

    private fun resolveTitles(sessionIds: Set<String>) {
        val missing = sessionIds - _state.value.sessionTitles.keys
        if (missing.isEmpty()) return
        viewModelScope.launch {
            missing.forEach { id ->
                val title = try {
                    sessionTitle(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (title != null) _state.update { it.copy(sessionTitles = it.sessionTitles + (id to title)) }
            }
        }
    }

    companion object {
        fun factory(app: InfinityApplication, request: LinuxTerminalRequest?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = app.toolLiveOutputStore
                return LinuxTerminalViewModel(
                    request = request,
                    terminals = app.linux.terminals,
                    runtime = app.linux.runtime,
                    jobs = app.backgroundJobController,
                    liveTail = { key -> store.observe(key).map { snapshot -> snapshot?.let { LiveTail(it.text, it.truncated) } } },
                    sessionTitle = { id -> app.database.sessionDao().getById(id)?.title },
                ) as T
            }
        }

        /** 预览与测试中没有任务控制器时使用。 */
        val NoLiveTail: (String) -> Flow<LiveTail?> = { emptyFlow() }
    }
}

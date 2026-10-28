package com.promenar.infinity.ui.linux.output

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CommandOutputPhase { LOADING, LOADED, PENDING, NOT_FOUND, FAILED }

data class CommandOutputUiState(
    val phase: CommandOutputPhase = CommandOutputPhase.LOADING,
    val output: CommandOutput? = null,
    val segment: OutputSegment = OutputSegment.MERGED,
    val document: OutputDocument = OutputDocument.EMPTY,
    val search: OutputSearch = OutputSearch(),
    val saving: Boolean = false,
) {
    /** 后台任务日志只有合并内容，不提供分段切换。 */
    val segmentsAvailable: Boolean get() = output?.combinedOnly == false

    /** 当前分段对应的截断说明来源；合并视图同时考虑两路。 */
    val truncatedStreams: List<Pair<OutputSegment, CommandStream>>
        get() {
            val value = output ?: return emptyList()
            return listOf(OutputSegment.STDOUT to value.stdout, OutputSegment.STDERR to value.stderr).filter { (segment, stream) ->
                stream.truncated && (this.segment == OutputSegment.MERGED || this.segment == segment)
            }
        }
}

sealed interface CommandOutputEvent {
    data class Saved(val path: String) : CommandOutputEvent
    /** 自选目录工作区不支持直接保存，界面改为系统分享。 */
    data class SaveUnsupported(val text: String) : CommandOutputEvent
    data object SaveFailed : CommandOutputEvent
}

class CommandOutputViewModel(
    private val source: CommandOutputSource,
    private val repository: CommandOutputRepository,
    /** 合并视图中 stderr 段之前的分隔行文案。 */
    private val stderrSeparator: String,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(CommandOutputUiState())
    val state: StateFlow<CommandOutputUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CommandOutputEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CommandOutputEvent> = _events.asSharedFlow()

    private var documentJob: Job? = null
    private var searchJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(phase = CommandOutputPhase.LOADING) }
            val load = try {
                repository.load(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(phase = CommandOutputPhase.FAILED) }
                return@launch
            }
            when (load) {
                is CommandOutputLoad.Loaded -> {
                    val segment = if (load.output.combinedOnly) OutputSegment.MERGED else _state.value.segment
                    _state.update { it.copy(phase = CommandOutputPhase.LOADED, output = load.output, segment = segment) }
                    rebuildDocument()
                }
                CommandOutputLoad.Pending -> _state.update { it.copy(phase = CommandOutputPhase.PENDING) }
                CommandOutputLoad.NotFound -> _state.update { it.copy(phase = CommandOutputPhase.NOT_FOUND) }
            }
        }
    }

    fun selectSegment(segment: OutputSegment) {
        val current = _state.value
        if (current.segment == segment || current.output?.combinedOnly == true) return
        _state.update { it.copy(segment = segment) }
        rebuildDocument()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
        researchCurrent(keepNear = _state.value.search.currentMatch?.first)
    }

    fun nextMatch() = _state.update { it.copy(search = it.search.next()) }

    fun previousMatch() = _state.update { it.copy(search = it.search.previous()) }

    /** 复制与分享使用去掉控制序列后的当前分段文本。 */
    fun currentText(): String = _state.value.document.text

    fun saveToWorkspace() {
        val current = _state.value
        if (current.saving || current.phase != CommandOutputPhase.LOADED) return
        val text = current.document.text
        val name = CommandOutputFiles.fileName(current.segment, clock())
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = try {
                repository.saveToWorkspace(source.sessionId, name, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WorkspaceSaveResult.Failed
            } finally {
                _state.update { it.copy(saving = false) }
            }
            _events.emit(
                when (result) {
                    is WorkspaceSaveResult.Saved -> CommandOutputEvent.Saved(result.path)
                    WorkspaceSaveResult.Unsupported -> CommandOutputEvent.SaveUnsupported(text)
                    WorkspaceSaveResult.Failed -> CommandOutputEvent.SaveFailed
                },
            )
        }
    }

    private fun rebuildDocument() {
        val current = _state.value
        val output = current.output ?: return
        val segment = current.segment
        documentJob?.cancel()
        documentJob = viewModelScope.launch {
            val document = withContext(compute) { OutputSegments.build(output, segment, stderrSeparator) }
            val query = _state.value.search.query
            val search = withContext(compute) { OutputSearch.find(document.text, query) }
            _state.update { if (it.segment == segment) it.copy(document = document, search = search.copy(query = it.search.query)) else it }
            if (_state.value.search.query != query) researchCurrent(keepNear = null)
        }
    }

    private fun researchCurrent(keepNear: Int?) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val snapshot = _state.value
            val text = snapshot.document.text
            val query = snapshot.search.query
            val search = withContext(compute) { OutputSearch.find(text, query, keepNear) }
            _state.update { if (it.document.text === text && it.search.query == query) it.copy(search = search) else it }
        }
    }

    companion object {
        fun factory(app: InfinityApplication, source: CommandOutputSource) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CommandOutputViewModel(
                source = source,
                repository = AppCommandOutputRepository(app),
                stderrSeparator = app.getString(R.string.linux_output_stderr_separator),
            ) as T
        }
    }
}

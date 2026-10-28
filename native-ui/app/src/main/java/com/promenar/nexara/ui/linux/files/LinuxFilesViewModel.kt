package com.promenar.nexara.ui.linux.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.domain.linux.LinuxFileEntry
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileSystem
import com.promenar.nexara.domain.linux.LinuxFileType
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.ui.chat.components.DirectoryClipboard
import com.promenar.nexara.ui.chat.components.canPasteInto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 批量操作的种类；结果提示按它选文案。 */
internal enum class LinuxFileOp { COPY, MOVE, DELETE, COMPRESS, CREATE, RENAME, CHMOD }

/** 一次操作的结果：全部成功只报数量，部分失败报失败数与首个原因。 */
internal data class LinuxFileOpOutcome(
    val op: LinuxFileOp,
    val succeeded: Int,
    val failed: Int,
    val reason: LinuxFileErrorCode?,
    /** 新建、重命名、压缩、改权限得到的条目名。 */
    val name: String? = null,
)

/** 页面上方的一次性提示。 */
internal sealed interface LinuxFilesNotice {
    /** 链接指向运行时绑定路径，文件浏览无法进入。 */
    data class RuntimeBinding(val linkName: String, val target: String) : LinuxFilesNotice
    data class Failure(val code: LinuxFileErrorCode) : LinuxFilesNotice
}

/** 浏览页的对话框；名称类对话框共用 [LinuxFilesUiState.dialogError]。 */
internal sealed interface LinuxFilesDialog {
    data class CreateFile(val parent: String) : LinuxFilesDialog
    data class CreateFolder(val parent: String) : LinuxFilesDialog
    data class Rename(val entry: LinuxFileEntry) : LinuxFilesDialog
    data class Compress(val paths: List<String>, val defaultName: String) : LinuxFilesDialog
    data class Chmod(val entry: LinuxFileEntry) : LinuxFilesDialog
    data class ConfirmDelete(val paths: List<String>) : LinuxFilesDialog
}

internal data class LinuxFilesUiState(
    /** 运行时状态；非 [LinuxRuntimeState.Ready] 时显示空状态。 */
    val runtime: LinuxRuntimeState? = null,
    val path: String = LINUX_HOME_PATH,
    /** [listedPath] 目录的原始条目（含隐藏项，未排序）。 */
    val entries: List<LinuxFileEntry> = emptyList(),
    /**
     * [entries] 所属目录。进入其它目录时旧列表保留到新目录读完，页面据此做层级切换而不先闪成空白；
     * 运行时未就绪时为 null。
     */
    val listedPath: String? = null,
    val sort: LinuxFileSort = LinuxFileSort.MODIFIED,
    val showHidden: Boolean = true,
    /** 当前目录内的名称筛选；进入其它目录时清空。 */
    val query: String = "",
    val loading: Boolean = false,
    val error: LinuxFileErrorCode? = null,
    val selection: Set<String> = emptySet(),
    val clipboard: DirectoryClipboard? = null,
    val operating: Boolean = false,
    val outcome: LinuxFileOpOutcome? = null,
    val notice: LinuxFilesNotice? = null,
    val dialog: LinuxFilesDialog? = null,
    val dialogError: LinuxFileErrorCode? = null,
    val nowMs: Long = 0L,
) {
    val ready: Boolean get() = runtime is LinuxRuntimeState.Ready

    /** 浏览范围：主目录内路径行以 `~` 为起点，否则以 `/` 为起点。 */
    val atHome: Boolean get() = isUnderLinuxHome(path)

    val visibleEntries: List<LinuxFileEntry> by lazy { filterLinuxEntriesByName(sortLinuxEntries(entries, sort, showHidden), query) }

    val selecting: Boolean get() = selection.isNotEmpty()

    /** 已切换到 [path] 但仍显示上一个目录的列表。 */
    val stale: Boolean get() = listedPath != path

    val selectedEntries: List<LinuxFileEntry> get() = entries.filter { it.path in selection }

    val canPaste: Boolean get() = clipboard?.canPasteInto(path) == true
}

/**
 * Linux 文件浏览：当前目录、排序与隐藏文件、多选、剪贴板式复制移动与常用文件操作。
 * 数据层只经 [LinuxFileSystem] 访问 rootfs，所有调用在 [io] 上执行；运行时未就绪时不读取目录。
 */
internal class LinuxFilesViewModel(
    private val files: LinuxFileSystem,
    runtime: LinuxRuntime,
    initialPath: String = LINUX_HOME_PATH,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(LinuxFilesUiState(path = normalizeLinuxPath(initialPath), nowMs = clock()))
    val state: StateFlow<LinuxFilesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            runtime.state.collect { runtimeState ->
                val wasReady = _state.value.ready
                _state.update { it.copy(runtime = runtimeState) }
                if (runtimeState is LinuxRuntimeState.Ready && !wasReady) load()
                if (runtimeState !is LinuxRuntimeState.Ready) {
                    loadJob?.cancel()
                    _state.update { it.copy(entries = emptyList(), listedPath = null, loading = false, error = null, selection = emptySet()) }
                }
            }
        }
    }

    // ── 浏览 ──

    fun navigate(path: String) {
        val target = normalizeLinuxPath(path)
        _state.update { it.copy(path = target, query = "", selection = emptySet(), error = null, notice = null) }
        load(keepEntries = true)
    }

    /** 回到上一级；已在根目录返回 false，由页面交给外层返回。 */
    fun navigateUp(): Boolean {
        val parent = linuxParentPath(_state.value.path) ?: return false
        navigate(parent)
        return true
    }

    fun goHome() = navigate(LINUX_HOME_PATH)

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun refresh() = load(keepEntries = true)

    fun setSort(sort: LinuxFileSort) = _state.update { it.copy(sort = sort) }

    fun toggleShowHidden() = _state.update { state ->
        val showHidden = !state.showHidden
        // 隐藏后不可见的条目不再保留在选择中，避免误操作看不见的文件。
        val selection = if (showHidden) state.selection else state.selection - state.entries.filter { it.isHidden }.map { it.path }.toSet()
        state.copy(showHidden = showHidden, selection = selection)
    }

    /**
     * 点击条目。返回需要打开详情的文件路径；目录、链接目录与选择切换在内部完成并返回 null。
     * 指向 `/workspace`、`/mnt/` 的链接只给出提示。
     */
    fun open(entry: LinuxFileEntry): String? {
        val state = _state.value
        if (state.selecting) {
            toggleSelection(entry.path)
            return null
        }
        return when (entry.type) {
            LinuxFileType.DIRECTORY -> {
                navigate(entry.path)
                null
            }
            LinuxFileType.SYMLINK -> {
                val target = entry.symlinkTarget?.let { resolveLinuxLinkTarget(entry.path, it) }
                when {
                    target != null && isLinuxRuntimeBinding(target) -> {
                        _state.update { it.copy(notice = LinuxFilesNotice.RuntimeBinding(entry.name, target)) }
                        null
                    }
                    entry.targetIsDirectory && target != null -> {
                        navigate(target)
                        null
                    }
                    target == null -> {
                        _state.update { it.copy(notice = LinuxFilesNotice.Failure(LinuxFileErrorCode.NOT_FOUND)) }
                        null
                    }
                    else -> entry.path
                }
            }
            LinuxFileType.FILE -> entry.path
            LinuxFileType.OTHER -> null
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    // ── 选择与剪贴板 ──

    fun toggleSelection(path: String) = _state.update { state ->
        // 新目录读完前列表仍是上一个目录，不在其中开始选择。
        if (state.stale) state else state.copy(selection = if (path in state.selection) state.selection - path else state.selection + path)
    }

    fun selectAll() = _state.update { state -> if (state.stale) state else state.copy(selection = state.visibleEntries.map { it.path }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun copySelection(cut: Boolean) = _state.update { state ->
        if (state.selection.isEmpty()) state
        else state.copy(clipboard = DirectoryClipboard(state.selectedEntries.map { it.path }, cut), selection = emptySet())
    }

    fun cancelClipboard() = _state.update { it.copy(clipboard = null) }

    fun paste() {
        val state = _state.value
        val clipboard = state.clipboard ?: return
        if (state.operating || !clipboard.canPasteInto(state.path)) return
        val op = if (clipboard.cut) LinuxFileOp.MOVE else LinuxFileOp.COPY
        runBatch(op, clipboard.paths, clearClipboard = true) { paths ->
            if (clipboard.cut) files.move(paths, state.path) else files.copy(paths, state.path)
        }
    }

    /** “在终端打开”的目录：恰好选中一个目录时为它，否则为当前目录。 */
    fun terminalDirectory(): String {
        val state = _state.value
        val single = state.selectedEntries.singleOrNull()
        return when {
            single == null -> state.path
            single.type == LinuxFileType.DIRECTORY -> single.path
            single.type == LinuxFileType.SYMLINK && single.targetIsDirectory ->
                single.symlinkTarget?.let { resolveLinuxLinkTarget(single.path, it) }?.takeUnless(::isLinuxRuntimeBinding) ?: state.path
            else -> state.path
        }
    }

    // ── 对话框 ──

    fun requestCreate(folder: Boolean) = showDialog(
        if (folder) LinuxFilesDialog.CreateFolder(_state.value.path) else LinuxFilesDialog.CreateFile(_state.value.path),
    )

    fun requestRename() {
        val entry = _state.value.selectedEntries.singleOrNull() ?: return
        showDialog(LinuxFilesDialog.Rename(entry))
    }

    fun requestChmod(entry: LinuxFileEntry? = _state.value.selectedEntries.singleOrNull()) {
        if (entry == null || entry.type == LinuxFileType.SYMLINK) return
        showDialog(LinuxFilesDialog.Chmod(entry))
    }

    fun requestCompress() {
        val state = _state.value
        val paths = state.selectedEntries.map { it.path }
        if (paths.isEmpty()) return
        showDialog(LinuxFilesDialog.Compress(paths, defaultLinuxArchiveName(paths, state.path)))
    }

    fun requestDelete() {
        val paths = _state.value.selectedEntries.map { it.path }
        if (paths.isNotEmpty()) showDialog(LinuxFilesDialog.ConfirmDelete(paths))
    }

    fun dismissDialog() = _state.update { if (it.operating) it else it.copy(dialog = null, dialogError = null) }

    fun clearDialogError() = _state.update { it.copy(dialogError = null) }

    /** 名称类对话框确认：新建文件或文件夹、重命名、压缩。 */
    fun confirmName(name: String) {
        val state = _state.value
        if (state.operating) return
        val trimmed = name.trim()
        when (val dialog = state.dialog) {
            is LinuxFilesDialog.CreateFile -> runSingle(LinuxFileOp.CREATE) { files.createFile(dialog.parent, trimmed) }
            is LinuxFilesDialog.CreateFolder -> runSingle(LinuxFileOp.CREATE) { files.createDirectory(dialog.parent, trimmed) }
            is LinuxFilesDialog.Rename ->
                if (trimmed == dialog.entry.name) dismissDialog() else runSingle(LinuxFileOp.RENAME) { files.rename(dialog.entry.path, trimmed) }
            is LinuxFilesDialog.Compress ->
                runSingle(LinuxFileOp.COMPRESS) { files.compress(dialog.paths, ensureTarGzSuffix(trimmed)) }
            else -> Unit
        }
    }

    fun confirmChmod(mode: Int) {
        val dialog = _state.value.dialog as? LinuxFilesDialog.Chmod ?: return
        if (_state.value.operating) return
        if (mode == dialog.entry.mode) {
            dismissDialog()
            return
        }
        runSingle(LinuxFileOp.CHMOD) { files.chmod(dialog.entry.path, mode) }
    }

    fun confirmDelete() {
        val dialog = _state.value.dialog as? LinuxFilesDialog.ConfirmDelete ?: return
        _state.update { it.copy(dialog = null, dialogError = null) }
        runBatch(LinuxFileOp.DELETE, dialog.paths, clearClipboard = false) { files.delete(it) }
    }

    // ── 内部 ──

    private fun showDialog(dialog: LinuxFilesDialog) = _state.update { it.copy(dialog = dialog, dialogError = null) }

    private fun load(keepEntries: Boolean = false) {
        if (!_state.value.ready) return
        loadJob?.cancel()
        val path = _state.value.path
        _state.update {
            it.copy(
                loading = true, error = null,
                entries = if (keepEntries) it.entries else emptyList(),
                listedPath = if (keepEntries) it.listedPath else null,
                nowMs = clock(),
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val entries = withContext(io) { files.list(path) }
                _state.update { state ->
                    if (state.path != path) state
                    else state.copy(
                        entries = entries,
                        listedPath = path,
                        loading = false,
                        error = null,
                        // 刷新后已不存在的条目移出选择。
                        selection = state.selection.intersect(entries.map { it.path }.toSet()),
                        nowMs = clock(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { if (it.path != path) it else it.copy(loading = false, error = error.toLinuxFileErrorCode(), entries = emptyList(), listedPath = path) }
            }
        }
    }

    /** 单项操作：成功后关闭对话框、清空选择并刷新；名称冲突等错误留在对话框内显示。 */
    private fun runSingle(op: LinuxFileOp, action: suspend () -> LinuxFileEntry) {
        _state.update { it.copy(operating = true, dialogError = null) }
        viewModelScope.launch {
            try {
                val entry = withContext(io) { action() }
                _state.update {
                    it.copy(
                        operating = false,
                        dialog = null,
                        selection = emptySet(),
                        outcome = LinuxFileOpOutcome(op, 1, 0, null, entry.name),
                    )
                }
                load(keepEntries = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(operating = false, dialogError = error.toLinuxFileErrorCode()) }
            }
        }
    }

    private fun runBatch(
        op: LinuxFileOp,
        paths: List<String>,
        clearClipboard: Boolean,
        action: suspend (List<String>) -> Map<String, LinuxFileErrorCode>,
    ) {
        _state.update { it.copy(operating = true) }
        viewModelScope.launch {
            val outcome = try {
                val failed = withContext(io) { action(paths) }
                LinuxFileOpOutcome(op, paths.size - failed.size, failed.size, failed.values.firstOrNull())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LinuxFileOpOutcome(op, 0, paths.size, error.toLinuxFileErrorCode())
            }
            _state.update {
                it.copy(
                    operating = false,
                    outcome = outcome,
                    selection = emptySet(),
                    // 全部失败时保留剪贴板，便于换个目录重试。
                    clipboard = if (clearClipboard && outcome.succeeded > 0) null else it.clipboard,
                )
            }
            load(keepEntries = true)
        }
    }

    companion object {
        fun factory(files: LinuxFileSystem, runtime: LinuxRuntime, initialPath: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LinuxFilesViewModel(files, runtime, initialPath) as T
            }
    }
}

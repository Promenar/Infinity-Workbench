package com.promenar.nexara.ui.linux.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.domain.linux.LinuxFileEntry
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import com.promenar.nexara.domain.linux.LinuxFileSystem
import com.promenar.nexara.domain.linux.LinuxFileType
import com.promenar.nexara.domain.linux.LinuxLineEnding
import com.promenar.nexara.domain.linux.LinuxTextContent
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
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 内容页最多读取的字节；超出时只读截断显示，不允许编辑。 */
internal const val LINUX_TEXT_PREVIEW_BYTES = 256 * 1024

/** 十六进制页每页字节数。 */
internal const val LINUX_HEX_PAGE_BYTES = 4096

internal enum class LinuxFileTab { CONTENT, HEX, PROPERTIES }

/** 内容页解码后的文本快照。 */
internal data class LinuxTextPreview(
    val text: String,
    val isBinary: Boolean,
    val truncated: Boolean,
    val shownBytes: Int,
    val totalSize: Long,
    val encoding: String?,
    val lineEnding: LinuxLineEnding?,
    val lineCount: Int?,
    /** 读取时的修改时间，保存时作为冲突检测的期望值。 */
    val modifiedMs: Long,
    val kind: LinuxFileKind,
) {
    val editable: Boolean get() = !isBinary && !truncated
}

internal data class LinuxHexPage(val page: Int, val offset: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** 保存冲突：磁盘上的文件在打开后被改过；[pendingText] 是未保存的编辑内容。 */
internal data class LinuxEditConflict(val pendingText: String)

internal data class LinuxFileUiState(
    val path: String,
    val entry: LinuxFileEntry? = null,
    val loading: Boolean = true,
    val error: LinuxFileErrorCode? = null,
    val tab: LinuxFileTab = LinuxFileTab.CONTENT,
    val text: LinuxTextPreview? = null,
    val textError: LinuxFileErrorCode? = null,
    val hex: LinuxHexPage? = null,
    val hexLoading: Boolean = false,
    val hexError: LinuxFileErrorCode? = null,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val saveError: LinuxFileErrorCode? = null,
    val conflict: LinuxEditConflict? = null,
    val chmodOpen: Boolean = false,
    val chmodError: LinuxFileErrorCode? = null,
    val nowMs: Long = 0L,
) {
    val name: String get() = entry?.name ?: path.substringAfterLast('/')

    val hexPageCount: Int
        get() = entry?.size?.let { size -> ((size + LINUX_HEX_PAGE_BYTES - 1) / LINUX_HEX_PAGE_BYTES).toInt().coerceAtLeast(1) } ?: 1

    val canEdit: Boolean get() = text?.editable == true && !saving

    val canRun: Boolean get() = entry?.isExecutableFile() == true
}

/**
 * 单个文件的详情：内容只读预览（大文件截断）、十六进制分页、属性，以及带冲突检测的编辑保存。
 * 保存时以打开（或上次保存、重新加载）时的修改时间作为期望值，被别处改过时进入冲突状态。
 */
internal class LinuxFileViewModel(
    private val files: LinuxFileSystem,
    path: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(LinuxFileUiState(path = normalizeLinuxPath(path), nowMs = clock()))
    val state: StateFlow<LinuxFileUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var hexJob: Job? = null
    private var shownBefore = false

    init {
        reload()
    }

    /** 页面每次进入组合时调用：首次沿用创建时的读取，之后（从终端返回、再次打开同一文件）重新读取。 */
    fun onShown() {
        if (shownBefore && !_state.value.editing) reload()
        shownBefore = true
    }

    fun selectTab(tab: LinuxFileTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == LinuxFileTab.HEX && _state.value.hex == null && !_state.value.hexLoading) loadHexPage(0)
    }

    /** 重新读取属性与内容；十六进制页回到当前页重读。 */
    fun reload() {
        loadJob?.cancel()
        val path = _state.value.path
        _state.update { it.copy(loading = true, error = null, nowMs = clock()) }
        loadJob = viewModelScope.launch {
            try {
                val entry = withContext(io) { files.stat(path) } ?: throw LinuxFileException(LinuxFileErrorCode.NOT_FOUND)
                _state.update { it.copy(entry = entry) }
                val (preview, textError) = if (entry.type == LinuxFileType.DIRECTORY) {
                    null to null
                } else {
                    try {
                        withContext(io) { files.readText(path, LINUX_TEXT_PREVIEW_BYTES).toPreview(entry.name) } to null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        null to error.toLinuxFileErrorCode()
                    }
                }
                _state.update { it.copy(loading = false, text = preview, textError = textError, nowMs = clock()) }
                val hex = _state.value.hex
                if (hex != null || _state.value.tab == LinuxFileTab.HEX) loadHexPage(hex?.page ?: 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.toLinuxFileErrorCode()) }
            }
        }
    }

    fun loadHexPage(page: Int) {
        val pageCount = _state.value.hexPageCount
        val target = page.coerceIn(0, pageCount - 1)
        hexJob?.cancel()
        _state.update { it.copy(hexLoading = true, hexError = null) }
        val path = _state.value.path
        hexJob = viewModelScope.launch {
            try {
                val offset = target.toLong() * LINUX_HEX_PAGE_BYTES
                val bytes = withContext(io) { files.readBytes(path, offset, LINUX_HEX_PAGE_BYTES) }
                _state.update { it.copy(hex = LinuxHexPage(target, offset, bytes), hexLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(hexLoading = false, hexError = error.toLinuxFileErrorCode()) }
            }
        }
    }

    // ── 编辑 ──

    fun startEditing() {
        if (_state.value.canEdit) _state.update { it.copy(editing = true, saveError = null, conflict = null) }
    }

    fun stopEditing() = _state.update { it.copy(editing = false, saveError = null, conflict = null) }

    /**
     * 编辑器的保存回调：成功后关闭编辑器并更新内容；冲突与其它错误返回失败，编辑器保持打开。
     * [force] 为 true 时不做冲突检测（用户在冲突提示中选择覆盖）。
     */
    suspend fun save(text: String, force: Boolean = false): Result<Unit> {
        val snapshot = _state.value
        val preview = snapshot.text ?: return Result.failure(LinuxFileException(LinuxFileErrorCode.NOT_FOUND))
        _state.update { it.copy(saving = true, saveError = null, conflict = null) }
        return try {
            val entry = withContext(io) {
                files.writeText(snapshot.path, text, expectedModifiedMs = if (force) null else preview.modifiedMs)
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            _state.update {
                it.copy(
                    entry = entry,
                    text = preview.copy(
                        text = text,
                        shownBytes = bytes.size,
                        totalSize = bytes.size.toLong(),
                        lineCount = countLines(text),
                        modifiedMs = entry.modifiedMs,
                        encoding = preview.encoding ?: "UTF-8",
                    ),
                    hex = null,
                    saving = false,
                    editing = false,
                    nowMs = clock(),
                )
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(saving = false) }
            throw cancelled
        } catch (error: Exception) {
            val code = error.toLinuxFileErrorCode()
            _state.update {
                if (code == LinuxFileErrorCode.CHANGED) it.copy(saving = false, conflict = LinuxEditConflict(text))
                else it.copy(saving = false, saveError = code)
            }
            Result.failure(error)
        }
    }

    /** 冲突后放弃编辑并重新读取磁盘上的内容。 */
    fun reloadAfterConflict() {
        _state.update { it.copy(editing = false, conflict = null, saveError = null) }
        reload()
    }

    /** 冲突后保留编辑，回到编辑器。 */
    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    /** 冲突后以编辑内容覆盖磁盘上的版本。 */
    fun overwriteAfterConflict() {
        val pending = _state.value.conflict?.pendingText ?: return
        viewModelScope.launch { save(pending, force = true) }
    }

    // ── 权限 ──

    fun requestChmod() {
        val entry = _state.value.entry ?: return
        if (entry.type != LinuxFileType.SYMLINK) _state.update { it.copy(chmodOpen = true, chmodError = null) }
    }

    fun dismissChmod() = _state.update { it.copy(chmodOpen = false, chmodError = null) }

    fun confirmChmod(mode: Int) {
        val entry = _state.value.entry ?: return
        if (mode == entry.mode) {
            dismissChmod()
            return
        }
        viewModelScope.launch {
            try {
                val updated = withContext(io) { files.chmod(entry.path, mode) }
                _state.update { it.copy(entry = updated, chmodOpen = false, chmodError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(chmodError = error.toLinuxFileErrorCode()) }
            }
        }
    }

    companion object {
        fun factory(files: LinuxFileSystem, path: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LinuxFileViewModel(files, path) as T
            }
    }
}

/** 按数据层给出的编码解码；截断处被切开的多字节字符丢弃，不显示替换符。 */
internal fun LinuxTextContent.toPreview(name: String): LinuxTextPreview {
    val text = if (isBinary) "" else decodeLinuxText(bytes, encoding, truncated)
    return LinuxTextPreview(
        text = text,
        isBinary = isBinary,
        truncated = truncated,
        shownBytes = bytes.size,
        totalSize = totalSize,
        encoding = encoding,
        lineEnding = lineEnding,
        lineCount = lineCount,
        modifiedMs = modifiedMs,
        kind = linuxFileKind(name, isBinary, text.lineSequence().firstOrNull()),
    )
}

internal fun decodeLinuxText(bytes: ByteArray, encoding: String?, truncated: Boolean): String {
    val charset = encoding?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
    return if (truncated) decoded.trimEnd('�') else decoded
}

/** 与 `wc -l` 不同，末尾没有换行的最后一行也计数；空文本为 0 行。 */
internal fun countLines(text: String): Int {
    if (text.isEmpty()) return 0
    val newlines = text.count { it == '\n' }
    return if (text.endsWith('\n')) newlines else newlines + 1
}

package com.promenar.nexara.ui.rag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DocEditorTextStatistics(
    val lineCount: Int,
    val wordCount: Int,
    val maxLogicalLineLength: Int,
)

enum class DocEditorContentAccess {
    Editable,
    PerformanceProtected,
    MetadataOnly,
}

/**
 * 单次扫描正文，避免在每次击键时分别创建 lines 列表与 Regex 分词结果。
 * 行分隔语义与 Kotlin lines() 一致：CRLF 视为一个换行，独立 CR/LF 各算一个换行。
 */
internal fun analyzeDocEditorText(content: String): DocEditorTextStatistics {
    var lineCount = 1
    var wordCount = 0
    var insideWord = false
    var currentLineLength = 0
    var maxLogicalLineLength = 0

    content.forEachIndexed { index, char ->
        val isLogicalLineBreak = char == '\r' ||
            (char == '\n' && (index == 0 || content[index - 1] != '\r'))
        if (isLogicalLineBreak) {
            maxLogicalLineLength = maxOf(maxLogicalLineLength, currentLineLength)
            currentLineLength = 0
            lineCount++
        } else if (char != '\n') {
            currentLineLength++
        }
        if (char.isLegacyRegexWhitespace()) {
            insideWord = false
        } else if (!insideWord) {
            wordCount++
            insideWord = true
        }
    }

    return DocEditorTextStatistics(
        lineCount = lineCount,
        wordCount = wordCount,
        maxLogicalLineLength = maxOf(maxLogicalLineLength, currentLineLength),
    )
}

internal fun contentAccessFor(
    contentLength: Int,
    statistics: DocEditorTextStatistics,
): DocEditorContentAccess = if (
    contentLength > MAX_EDITABLE_CONTENT_LENGTH ||
    statistics.lineCount > MAX_EDITABLE_LINE_COUNT ||
    statistics.maxLogicalLineLength > MAX_EDITABLE_LOGICAL_LINE_LENGTH
) {
    DocEditorContentAccess.PerformanceProtected
} else {
    DocEditorContentAccess.Editable
}

private fun Char.isLegacyRegexWhitespace(): Boolean = when (this) {
    ' ', '\t', '\n', '\u000B', '\u000C', '\r' -> true
    else -> false
}

internal data class DocEditorPreviewSnapshot(
    val content: String,
    val isTruncated: Boolean,
    val originalLength: Int,
)

internal fun buildDocEditorPreviewSnapshot(content: String): DocEditorPreviewSnapshot {
    if (content.length <= MAX_PREVIEW_CONTENT_LENGTH) {
        return DocEditorPreviewSnapshot(
            content = content,
            isTruncated = false,
            originalLength = content.length,
        )
    }

    var endExclusive = MAX_PREVIEW_CONTENT_LENGTH
    if (
        Character.isHighSurrogate(content[endExclusive - 1]) &&
        Character.isLowSurrogate(content[endExclusive])
    ) {
        endExclusive--
    }
    return DocEditorPreviewSnapshot(
        content = content.substring(0, endExclusive),
        isTruncated = true,
        originalLength = content.length,
    )
}

/** Split 预览的可取消快照门禁；Preview 模式始终立即采用最新正文。 */
internal class DocEditorPreviewSnapshotController(
    private val scope: CoroutineScope,
    initialContent: String,
    private val debounceMillis: Long = DEFAULT_PREVIEW_DEBOUNCE_MILLIS,
) {
    private val initialSnapshot = buildDocEditorPreviewSnapshot(initialContent)
    private val _snapshot = MutableStateFlow(initialSnapshot)
    val snapshot: StateFlow<DocEditorPreviewSnapshot> = _snapshot.asStateFlow()

    private var pendingJob: Job? = null
    private var requestedSnapshot: DocEditorPreviewSnapshot? = initialSnapshot
    private var requestedMode: DocEditorViewMode = DocEditorViewMode.EDIT
    internal val hasPendingUpdate: Boolean
        get() = pendingJob?.isActive == true

    fun update(latestContent: String, mode: DocEditorViewMode) {
        if (mode == DocEditorViewMode.EDIT) {
            if (requestedMode == mode && pendingJob == null) return
            requestedMode = mode
            requestedSnapshot = null
            pendingJob?.cancel()
            pendingJob = null
            return
        }

        val latestSnapshot = buildDocEditorPreviewSnapshot(latestContent)
        if (latestSnapshot == requestedSnapshot && mode == requestedMode) return
        requestedSnapshot = latestSnapshot
        requestedMode = mode
        pendingJob?.cancel()
        pendingJob = null
        when (mode) {
            DocEditorViewMode.PREVIEW -> _snapshot.value = latestSnapshot
            DocEditorViewMode.EDIT -> Unit
            DocEditorViewMode.SPLIT -> {
                if (_snapshot.value == latestSnapshot) return
                pendingJob = scope.launch {
                    delay(debounceMillis)
                    _snapshot.value = latestSnapshot
                }
            }
        }
    }

    fun close() {
        pendingJob?.cancel()
        pendingJob = null
    }

    private companion object {
        const val DEFAULT_PREVIEW_DEBOUNCE_MILLIS = 250L
    }
}

private const val MAX_PREVIEW_CONTENT_LENGTH = 16 * 1024
internal const val MAX_EDITABLE_CONTENT_LENGTH = 32 * 1024
internal const val MAX_EDITABLE_LINE_COUNT = 2_000
internal const val MAX_EDITABLE_LOGICAL_LINE_LENGTH = 16 * 1024

package com.promenar.nexara.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class EditorMode { DIALOG }

internal data class PromptTextStatistics(
    val words: Int,
    val lines: Int,
    val characters: Int,
)

/**
 * Prompt 统计采用编辑器可解释规则：中日韩统一表意文字逐字计数，其他字母/数字连续段计为一词。
 * 连续段内部的点、连字符、下划线和撇号在两侧均为字母或数字时保留，便于正确统计版本号和复合词。
 */
internal fun promptTextStatistics(text: String): PromptTextStatistics {
    var words = 0
    var inWord = false
    var offset = 0

    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val nextOffset = offset + Character.charCount(codePoint)
        val nextCodePoint = if (nextOffset < text.length) text.codePointAt(nextOffset) else null
        when {
            codePoint.isCjkIdeograph() -> {
                if (inWord) words++
                words++
                inWord = false
            }

            Character.isLetterOrDigit(codePoint) -> inWord = true

            codePoint.isInternalWordPunctuation() &&
                inWord &&
                nextCodePoint?.let(Character::isLetterOrDigit) == true -> Unit

            else -> {
                if (inWord) words++
                inWord = false
            }
        }
        offset = nextOffset
    }
    if (inWord) words++

    return PromptTextStatistics(
        words = words,
        lines = text.count { it == '\n' } + 1,
        characters = text.length,
    )
}

internal fun hasUnsavedPromptChanges(initialText: String, currentText: String): Boolean =
    initialText != currentText

/** 同步门禁用于拦截同一帧内的快速重复点击；Compose 状态重组本身不足以消除该竞态。 */
internal class PromptSaveGate {
    private var saving = false

    fun tryStart(): Boolean {
        if (saving) return false
        saving = true
        return true
    }

    fun finish() {
        saving = false
    }
}

/**
 * 将普通异常收敛为可展示的保存失败，同时保持协程取消的结构化并发语义。
 */
internal suspend fun runPromptSave(
    text: String,
    persist: suspend (String) -> Result<Unit>,
): Result<Unit> = try {
    persist(text).also { result ->
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun Int.isCjkIdeograph(): Boolean =
    this in 0x1100..0x11FF ||
        this in 0x3040..0x30FF ||
        this in 0x3130..0x318F ||
        this in 0x31F0..0x31FF ||
        this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xAC00..0xD7AF ||
        this in 0xF900..0xFAFF ||
        this in 0x20000..0x2FA1F

private fun Int.isInternalWordPunctuation(): Boolean =
    this == '.'.code || this == '-'.code || this == '_'.code || this == '\''.code

private enum class EditorTab(@param:StringRes val labelRes: Int) {
    EDITOR(R.string.prompt_editor_tab_edit),
    PREVIEW(R.string.prompt_editor_tab_preview)
}

@Composable
fun UnifiedPromptEditor(
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<Unit>,
    initialText: String = "",
    title: String = "",
    placeholder: String = "",
    mode: EditorMode = EditorMode.DIALOG,
    fontSize: Int = 13
) {
    if (!show) return

    val textFieldState = rememberTextFieldState(initialText)
    val text = textFieldState.text.toString()
    var selectedTab by remember { mutableIntStateOf(EditorTab.EDITOR.ordinal) }
    var showDiscardConfirmation by remember(initialText) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val saveGate = remember { PromptSaveGate() }
    val coroutineScope = rememberCoroutineScope()

    val statistics = remember(text) { promptTextStatistics(text) }
    val lineCount = statistics.lines
    val charCount = statistics.characters
    val wordCount = statistics.words

    val resolvedTitle = title.ifBlank { stringResource(R.string.prompt_editor_default_title) }
    val resolvedPlaceholder = placeholder.ifBlank { stringResource(R.string.prompt_editor_default_placeholder) }
    val requestDismiss = {
        if (isSaving) {
            saveJob?.cancel()
            Unit
        } else if (hasUnsavedPromptChanges(initialText, text)) {
            showDiscardConfirmation = true
        } else {
            onDismiss()
        }
    }

    @Composable
    fun TopBar() {
        val closeDescription = stringResource(R.string.prompt_editor_cd_close)
        val saveDescription = stringResource(R.string.prompt_editor_cd_save)
        val savingDescription = stringResource(R.string.shared_loading)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = requestDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = closeDescription,
                    tint = NexaraColors.OnSurface
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = resolvedTitle,
                style = NexaraTypography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = NexaraColors.OnSurface,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = {
                    if (!saveGate.tryStart()) return@IconButton
                    isSaving = true
                    saveFailed = false
                    saveJob = coroutineScope.launch {
                        try {
                            val result = runPromptSave(text, onSave)
                            if (result.isSuccess) {
                                onDismiss()
                            } else {
                                saveFailed = true
                            }
                        } finally {
                            saveGate.finish()
                            isSaving = false
                            saveJob = null
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        if (isSaving) {
                            contentDescription = saveDescription
                            stateDescription = savingDescription
                        }
                    },
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = NexaraColors.Primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = saveDescription,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun TabBar() {
        val tabs = EditorTab.entries
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = NexaraColors.SurfaceContainer,
            contentColor = NexaraColors.OnSurface,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    height = 2.dp,
                    color = NexaraColors.Primary
                )
            },
            divider = {}
        ) {
            tabs.forEachIndexed { index, tab ->
                val label = stringResource(tab.labelRes)
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = label,
                            style = NexaraTypography.labelMedium.copy(
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (selectedTab == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                        )
                    }
                )
            }
        }
    }

    @Composable
    fun EditorPane(modifier: Modifier = Modifier) {
        val scrollState = rememberScrollState()
        val textMeasurer = rememberTextMeasurer()
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        val textLines = text.split("\n")
        val lineOffsets = remember(text) {
            val offsets = mutableListOf<Int>()
            var currentOffset = 0
            for (line in textLines) {
                offsets.add(currentOffset)
                currentOffset += line.length + 1
            }
            offsets
        }

        Row(modifier = modifier) {
            Canvas(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight()
                    .background(NexaraColors.SurfaceLowest)
                    .padding(end = 6.dp, top = 8.dp)
            ) {
                val layout = layoutResult
                if (layout != null) {
                    for (i in textLines.indices) {
                        val startOffset = lineOffsets.getOrNull(i) ?: continue
                        if (startOffset > text.length) continue

                        val physicalLine = layout.getLineForOffset(startOffset)
                        val topPx = layout.getLineTop(physicalLine)

                        val lineStr = (i + 1).toString()
                        val textLayout = textMeasurer.measure(
                            text = lineStr,
                            style = NexaraTypography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                            )
                        )

                        val x = size.width - textLayout.size.width
                        val physicalLineHeight = layout.getLineBottom(physicalLine) - topPx
                        val y = topPx +
                            (physicalLineHeight - textLayout.size.height) / 2f -
                            scrollState.value
                        if (y + textLayout.size.height < 0f || y > size.height) continue

                        drawText(
                            textLayoutResult = textLayout,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y)
                        )
                    }
                } else {
                    var currentY = 0f
                    val lineHeightPx = 20.sp.toPx()
                    for (i in textLines.indices) {
                        val lineStr = (i + 1).toString()
                        val textLayout = textMeasurer.measure(
                            text = lineStr,
                            style = NexaraTypography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                        val x = size.width - textLayout.size.width
                        val y = currentY +
                            (lineHeightPx - textLayout.size.height) / 2f -
                            scrollState.value
                        if (y + textLayout.size.height < 0f || y > size.height) {
                            currentY += lineHeightPx
                            continue
                        }
                        drawText(
                            textLayoutResult = textLayout,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y)
                        )
                        currentY += lineHeightPx
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                BasicTextField(
                    state = textFieldState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(UiTags.PROMPT_EDITOR_INPUT)
                        .semantics { contentDescription = resolvedPlaceholder }
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp),
                    onTextLayout = { getResult -> layoutResult = getResult() },
                    textStyle = NexaraTypography.bodySmall.copy(
                        color = NexaraColors.OnSurface,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(NexaraColors.Primary),
                    decorator = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = resolvedPlaceholder,
                                style = NexaraTypography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp
                                ),
                                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    },
                    scrollState = scrollState,
                )
            }
        }
    }

    @Composable
    fun PreviewPane(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            if (text.isBlank()) {
                Text(
                    text = resolvedPlaceholder,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f),
                )
            } else {
                MarkdownText(
                    markdown = text,
                    isStreaming = false,
                    fontSize = fontSize,
                    showCursor = false,
                )
            }
        }
    }

    @Composable
    fun BottomBar() {
        Column {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = NexaraColors.OutlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pluralStringResource(
                        id = R.plurals.prompt_editor_word_count,
                        count = wordCount,
                        wordCount
                    ),
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = pluralStringResource(
                        id = R.plurals.prompt_editor_line_count,
                        count = lineCount,
                        lineCount
                    ),
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = pluralStringResource(
                        id = R.plurals.prompt_editor_char_count,
                        count = charCount,
                        charCount
                    ),
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }

    @Composable
    fun Content() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NexaraColors.CanvasBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            TopBar()
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.shared_error_generic),
                    style = NexaraTypography.bodySmall,
                    color = NexaraColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            TabBar()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (EditorTab.entries[selectedTab]) {
                    EditorTab.EDITOR -> {
                        EditorPane(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    EditorTab.PREVIEW -> {
                        PreviewPane(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            BottomBar()
        }
    }

    when (mode) {
        EditorMode.DIALOG -> Dialog(
            onDismissRequest = requestDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Content()
        }
    }

    if (showDiscardConfirmation) {
        Dialog(onDismissRequest = { showDiscardConfirmation = false }) {
            NexaraConfirmDialog(
                title = resolvedTitle,
                message = stringResource(R.string.shared_action_cannot_undo),
                confirmText = stringResource(R.string.shared_btn_close),
                onConfirm = onDismiss,
                onCancel = { showDiscardConfirmation = false },
                isDestructive = true,
            )
        }
    }
}

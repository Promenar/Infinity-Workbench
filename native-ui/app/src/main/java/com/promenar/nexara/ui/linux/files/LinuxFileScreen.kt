package com.promenar.nexara.ui.linux.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxFileEntry
import com.promenar.nexara.domain.linux.LinuxFileType
import com.promenar.nexara.ui.common.FolderStatusMessage
import com.promenar.nexara.ui.common.NexaraCapsuleTabRow
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraNavigationHeader
import com.promenar.nexara.ui.common.NexaraTabItem
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.renderer.nexaraSyntaxTheme
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.NexaraSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 超过此长度的文本只为开头部分上色，其余保持纯文本，避免大文件卡顿。 */
private const val HIGHLIGHT_MAX_CHARS = 64 * 1024

/**
 * Linux 文件详情页。
 *
 * @param path 容器内绝对路径，例如 `/root/projects/run.sh`。
 * @param onBack 返回上一页。
 * @param onOpenTerminal 打开本地终端：`directory` 为文件所在目录，`command` 为“在终端运行”时要执行的命令。
 */
@Composable
fun LinuxFileScreen(
    path: String,
    onBack: () -> Unit,
    onOpenTerminal: (directory: String, command: String?) -> Unit,
) {
    val linux = (LocalContext.current.applicationContext as NexaraApplication).linux
    val viewModel: LinuxFileViewModel = viewModel(key = "linux_file:$path", factory = LinuxFileViewModel.factory(linux.files, path))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onShown() }
    LinuxFileContent(
        state = state,
        actions = LinuxFileActions(
            onBack = onBack,
            onSelectTab = viewModel::selectTab,
            onReload = viewModel::reload,
            onHexPage = viewModel::loadHexPage,
            onEdit = viewModel::startEditing,
            onRun = {
                val directory = linuxParentPath(state.path) ?: "/"
                onOpenTerminal(directory, "./" + shellQuote(state.name))
            },
            onChmod = viewModel::requestChmod,
        ),
    )
    val text = state.text
    UnifiedPromptEditor(
        show = state.editing && text != null,
        onDismiss = viewModel::stopEditing,
        onSave = { edited -> viewModel.save(edited) },
        initialText = text?.text.orEmpty(),
        title = state.name,
        placeholder = stringResource(R.string.linux_file_editor_placeholder),
        showPreviewTab = text?.kind == LinuxFileKind.MARKDOWN,
    )
    if (state.conflict != null) {
        Dialog(onDismissRequest = viewModel::dismissConflict) {
            NexaraConfirmDialog(
                title = stringResource(R.string.linux_file_conflict_title),
                message = stringResource(R.string.linux_file_conflict_message),
                confirmText = stringResource(R.string.linux_file_conflict_reload),
                cancelText = stringResource(R.string.linux_file_conflict_keep_editing),
                onConfirm = viewModel::reloadAfterConflict,
                onCancel = viewModel::dismissConflict,
                confirmButtonModifier = Modifier.testTag(UiTags.LINUX_FILE_CONFLICT_RELOAD),
            ) {
                TextButton(
                    onClick = viewModel::overwriteAfterConflict,
                    modifier = Modifier
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.LINUX_FILE_CONFLICT_OVERWRITE),
                ) { Text(stringResource(R.string.linux_file_conflict_overwrite), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    val entry = state.entry
    if (state.chmodOpen && entry != null) {
        LinuxChmodDialog(entry = entry, error = state.chmodError, onConfirm = viewModel::confirmChmod, onDismiss = viewModel::dismissChmod)
    }
}

internal data class LinuxFileActions(
    val onBack: () -> Unit = {},
    val onSelectTab: (LinuxFileTab) -> Unit = {},
    val onReload: () -> Unit = {},
    val onHexPage: (Int) -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRun: () -> Unit = {},
    val onChmod: () -> Unit = {},
)

@Composable
internal fun LinuxFileContent(state: LinuxFileUiState, actions: LinuxFileActions, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = NexaraSpacing.Large),
        ) {
            NexaraNavigationHeader(
                title = state.name,
                onBack = actions.onBack,
                titleStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = NexaraMonospace),
                supporting = {
                    Text(
                        state.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = { LinuxFileMoreMenu(state, actions) },
            )
            NexaraCapsuleTabRow(
                tabs = listOf(
                    NexaraTabItem(stringResource(R.string.linux_file_tab_content), testTag = "${UiTags.LINUX_FILE_TABS}:content"),
                    NexaraTabItem(stringResource(R.string.linux_file_tab_hex), testTag = "${UiTags.LINUX_FILE_TABS}:hex"),
                    NexaraTabItem(stringResource(R.string.linux_file_tab_properties), testTag = "${UiTags.LINUX_FILE_TABS}:properties"),
                ),
                selectedIndex = state.tab.ordinal,
                onTabSelected = { actions.onSelectTab(LinuxFileTab.entries[it]) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NexaraSpacing.Small)
                    .testTag(UiTags.LINUX_FILE_TABS),
            )
            AnimatedVisibility(visible = state.loading, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = NexaraSpacing.XSmall))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val error = state.error
                if (error != null) {
                    FolderStatusMessage(text = stringResource(R.string.linux_file_load_failed, linuxErrorText(error)), error = true) {
                        TextButton(onClick = actions.onReload, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                            Text(stringResource(R.string.shared_btn_retry))
                        }
                    }
                } else if (state.entry != null) {
                    AnimatedContent(
                        targetState = state.tab,
                        transitionSpec = { NexaraMotion.sharedAxisX(forward = targetState.ordinal > initialState.ordinal) },
                        label = "linux_file_tab",
                    ) { tab ->
                        when (tab) {
                            LinuxFileTab.CONTENT -> LinuxContentTab(state, actions)
                            LinuxFileTab.HEX -> LinuxHexTab(state, actions)
                            LinuxFileTab.PROPERTIES -> LinuxPropertiesTab(state)
                        }
                    }
                }
            }
            val entry = state.entry
            if (entry != null && entry.type != LinuxFileType.DIRECTORY) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                    modifier = Modifier.fillMaxWidth().padding(vertical = NexaraSpacing.Medium),
                ) {
                    if (state.canRun) {
                        FilledTonalButton(
                            onClick = actions.onRun,
                            modifier = Modifier.weight(1f).heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(UiTags.LINUX_FILE_RUN),
                        ) { Text(stringResource(R.string.linux_file_run), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Button(
                        onClick = actions.onEdit,
                        enabled = state.canEdit,
                        modifier = Modifier.weight(1f).heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(UiTags.LINUX_FILE_EDIT),
                    ) { Text(stringResource(R.string.linux_file_edit), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun LinuxFileMoreMenu(state: LinuxFileUiState, actions: LinuxFileActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(40.dp).testTag(UiTags.LINUX_FILE_MORE),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.linux_files_more), modifier = Modifier.size(20.dp)) }
        NexaraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.linux_file_reload)) }, onClick = { expanded = false; actions.onReload() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.linux_files_chmod)) },
                enabled = state.entry?.type.let { it != null && it != LinuxFileType.SYMLINK },
                onClick = { expanded = false; actions.onChmod() },
            )
        }
    }
}

// ── 内容 ──

@Composable
private fun LinuxContentTab(state: LinuxFileUiState, actions: LinuxFileActions) {
    val entry = state.entry ?: return
    val text = state.text
    Column(Modifier.fillMaxSize()) {
        LinuxContentSummary(entry, text, state.nowMs)
        if (text?.truncated == true) {
            Text(
                stringResource(R.string.linux_file_truncated, formatLinuxSize(text.shownBytes.toLong()), formatLinuxSize(text.totalSize)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(bottom = NexaraSpacing.Small),
            )
        }
        val textError = state.textError
        when {
            entry.type == LinuxFileType.DIRECTORY -> Unit
            textError != null -> FolderStatusMessage(text = stringResource(R.string.linux_file_load_failed, linuxErrorText(textError)), error = true) {
                TextButton(onClick = actions.onReload, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.shared_btn_retry))
                }
            }
            text == null -> Unit
            text.isBinary -> FolderStatusMessage(text = stringResource(R.string.linux_file_binary), icon = Icons.Rounded.Memory)
            text.text.isEmpty() -> FolderStatusMessage(text = stringResource(R.string.linux_file_empty), icon = Icons.AutoMirrored.Rounded.InsertDriveFile)
            else -> LinuxCodeView(text, Modifier.weight(1f))
        }
    }
}

/** 内容页顶部的摘要：类型、编码、换行、大小与行数；权限、属主属组与修改时间。 */
@Composable
private fun LinuxContentSummary(entry: LinuxFileEntry, text: LinuxTextPreview?, nowMs: Long) {
    val first = buildList {
        add(linuxTypeLabel(entry, text))
        text?.encoding?.let(::add)
        text?.lineEnding?.let { add(linuxLineEndingLabel(it)) }
        add(formatLinuxSize(entry.size))
        text?.lineCount?.takeIf { !text.isBinary }?.let { add(pluralStringResource(R.plurals.linux_file_line_count, it, it)) }
    }.joinToString(" · ")
    val second = "${formatLinuxPermissions(entry.type, entry.mode)} (${formatLinuxOctalMode(entry.mode)}) · ${entry.owner}:${entry.group} · " +
        linuxTimestampText(linuxTimestamp(entry.modifiedMs, nowMs))
    Column(Modifier.fillMaxWidth().padding(bottom = NexaraSpacing.Small)) {
        Text(first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(second, style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun linuxTypeLabel(entry: LinuxFileEntry, text: LinuxTextPreview?): String = when (entry.type) {
    LinuxFileType.DIRECTORY -> stringResource(R.string.linux_file_directory)
    LinuxFileType.OTHER -> stringResource(R.string.linux_file_special)
    else -> {
        val kind = stringResource(linuxFileKindLabel(text?.kind ?: linuxKindByName(entry.name) ?: LinuxFileKind.TEXT))
        if (entry.type == LinuxFileType.SYMLINK) stringResource(R.string.linux_file_link) + " · " + kind else kind
    }
}

/** 只读代码视图：行号列 + 自动换行的正文，按语言上色。 */
@Composable
private fun LinuxCodeView(text: LinuxTextPreview, modifier: Modifier) {
    val content = text.text
    val highlighted = rememberLinuxHighlight(content, text.kind)
    val lineStarts = remember(content) {
        buildList {
            add(0)
            content.forEachIndexed { index, c -> if (c == '\n' && index + 1 < content.length) add(index + 1) }
        }
    }
    val digits = lineStarts.size.toString().length
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace)
    val numberColor = MaterialTheme.colorScheme.outline
    SelectionContainer(modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip12()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .testTag(UiTags.LINUX_FILE_CONTENT),
            contentPadding = PaddingValues(vertical = NexaraSpacing.Medium, horizontal = NexaraSpacing.Small),
        ) {
            items(lineStarts.size) { line ->
                val start = lineStarts[line]
                val end = (lineStarts.getOrNull(line + 1)?.minus(1) ?: content.length).let { stop ->
                    if (stop > start && content[stop - 1] == '\r') stop - 1 else stop
                }
                val lineText = remember(highlighted, start, end) {
                    if (highlighted != null && end <= highlighted.length) highlighted.subSequence(start, end) else AnnotatedString(content.substring(start, end))
                }
                Row {
                    Text(
                        (line + 1).toString().padStart(digits),
                        style = codeStyle,
                        color = numberColor,
                        modifier = Modifier.padding(end = NexaraSpacing.Small),
                    )
                    Text(lineText, style = codeStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun Modifier.clip12() = clip(RoundedCornerShape(12.dp))

/** 预览与截图中同步上色；运行时在 Default 线程计算，完成前先显示纯文本。 */
@Composable
private fun rememberLinuxHighlight(content: String, kind: LinuxFileKind): AnnotatedString? {
    val syntax = kind.syntaxName ?: return null
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val compute = { highlightLinuxCode(content.take(HIGHLIGHT_MAX_CHARS), syntax, isDark) }
    if (LocalInspectionMode.current) return remember(content, syntax, isDark) { compute() }
    val result by produceState<AnnotatedString?>(null, content, syntax, isDark) {
        value = withContext(Dispatchers.Default) { compute() }
    }
    return result
}

private fun highlightLinuxCode(code: String, syntax: String, isDark: Boolean): AnnotatedString {
    val builder = dev.snipme.highlights.Highlights.Builder().theme(nexaraSyntaxTheme(isDark)).code(code)
    dev.snipme.highlights.model.SyntaxLanguage.getByName(syntax)?.let(builder::language)
    val highlights = builder.build().getHighlights()
    return buildAnnotatedString {
        append(code)
        highlights.forEach { highlight ->
            val span = when (highlight) {
                is dev.snipme.highlights.model.ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is dev.snipme.highlights.model.BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(start, code.length)
            if (end > start) addStyle(span, start, end)
        }
    }
}

// ── 十六进制 ──

@Composable
private fun LinuxHexTab(state: LinuxFileUiState, actions: LinuxFileActions) {
    val hex = state.hex
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = state.hexLoading, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = NexaraSpacing.XSmall))
        }
        val hexError = state.hexError
        when {
            hexError != null -> FolderStatusMessage(text = stringResource(R.string.linux_file_load_failed, linuxErrorText(hexError)), error = true) {
                TextButton(onClick = { actions.onHexPage(hex?.page ?: 0) }, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.shared_btn_retry))
                }
            }
            hex == null -> Box(Modifier.weight(1f))
            hex.bytes.isEmpty() -> FolderStatusMessage(text = stringResource(R.string.linux_file_empty), icon = Icons.AutoMirrored.Rounded.InsertDriveFile)
            else -> LinuxHexRowsView(hex, Modifier.weight(1f))
        }
        if (state.hexPageCount > 1) LinuxHexPager(page = hex?.page ?: 0, pageCount = state.hexPageCount, busy = state.hexLoading, onPage = actions.onHexPage)
    }
}

@Composable
private fun LinuxHexRowsView(hex: LinuxHexPage, modifier: Modifier) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip12()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val horizontalPadding = NexaraSpacing.Medium
        val charWidthPx = remember(style) { measurer.measure("0", style).size.width.coerceAtLeast(1) }
        val availablePx = with(LocalDensity.current) { (maxWidth - horizontalPadding * 2).toPx() }
        val bytesPerRow = linuxHexBytesPerRow((availablePx / charWidthPx).toInt())
        val rows = remember(hex, bytesPerRow) { linuxHexRows(hex.bytes, hex.offset, bytesPerRow) }
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(UiTags.LINUX_FILE_HEX),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = NexaraSpacing.Medium),
            ) {
                items(rows, key = { it.offset }) { row ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append(row.offset) }
                            append("  ")
                            append(row.hex)
                            append("  ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) { append(row.ascii) }
                        },
                        style = style,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinuxHexPager(page: Int, pageCount: Int, busy: Boolean, onPage: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small),
    ) {
        IconButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 0) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.linux_file_hex_previous))
        }
        Text(
            stringResource(R.string.linux_file_hex_page, page + 1, pageCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = NexaraSpacing.Medium)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = { onPage(page + 1) }, enabled = !busy && page < pageCount - 1) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.linux_file_hex_next))
        }
    }
}

// ── 属性 ──

@Composable
private fun LinuxPropertiesTab(state: LinuxFileUiState) {
    val entry = state.entry ?: return
    val text = state.text
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(UiTags.LINUX_FILE_PROPERTIES),
    ) {
        Column(Modifier.fillMaxWidth().bettboxListGroup().padding(vertical = 6.dp)) {
            PropertyRow(stringResource(R.string.linux_file_prop_path), entry.path, mono = true)
            PropertyRow(stringResource(R.string.linux_file_prop_type), linuxTypeLabel(entry, text))
            entry.symlinkTarget?.let { PropertyRow(stringResource(R.string.linux_file_prop_link), it, mono = true) }
            if (text != null && !text.isBinary) {
                text.encoding?.let { PropertyRow(stringResource(R.string.linux_file_prop_encoding), it) }
                text.lineEnding?.let { PropertyRow(stringResource(R.string.linux_file_prop_line_ending), linuxLineEndingLabel(it)) }
            }
            val size = buildList {
                add(formatLinuxSize(entry.size))
                if (entry.size >= 1024) add("${entry.size} B")
                text?.lineCount?.takeIf { !text.isBinary }?.let { add(pluralStringResource(R.plurals.linux_file_line_count, it, it)) }
            }.joinToString(" · ")
            PropertyRow(stringResource(R.string.linux_file_prop_size), size)
            PropertyRow(
                stringResource(R.string.linux_file_prop_permissions),
                "${formatLinuxPermissions(entry.type, entry.mode)} (${formatLinuxOctalMode(entry.mode)})",
                mono = true,
            )
            PropertyRow(stringResource(R.string.linux_file_prop_owner), "${entry.owner}:${entry.group}", mono = true)
            PropertyRow(stringResource(R.string.linux_file_prop_modified), linuxTimestampText(linuxTimestamp(entry.modifiedMs, state.nowMs)))
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String, mono: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = NexaraSpacing.Large, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = NexaraMonospace) else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

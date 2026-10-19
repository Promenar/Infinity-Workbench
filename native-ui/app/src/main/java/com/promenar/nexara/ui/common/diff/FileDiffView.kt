package com.promenar.nexara.ui.common.diff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors

/** 差异视图模式：统一视图逐行交替显示删除与新增；并排视图左旧右新。 */
enum class FileDiffViewMode { UNIFIED, SIDE_BY_SIDE }

object FileDiffViewDefaults {
    /** 内部视口的默认最大高度；放进可滚动父容器时必须保持有限值。 */
    val MaxViewportHeight: Dp = 480.dp

    /** 组件宽度低于此值时默认统一视图，否则默认并排视图。 */
    val SideBySideMinWidth: Dp = 600.dp
}

/**
 * 由新旧全文直接渲染差异。模型在组合中同步计算（受 [FileDiffLimits] 的行数、字节与回溯表规模上限约束）；
 * 输入可能接近上限时，应在 `Dispatchers.Default` 上调用 [FileDiffModel.fromTexts] 后改用模型重载。
 */
@Composable
fun FileDiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier,
    fileName: String? = null,
    isBinary: Boolean = false,
    changeKind: FileChangeKind? = null,
    initialMode: FileDiffViewMode? = null,
    maxViewportHeight: Dp = FileDiffViewDefaults.MaxViewportHeight,
) {
    val model = remember(oldText, newText, fileName, isBinary, changeKind) {
        FileDiffModel.fromTexts(oldText, newText, fileName, isBinary, changeKind)
    }
    FileDiffView(model, modifier, initialMode, maxViewportHeight)
}

/**
 * 可复用的文件差异视图：头部显示文件名与 `+N −M` 汇总；正文支持统一 / 并排两种视图、
 * 新旧行号、增删着色加 `+` / `−` 标记、未变更区段折叠展开，以及二进制与大文件降级。
 *
 * 滚动结构：正文是组件自有的单一视口——外层 `Box` 先以 `heightIn(max = maxViewportHeight)` 限高，
 * 再挂唯一一个横向 `Modifier.scrollable`；其内与视口等宽的 `LazyColumn` 负责纵向滚动与按需组合。
 * 横向滚动只平移代码列（各行代码文本按自然宽度测量、裁剪在单元格内并在放置阶段偏移），行号与标记栏固定，
 * 并排视图左右两侧同步平移且始终各占一半宽度。行内容回调中不叠加任何 `horizontalScroll` / `verticalScroll`，
 * 组件宽度始终等于父容器给定宽度，长行不会撑宽页面。超过 [DIFF_MAX_DISPLAY_CHARS] 的单行截断显示。
 *
 * 在可滚动父容器（聊天消息列表 `LazyColumn`、`verticalScroll` 页面等）中使用时：
 * - [maxViewportHeight] 必须是有限值（默认 480dp），否则内部 `LazyColumn` 会在无限高度约束下测量而崩溃；
 * - 视口内容可滚动时，纵向手势先由视口消费，到达边界后再交给父列表；
 * - 只有在父容器给出有界高度的全屏面板中，才可传 `Dp.Infinity` 并用 [modifier] 约束高度；
 * - 父容器的宽度必须有界，不要把组件放进 `horizontalScroll` 或 `LazyRow` 等横向无界容器。
 *
 * @param model 由 [FileDiffModel.fromTexts] 或 [FileDiffModel.fromDiffLines] 构建的数据模型。
 * @param initialMode 初始视图模式；为空时按宽度自动选择（低于 [FileDiffViewDefaults.SideBySideMinWidth] 用统一视图）。
 *   用户在头部切换后以用户选择为准。
 * @param maxViewportHeight 正文视口的最大高度，内容较少时按内容高度收缩。
 */
@Composable
fun FileDiffView(
    model: FileDiffModel,
    modifier: Modifier = Modifier,
    initialMode: FileDiffViewMode? = null,
    maxViewportHeight: Dp = FileDiffViewDefaults.MaxViewportHeight,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewportWidth = maxWidth
        val autoMode = if (viewportWidth < FileDiffViewDefaults.SideBySideMinWidth) {
            FileDiffViewMode.UNIFIED
        } else {
            FileDiffViewMode.SIDE_BY_SIDE
        }
        var chosenMode by rememberSaveable { mutableStateOf(initialMode) }
        val mode = chosenMode ?: autoMode
        val content = model.content
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                FileDiffHeader(
                    model = model,
                    mode = mode,
                    showModeToggle = content is FileDiffContent.Lines && content.lines.isNotEmpty(),
                    onToggleMode = {
                        chosenMode = if (mode == FileDiffViewMode.UNIFIED) {
                            FileDiffViewMode.SIDE_BY_SIDE
                        } else {
                            FileDiffViewMode.UNIFIED
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (content) {
                    FileDiffContent.Binary -> DiffMessage(stringResource(R.string.file_diff_binary))
                    is FileDiffContent.Summary -> DiffMessage(summaryMessage(model, content.reason))
                    is FileDiffContent.Lines -> if (content.lines.isEmpty()) {
                        DiffMessage(stringResource(R.string.file_diff_no_changes))
                    } else {
                        DiffViewport(content, mode, viewportWidth, maxViewportHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDiffHeader(
    model: FileDiffModel,
    mode: FileDiffViewMode,
    showModeToggle: Boolean,
    onToggleMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(
                start = NexaraSpacing.Medium,
                end = if (showModeToggle) NexaraSpacing.XSmall else NexaraSpacing.Medium,
                top = NexaraSpacing.XSmall,
                bottom = NexaraSpacing.XSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        if (model.fileName != null) {
            NexaraIdentifierText(
                text = model.fileName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        DiffCounts(model, if (model.fileName == null) Modifier.weight(1f) else Modifier)
        if (showModeToggle) {
            val toSideBySide = mode == FileDiffViewMode.UNIFIED
            IconButton(onClick = onToggleMode, modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget)) {
                Icon(
                    imageVector = if (toSideBySide) Icons.Rounded.VerticalSplit else Icons.Rounded.ViewStream,
                    contentDescription = stringResource(
                        if (toSideBySide) R.string.file_diff_switch_to_side_by_side else R.string.file_diff_switch_to_unified,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 头部 `+N −M` 汇总：颜色之外以符号区分，读屏读出“新增 N 行，删除 M 行”。 */
@Composable
private fun DiffCounts(model: FileDiffModel, modifier: Modifier) {
    val added = model.headerAdded
    val removed = model.headerRemoved
    if (added == null && removed == null) return
    val addedColor = MaterialTheme.nexaraDomainColors.success
    val removedColor = MaterialTheme.colorScheme.error
    val parts = listOfNotNull(
        added?.let { pluralStringResource(R.plurals.file_diff_added_lines_a11y, it, it) },
        removed?.let { pluralStringResource(R.plurals.file_diff_removed_lines_a11y, it, it) },
    )
    val joined = if (parts.size == 2) stringResource(R.string.file_diff_counts_a11y, parts[0], parts[1]) else parts.first()
    val description = if (model.stats?.isApproximate == true) {
        stringResource(R.string.file_diff_approximate_counts, joined)
    } else {
        joined
    }
    Text(
        text = buildAnnotatedString {
            if (added != null) withStyle(SpanStyle(color = addedColor)) { append("+$added") }
            if (added != null && removed != null) append(" ")
            if (removed != null) withStyle(SpanStyle(color = removedColor)) { append("−$removed") }
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = NexaraMonospace),
        softWrap = false,
        maxLines = 1,
    )
}

@Composable
private fun summaryMessage(model: FileDiffModel, reason: FileDiffSummaryReason): String {
    val counts = model.countsLabel.orEmpty()
    val shownCounts = if (model.stats?.isApproximate == true) {
        stringResource(R.string.file_diff_approximate_counts, counts)
    } else {
        counts
    }
    return when (reason) {
        FileDiffSummaryReason.LARGE_FILE -> stringResource(R.string.file_diff_large_summary, shownCounts)
        FileDiffSummaryReason.TOO_COMPLEX -> stringResource(R.string.file_diff_complex_summary, shownCounts)
    }
}

@Composable
private fun DiffMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(NexaraSpacing.Large),
    )
}

/** 列表项：统一视图的一行、并排视图的一行，或一个尚未展开的折叠区段。 */
private sealed interface DiffListItem {
    val key: String
    val contentType: Int

    data class Unified(val line: NumberedDiffLine, override val key: String) : DiffListItem {
        override val contentType: Int get() = 0
    }

    data class Paired(val row: SideBySideRow, override val key: String) : DiffListItem {
        override val contentType: Int get() = 1
    }

    data class Fold(val segmentIndex: Int, val hiddenCount: Int) : DiffListItem {
        override val key: String get() = "fold-$segmentIndex"
        override val contentType: Int get() = 2
    }

    /** 内容未保存的未变更区段（见 [OmittedDiffSegment]），不可展开。 */
    data class Omitted(val segmentIndex: Int, val lineCount: Int?) : DiffListItem {
        override val key: String get() = "omitted-$segmentIndex"
        override val contentType: Int get() = 3
    }
}

private fun buildDiffItems(
    segments: List<DiffSegment>,
    pairedSegments: List<List<SideBySideRow>>?,
    expanded: Set<Int>,
): List<DiffListItem> = buildList {
    segments.forEachIndexed { segmentIndex, segment ->
        when {
            segment is OmittedDiffSegment -> add(DiffListItem.Omitted(segmentIndex, segment.lineCount))
            segment is DiffSegment.Folded && segmentIndex !in expanded ->
                add(DiffListItem.Fold(segmentIndex, segment.lines.size))
            pairedSegments != null -> pairedSegments[segmentIndex].forEachIndexed { index, row ->
                add(DiffListItem.Paired(row, "pair-$segmentIndex-$index"))
            }
            else -> segment.lines.forEachIndexed { index, line ->
                add(DiffListItem.Unified(line, "line-$segmentIndex-$index"))
            }
        }
    }
}

/** 行号栏、标记栏与代码列内容的测量宽度；全部按当前字体与字号测量，大字号下同步放大。 */
@Immutable
private data class DiffMetrics(
    val numberWidth: Dp,
    val markerWidth: Dp,
    val unifiedCodeWidth: Dp,
    val sideCodeWidth: Dp,
)

private val DiffNumberPadding = 6.dp
private val DiffCodePadding = 8.dp
private val DiffDividerWidth = 1.dp

@Composable
private fun rememberDiffMetrics(lines: List<NumberedDiffLine>, codeStyle: TextStyle): DiffMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(lines, codeStyle, density, measurer) {
        fun width(text: String): Dp = with(density) {
            measurer.measure(text, codeStyle, softWrap = false, maxLines = 1).size.width.toDp()
        }
        fun codeWidth(candidates: List<String>): Dp =
            (candidates.maxOfOrNull { width(it) } ?: 0.dp) + DiffCodePadding
        val maxNumber = lines.maxOf { maxOf(it.oldLineNumber ?: 0, it.newLineNumber ?: 0) }
        val digits = maxOf(2, maxNumber.toString().length)
        DiffMetrics(
            numberWidth = width("0".repeat(digits)) + DiffNumberPadding * 2,
            markerWidth = maxOf(width("+"), width("−")) + DiffNumberPadding,
            unifiedCodeWidth = codeWidth(widestDisplayCandidates(lines)),
            sideCodeWidth = maxOf(
                codeWidth(widestDisplayCandidates(lines.filter { it.kind != DiffLineKind.ADDED })),
                codeWidth(widestDisplayCandidates(lines.filter { it.kind != DiffLineKind.REMOVED })),
            ),
        )
    }
}

/**
 * 正文视口：限高的 `Box` 上挂唯一的横向 `scrollable`，内部 `LazyColumn` 与视口等宽并负责纵向滚动。
 * 横向滚动只平移代码列（行号与标记栏固定），并排视图左右两侧同步平移；偏移量在放置阶段读取，滚动时不触发行重组。
 */
@Composable
private fun DiffViewport(
    content: FileDiffContent.Lines,
    mode: FileDiffViewMode,
    viewportWidth: Dp,
    maxViewportHeight: Dp,
) {
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = NexaraMonospace,
        lineBreak = LineBreak.Simple,
    )
    val metrics = rememberDiffMetrics(content.lines, codeStyle)
    val colors = fileDiffColors()
    var expanded by remember(content) { mutableStateOf(emptySet<Int>()) }
    val pairedSegments = remember(content, mode) {
        if (mode == FileDiffViewMode.SIDE_BY_SIDE) content.segments.map { pairSideBySide(it.lines) } else null
    }
    val items = remember(content, pairedSegments, expanded) {
        buildDiffItems(content.segments, pairedSegments, expanded)
    }
    // 代码列可见宽度：统一视图扣除两栏行号与标记栏；并排视图按半宽扣除一栏行号与标记栏。
    val codeViewportWidth = when (mode) {
        FileDiffViewMode.UNIFIED -> viewportWidth - metrics.numberWidth * 2 - metrics.markerWidth
        FileDiffViewMode.SIDE_BY_SIDE -> (viewportWidth - DiffDividerWidth) / 2 - metrics.numberWidth - metrics.markerWidth
    }
    val codeContentWidth = if (mode == FileDiffViewMode.UNIFIED) metrics.unifiedCodeWidth else metrics.sideCodeWidth
    val maxScroll = with(LocalDensity.current) {
        if (codeViewportWidth.value.isFinite()) (codeContentWidth - codeViewportWidth).toPx().coerceAtLeast(0f) else 0f
    }
    val currentMaxScroll by rememberUpdatedState(maxScroll)
    var scrollPx by remember { mutableFloatStateOf(0f) }
    val scrollableState = rememberScrollableState { delta ->
        val current = scrollPx.coerceAtMost(currentMaxScroll)
        val target = (current - delta).coerceIn(0f, currentMaxScroll)
        scrollPx = target
        current - target
    }
    val codeOffset: () -> Int = remember { { -scrollPx.coerceAtMost(currentMaxScroll).roundToInt() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxViewportHeight)
            .scrollable(scrollableState, Orientation.Horizontal, enabled = maxScroll > 0f),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = NexaraSpacing.XSmall),
        ) {
            items(items, key = { it.key }, contentType = { it.contentType }) { item ->
                when (item) {
                    is DiffListItem.Unified -> UnifiedDiffRow(item.line, metrics, colors, codeStyle, codeOffset)
                    is DiffListItem.Paired -> SideBySideDiffRow(item.row, metrics, colors, codeStyle, codeOffset)
                    is DiffListItem.Fold -> FoldedDiffRow(
                        hiddenCount = item.hiddenCount,
                        colors = colors,
                        onExpand = { expanded = expanded + item.segmentIndex },
                    )
                    is DiffListItem.Omitted -> OmittedDiffRow(item.lineCount, colors)
                }
            }
        }
    }
}

@Composable
private fun UnifiedDiffRow(
    line: NumberedDiffLine,
    metrics: DiffMetrics,
    colors: FileDiffColors,
    codeStyle: TextStyle,
    codeOffset: () -> Int,
) {
    val palette = colors.paletteFor(line.kind)
    val description = lineDescription(line)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(palette.background)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        LineNumberCell(line.oldLineNumber, metrics.numberWidth, palette, colors, codeStyle)
        LineNumberCell(line.newLineNumber, metrics.numberWidth, palette, colors, codeStyle)
        DiffLineBody(line, metrics, palette, colors, codeStyle, codeOffset, Modifier.weight(1f))
    }
}

@Composable
private fun SideBySideDiffRow(
    row: SideBySideRow,
    metrics: DiffMetrics,
    colors: FileDiffColors,
    codeStyle: TextStyle,
    codeOffset: () -> Int,
) {
    val oldDescription = row.old?.let { lineDescription(it) }
    val newDescription = row.new?.let { lineDescription(it) }
    val description = when {
        row.old === row.new || newDescription == null -> oldDescription.orEmpty()
        oldDescription == null -> newDescription
        else -> stringResource(R.string.file_diff_pair_a11y, oldDescription, newDescription)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        SideCell(row.old, row.old?.oldLineNumber, metrics, colors, codeStyle, codeOffset, Modifier.weight(1f))
        Box(
            Modifier
                .width(DiffDividerWidth)
                .fillMaxHeight()
                .background(colors.divider),
        )
        SideCell(row.new, row.new?.newLineNumber, metrics, colors, codeStyle, codeOffset, Modifier.weight(1f))
    }
}

@Composable
private fun SideCell(
    line: NumberedDiffLine?,
    number: Int?,
    metrics: DiffMetrics,
    colors: FileDiffColors,
    codeStyle: TextStyle,
    codeOffset: () -> Int,
    modifier: Modifier,
) {
    if (line == null) {
        Box(modifier.fillMaxHeight().background(colors.emptySide))
        return
    }
    val palette = colors.paletteFor(line.kind)
    Row(modifier.fillMaxHeight().background(palette.background)) {
        LineNumberCell(number, metrics.numberWidth, palette, colors, codeStyle)
        DiffLineBody(line, metrics, palette, colors, codeStyle, codeOffset, Modifier.weight(1f))
    }
}

/**
 * 标记栏 + 代码列；缺少末尾换行的行在代码下方追加 Git 风格提示。
 * 代码列按内容自然宽度测量（不受单元格宽度约束），裁剪在单元格内并按 [codeOffset] 平移。
 */
@Composable
private fun DiffLineBody(
    line: NumberedDiffLine,
    metrics: DiffMetrics,
    palette: DiffLinePalette,
    colors: FileDiffColors,
    codeStyle: TextStyle,
    codeOffset: () -> Int,
    modifier: Modifier,
) {
    Row(modifier) {
        Text(
            text = when (line.kind) {
                DiffLineKind.ADDED -> "+"
                DiffLineKind.REMOVED -> "−"
                DiffLineKind.CONTEXT -> " "
            },
            style = codeStyle.copy(textAlign = TextAlign.Center),
            color = palette.marker,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.width(metrics.markerWidth),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            Column(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(codeOffset(), 0) }
                    .padding(end = DiffCodePadding),
            ) {
                Text(
                    text = diffDisplayText(line.content),
                    style = codeStyle,
                    color = colors.code,
                    softWrap = false,
                    maxLines = 1,
                )
                if (line.missingFinalNewline) {
                    Text(
                        text = stringResource(R.string.file_diff_no_newline_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.number,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LineNumberCell(
    number: Int?,
    width: Dp,
    palette: DiffLinePalette,
    colors: FileDiffColors,
    codeStyle: TextStyle,
) {
    Text(
        text = number?.toString().orEmpty(),
        style = codeStyle.copy(textAlign = TextAlign.End),
        color = colors.number,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(palette.gutter)
            .padding(horizontal = DiffNumberPadding),
    )
}

/** 折叠区段：整行可点，读屏按“展开 N 行未变更内容”按钮朗读。 */
@Composable
private fun FoldedDiffRow(
    hiddenCount: Int,
    colors: FileDiffColors,
    onExpand: () -> Unit,
) {
    val label = pluralStringResource(R.plurals.file_diff_expand_unchanged, hiddenCount, hiddenCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .background(colors.fold)
            .clickable(
                onClickLabel = stringResource(R.string.file_diff_expand_action),
                role = Role.Button,
                onClick = onExpand,
            )
            .padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Icon(
            imageVector = Icons.Rounded.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(NexaraSpacing.RowIconSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 未保存内容的折叠区段：与折叠行同样的底色，但不可点击，说明这些未变更行没有随记录保存。 */
@Composable
private fun OmittedDiffRow(lineCount: Int?, colors: FileDiffColors) {
    val label = if (lineCount != null) {
        pluralStringResource(R.plurals.file_diff_omitted_unchanged, lineCount, lineCount)
    } else {
        stringResource(R.string.file_diff_omitted_unknown)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .background(colors.fold)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreHoriz,
            contentDescription = null,
            tint = colors.number,
            modifier = Modifier.size(NexaraSpacing.RowIconSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.number,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun lineDescription(line: NumberedDiffLine): String {
    val text = diffDisplayText(line.content).ifBlank { stringResource(R.string.file_diff_line_blank_a11y) }
    val base = when (line.kind) {
        DiffLineKind.ADDED -> stringResource(R.string.file_diff_line_added_a11y, line.newLineNumber ?: 0, text)
        DiffLineKind.REMOVED -> stringResource(R.string.file_diff_line_removed_a11y, line.oldLineNumber ?: 0, text)
        DiffLineKind.CONTEXT -> stringResource(R.string.file_diff_line_context_a11y, line.newLineNumber ?: 0, text)
    }
    return if (line.missingFinalNewline) stringResource(R.string.file_diff_line_no_newline_a11y, base) else base
}

/** 单类差异行的配色：整行底色、行号栏底色与 `+` / `−` 标记色。 */
@Immutable
private data class DiffLinePalette(
    val background: Color,
    val gutter: Color,
    val marker: Color,
)

@Immutable
private data class FileDiffColors(
    val context: DiffLinePalette,
    val added: DiffLinePalette,
    val removed: DiffLinePalette,
    val emptySide: Color,
    val fold: Color,
    val divider: Color,
    val number: Color,
    val code: Color,
) {
    fun paletteFor(kind: DiffLineKind): DiffLinePalette = when (kind) {
        DiffLineKind.CONTEXT -> context
        DiffLineKind.ADDED -> added
        DiffLineKind.REMOVED -> removed
    }
}

/**
 * 增删配色取自主题 token：新增用 `successContainer` / `success`，删除用 `errorContainer` / `error`，
 * 底色以半透明叠在 `surfaceContainerLowest` 上，深浅色与动态取色下正文都保持 `onSurface` 可读。
 */
@Composable
@ReadOnlyComposable
private fun fileDiffColors(): FileDiffColors {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.nexaraDomainColors
    return FileDiffColors(
        context = DiffLinePalette(
            background = Color.Transparent,
            gutter = scheme.surfaceContainerLow,
            marker = scheme.onSurfaceVariant,
        ),
        added = DiffLinePalette(
            background = domain.successContainer.copy(alpha = 0.45f),
            gutter = domain.successContainer.copy(alpha = 0.75f),
            marker = domain.success,
        ),
        removed = DiffLinePalette(
            background = scheme.errorContainer.copy(alpha = 0.45f),
            gutter = scheme.errorContainer.copy(alpha = 0.75f),
            marker = scheme.error,
        ),
        emptySide = scheme.surfaceContainer,
        fold = scheme.surfaceContainerLow,
        divider = scheme.outlineVariant,
        number = scheme.onSurfaceVariant,
        code = scheme.onSurface,
    )
}

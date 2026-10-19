package com.promenar.nexara.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal val TableMinColumnWidth = 56.dp
internal val TableMaxColumnWidth = 280.dp
private val TableCellHorizontalPadding = 12.dp
private val TableCellVerticalPadding = 10.dp

/** 表格的纯文本与原始单元格节点；节点用于渲染单元格内的行内 Markdown。 */
internal data class ParsedTable(
    val headerCells: List<String>,
    val rows: List<List<String>>,
    val source: String = "",
    val headerNodes: List<ASTNode> = emptyList(),
    val rowNodes: List<List<ASTNode>> = emptyList(),
)

internal fun parseMarkdownTable(content: String, node: ASTNode): ParsedTable? {
    if (node.type != GFMElementTypes.TABLE) return null
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER } ?: return null
    val headerNodes = headerNode.children.filter { it.type == GFMTokenTypes.CELL }
    if (headerNodes.isEmpty()) return null
    val rowNodes = node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }

    fun text(cell: ASTNode) = content.substring(cell.startOffset, cell.endOffset).trim()
    return ParsedTable(
        headerCells = headerNodes.map(::text),
        rows = rowNodes.map { row -> row.map(::text) },
        source = content,
        headerNodes = headerNodes,
        rowNodes = rowNodes,
    )
}

/**
 * 每列取最宽单元格的不换行宽度（含左右内边距），限制在 [minimum, maximum] 之间；
 * 超过上限的长单元格在列内换行。[measure] 的行号 -1 表示表头。
 */
internal fun tableColumnWidths(
    table: ParsedTable,
    minimum: Dp = TableMinColumnWidth,
    maximum: Dp = TableMaxColumnWidth,
    measure: (column: Int, row: Int) -> Dp,
): List<Dp> = table.headerCells.indices.map { column ->
    val widest = (listOf(-1) + table.rows.indices).maxOf { row -> measure(column, row) }
    (widest + TableCellHorizontalPadding * 2).coerceIn(minimum, maximum)
}

internal data class TableLayout(
    val header: List<AnnotatedString>,
    val rows: List<List<AnnotatedString>>,
    val columnWidths: List<Dp>,
) {
    val totalWidth: Dp get() = columnWidths.fold(0.dp) { sum, width -> sum + width }
}

/** 在组合外按同一套样式测量表格；顶层宽内容宿主与表格组件共用，保证两者宽度一致。 */
internal class TableMeasurer(
    private val settings: AnnotatorSettings,
    private val textMeasurer: TextMeasurer,
    private val density: Density,
    val headerStyle: TextStyle,
    val bodyStyle: TextStyle,
) {
    fun layout(table: ParsedTable): TableLayout {
        val header = table.headerCells.indices.map { column ->
            cellText(table, table.headerNodes.getOrNull(column), table.headerCells[column])
        }
        val rows = table.rows.mapIndexed { rowIndex, row ->
            table.headerCells.indices.map { column ->
                cellText(table, table.rowNodes.getOrNull(rowIndex)?.getOrNull(column), row.getOrNull(column).orEmpty())
            }
        }
        val widths = tableColumnWidths(table) { column, row ->
            val text = if (row < 0) header[column] else rows[row][column]
            if (text.isEmpty()) {
                0.dp
            } else {
                val style = if (row < 0) headerStyle else bodyStyle
                // 多留 1dp，避免像素取整后不换行宽度恰好差一点而折行。
                with(density) { textMeasurer.measure(text, style, softWrap = false).size.width.toDp() } + 1.dp
            }
        }
        return TableLayout(header, rows, widths)
    }

    private fun cellText(table: ParsedTable, node: ASTNode?, fallback: String): AnnotatedString {
        if (node == null || table.source.isEmpty()) return AnnotatedString(fallback)
        val built = buildAnnotatedString { buildMarkdownAnnotatedString(table.source, node.children, settings) }
            .withoutInlineContentPlaceholders()
        val start = built.text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return AnnotatedString("")
        val end = built.text.indexOfLast { !it.isWhitespace() } + 1
        return built.subSequence(start, end)
    }
}

@Composable
internal fun rememberTableMeasurer(fontSize: Int): TableMeasurer {
    val settings = annotatorSettings()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = NexaraTypography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.4).sp,
    )
    val bodyStyle = NexaraTypography.bodySmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.4).sp,
    )
    return remember(settings, textMeasurer, density, headerStyle, bodyStyle) {
        TableMeasurer(settings, textMeasurer, density, headerStyle, bodyStyle)
    }
}

@Composable
fun NexaraTableWidget(
    model: MarkdownComponentModel,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
) {
    val table = remember(model.content, model.node.startOffset, model.node.endOffset) {
        parseMarkdownTable(model.content, model.node)
    } ?: return
    val measurer = rememberTableMeasurer(fontSize)
    val layout = remember(table, measurer) { measurer.layout(table) }
    NexaraTableWidget(layout = layout, measurer = measurer, modifier = modifier)
}

/**
 * 仅负责有限宽度内的表格绘制：列宽按内容比例缩放到可用宽度。
 * 横向手势由 Markdown 顶层 AST 宿主注册（宽度取 [TableLayout.totalWidth]），避免在第三方回调内嵌套滚动。
 */
@Composable
internal fun NexaraTableWidget(
    layout: TableLayout,
    measurer: TableMeasurer,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        val total = layout.totalWidth
        val scale = if (total > 0.dp) maxWidth / total else 1f
        val widths = layout.columnWidths.map { it * scale }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .height(IntrinsicSize.Max),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                layout.header.forEachIndexed { column, text ->
                    TableCell(text, measurer.headerStyle, widths[column])
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            layout.rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (rowIndex % 2 == 0) {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        )
                        .height(IntrinsicSize.Max),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEachIndexed { column, text ->
                        TableCell(text, measurer.bodyStyle, widths[column])
                    }
                }
                if (rowIndex < layout.rows.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: AnnotatedString,
    style: TextStyle,
    width: Dp,
) {
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .width(width)
            .padding(horizontal = TableCellHorizontalPadding, vertical = TableCellVerticalPadding),
    )
}

/**
 * 渲染库为行内代码等插入 U+FFFD 内联内容占位符，单元格不提供对应的 inlineContent，
 * 保留会显示成替换字符；去掉占位符并保留其余样式与链接。
 */
internal fun AnnotatedString.withoutInlineContentPlaceholders(): AnnotatedString {
    if ('\uFFFD' !in text) return this
    return buildAnnotatedString {
        var start = 0
        text.forEachIndexed { index, char ->
            if (char == '\uFFFD') {
                if (index > start) append(this@withoutInlineContentPlaceholders.subSequence(start, index))
                start = index + 1
            }
        }
        if (start < text.length) append(this@withoutInlineContentPlaceholders.subSequence(start, text.length))
    }
}

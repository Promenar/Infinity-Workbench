package com.promenar.nexara.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private val DefaultTableColumnWidth = 120.dp

internal data class ParsedTable(
    val headerCells: List<String>,
    val rows: List<List<String>>,
) {
    val requiredWidth: Dp
        get() = DefaultTableColumnWidth * headerCells.size.coerceAtLeast(1)
}

internal fun parseMarkdownTable(content: String, node: ASTNode): ParsedTable? {
    if (node.type != GFMElementTypes.TABLE) return null
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER } ?: return null
    val headerCells = headerNode.children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell -> content.substring(cell.startOffset, cell.endOffset).trim() }
    if (headerCells.isEmpty()) return null

    val rows = node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { row ->
            row.children
                .filter { it.type == GFMTokenTypes.CELL }
                .map { cell -> content.substring(cell.startOffset, cell.endOffset).trim() }
        }

    return ParsedTable(headerCells, rows)
}

@Composable
fun NexaraTableWidget(
    model: MarkdownComponentModel,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    minColumnWidth: Dp = 80.dp,
    maxColumnWidth: Dp = 200.dp,
) {
    val table = remember(model.content, model.node.startOffset, model.node.endOffset) {
        parseMarkdownTable(model.content, model.node)
    } ?: return
    val preferredColumnWidth = DefaultTableColumnWidth.coerceIn(minColumnWidth, maxColumnWidth)

    NexaraTableWidget(
        table = table,
        modifier = modifier,
        fontSize = fontSize,
        preferredColumnWidth = preferredColumnWidth,
    )
}

/**
 * 仅负责有限宽度内的表格绘制。横向手势由 Markdown 顶层 AST 宿主注册，避免在第三方回调内嵌套滚动。
 */
@Composable
internal fun NexaraTableWidget(
    table: ParsedTable,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    preferredColumnWidth: Dp = DefaultTableColumnWidth,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLow),
    ) {
        val fixedColumns = maxWidth <= table.requiredWidth
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer)
                    .height(IntrinsicSize.Max),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                table.headerCells.forEach { cellText ->
                    TableCell(
                        text = cellText,
                        isHeader = true,
                        columnWidth = preferredColumnWidth,
                        fixedWidth = fixedColumns,
                        fontSize = fontSize,
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = NexaraColors.OutlineVariant.copy(alpha = 0.4f),
            )

            table.rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (rowIndex % 2 == 0) NexaraColors.SurfaceLowest
                            else NexaraColors.SurfaceLow,
                        )
                        .height(IntrinsicSize.Max),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    table.headerCells.indices.forEach { columnIndex ->
                        TableCell(
                            text = row.getOrNull(columnIndex).orEmpty(),
                            isHeader = false,
                            columnWidth = preferredColumnWidth,
                            fixedWidth = fixedColumns,
                            fontSize = fontSize,
                        )
                    }
                }
                if (rowIndex < table.rows.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = NexaraColors.OutlineVariant.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    isHeader: Boolean,
    columnWidth: Dp,
    fixedWidth: Boolean,
    fontSize: Int,
) {
    Text(
        text = text,
        style = if (isHeader) {
            NexaraTypography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = NexaraColors.OnSurface,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.4).sp
            )
        } else {
            NexaraTypography.bodySmall.copy(
                color = NexaraColors.OnSurfaceVariant,
                fontSize = (fontSize - 1).coerceAtLeast(10).sp,
                lineHeight = ((fontSize - 1).coerceAtLeast(10) * 1.4).sp
            )
        },
        modifier = Modifier
            .then(
                if (fixedWidth) Modifier.width(columnWidth)
                else Modifier.weight(1f)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

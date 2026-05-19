package com.promenar.nexara.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private data class ParsedTable(
    val headerCells: List<String>,
    val rows: List<List<String>>,
)

private fun parseTable(model: MarkdownComponentModel): ParsedTable? {
    val headerNode = model.node.children.find { it.type == GFMElementTypes.HEADER } ?: return null
    val headerCells = headerNode.children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell -> model.content.substring(cell.startOffset, cell.endOffset).trim() }

    val rows = model.node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { row ->
            row.children
                .filter { it.type == GFMTokenTypes.CELL }
                .map { cell -> model.content.substring(cell.startOffset, cell.endOffset).trim() }
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
    val table = remember(model) { parseTable(model) } ?: return

    val cellWidth = 120.dp
    val columnCount = table.headerCells.size
    val totalWidth = cellWidth * columnCount

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLow)
    ) {
        val columnWidth = 120.dp
        val columnCount = table.headerCells.size
        val totalWidth = columnWidth * columnCount
        val scrollable = maxWidth < totalWidth

        Column(
            modifier = if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(totalWidth)
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            Row(
                modifier = Modifier
                    .then(if (scrollable) Modifier.width(totalWidth) else Modifier.fillMaxWidth())
                    .background(NexaraColors.SurfaceContainer)
                    .height(IntrinsicSize.Max),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                table.headerCells.forEach { cellText ->
                    TableCell(
                        text = cellText,
                        isHeader = true,
                        columnWidth = if (scrollable) columnWidth else 0.dp,
                        weight = if (scrollable) 0f else 1f,
                        fontSize = fontSize
                    )
                }
            }

            // 表头与正文之间的分隔线
            HorizontalDivider(
                thickness = 0.5.dp,
                color = NexaraColors.OutlineVariant.copy(alpha = 0.4f)
            )

            table.rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .then(if (scrollable) Modifier.width(totalWidth) else Modifier.fillMaxWidth())
                        .background(
                            if (rowIndex % 2 == 0) NexaraColors.SurfaceLowest
                            else NexaraColors.SurfaceLow
                        )
                        .height(IntrinsicSize.Max),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { cellText ->
                        TableCell(
                            text = cellText,
                            isHeader = false,
                            columnWidth = if (scrollable) columnWidth else 0.dp,
                            weight = if (scrollable) 0f else 1f,
                            fontSize = fontSize
                        )
                    }
                }
                // 行间细分割线（最后一行除外）
                if (rowIndex < table.rows.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = NexaraColors.OutlineVariant.copy(alpha = 0.2f)
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
    weight: Float,
    fontSize: Int
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
                if (weight > 0f) Modifier.weight(weight)
                else Modifier.width(columnWidth)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

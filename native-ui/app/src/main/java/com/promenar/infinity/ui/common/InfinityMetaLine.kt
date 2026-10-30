package com.promenar.infinity.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** 分段元数据之间的分隔符；只在同一行相邻的两段之间绘制。 */
const val InfinityMetaSeparator = "·"

/**
 * 元数据行：每一段作为整体排列，放不下时整段换到下一行；分隔符只出现在同一行相邻两段之间，
 * 不会出现在行首或行尾。用于“类型 · 状态 · 凭据”“大小 · 时间”这类标签串，替代拼接成单个字符串的写法。
 *
 * - 单段本身超过可用宽度时独占一行并按主题换行策略折行，不做截断；
 * - 行数超过 [maxLines] 时，最后一行末尾显示省略号表示仍有未显示的段；
 * - 同一行内各段按首行基线对齐；分隔符与省略号不参与无障碍朗读。
 */
@Composable
fun InfinityMetaLine(
    segments: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val visible = segments.filter(String::isNotBlank)
    if (visible.isEmpty()) return
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Layout(
        modifier = modifier,
        content = {
            visible.forEach { segment -> Text(text = segment, style = style, color = textColor) }
            repeat(visible.size - 1) {
                Text(
                    text = " $InfinityMetaSeparator ",
                    style = style,
                    color = textColor,
                    softWrap = false,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text = " …",
                style = style,
                color = textColor,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics { },
            )
        },
    ) { measurables, constraints ->
        val count = visible.size
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val segmentMeasurables = measurables.take(count)
        val separators = measurables.subList(count, count * 2 - 1).map { it.measure(Constraints()) }
        val ellipsis = measurables.last().measure(Constraints())
        // 先取不折行时的自然宽度：放得下就单行排布，放不下则独占一行折行。
        val naturalWidths = segmentMeasurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val segmentPlaceables = segmentMeasurables.mapIndexed { index, measurable ->
            val natural = naturalWidths[index]
            measurable.measure(
                if (natural <= maxWidth) Constraints.fixedWidth(natural) else Constraints(maxWidth = maxWidth),
            )
        }

        data class Slot(val segment: Int, val x: Int, val separatorX: Int?)
        val rows = mutableListOf<MutableList<Slot>>()
        var x = 0
        segmentPlaceables.forEachIndexed { index, placeable ->
            val separatorWidth = if (index > 0) separators[index - 1].width else 0
            val current = rows.lastOrNull()
            val fitsCurrent = current != null &&
                naturalWidths[index] <= maxWidth &&
                current.none { naturalWidths[it.segment] > maxWidth } &&
                x + separatorWidth + placeable.width <= maxWidth
            if (fitsCurrent) {
                current!! += Slot(index, x + separatorWidth, x)
                x += separatorWidth + placeable.width
            } else {
                rows += mutableListOf(Slot(index, 0, null))
                x = placeable.width
            }
        }

        val shown = rows.take(maxLines.coerceAtLeast(1))
        val truncated = rows.size > shown.size
        fun rowEnd(row: List<Slot>): Int = row.last().let { it.x + segmentPlaceables[it.segment].width }
        val ellipsisX = if (truncated) {
            minOf(rowEnd(shown.last()), (maxWidth - ellipsis.width).coerceAtLeast(0))
        } else {
            null
        }

        fun baselineOf(placeable: Placeable): Int =
            placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        val rowMetrics = shown.mapIndexed { rowIndex, row ->
            val members = row.flatMap { slot ->
                listOfNotNull(segmentPlaceables[slot.segment], slot.separatorX?.let { separators[slot.segment - 1] })
            } + if (ellipsisX != null && rowIndex == shown.lastIndex) listOf(ellipsis) else emptyList()
            val baseline = members.maxOf(::baselineOf)
            val descent = members.maxOf { it.height - baselineOf(it) }
            baseline to baseline + descent
        }
        val contentWidth = shown.maxOf(::rowEnd).let { end ->
            if (ellipsisX != null) maxOf(end, ellipsisX + ellipsis.width) else end
        }
        val width = minOf(contentWidth, maxWidth).coerceAtLeast(constraints.minWidth)
        // 与 Text 一致：高度不超过父级约束，超出部分向下溢出由父级裁切，而不是居中后盖住上方内容。
        val height = rowMetrics.sumOf { it.second }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            shown.forEachIndexed { rowIndex, row ->
                val (baseline, rowHeight) = rowMetrics[rowIndex]
                row.forEach { slot ->
                    val segment = segmentPlaceables[slot.segment]
                    segment.placeRelative(slot.x, y + baseline - baselineOf(segment))
                    slot.separatorX?.let { separatorX ->
                        val separator = separators[slot.segment - 1]
                        separator.placeRelative(separatorX, y + baseline - baselineOf(separator))
                    }
                }
                if (ellipsisX != null && rowIndex == shown.lastIndex) {
                    ellipsis.placeRelative(ellipsisX, y + baseline - baselineOf(ellipsis))
                }
                y += rowHeight
            }
        }
    }
}

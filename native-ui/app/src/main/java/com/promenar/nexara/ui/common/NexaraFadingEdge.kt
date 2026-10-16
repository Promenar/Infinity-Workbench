package com.promenar.nexara.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** DstIn 遮罩只取 alpha，颜色分量无意义；不是主题色。 */
private val OpaqueMask = Color.Transparent.copy(alpha = 1f)

/**
 * 滚动容器的上下渐隐边：内容可以继续向某个方向滚动时，该侧边缘淡出，代替生硬的平直裁切。
 * 由调用方按滚动状态传入 [top] / [bottom]，例如 `state.canScrollBackward`。
 */
fun Modifier.verticalFadingEdges(
    top: Boolean,
    bottom: Boolean = false,
    length: Dp = 24.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = length.toPx().coerceAtMost(size.height / 2f)
        if (top && edge > 0f) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, OpaqueMask), startY = 0f, endY = edge),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom && edge > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(OpaqueMask, Color.Transparent),
                    startY = size.height - edge,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - edge),
                size = Size(size.width, edge),
                blendMode = BlendMode.DstIn,
            )
        }
    }

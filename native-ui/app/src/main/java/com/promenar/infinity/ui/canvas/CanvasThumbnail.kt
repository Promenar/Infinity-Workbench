package com.promenar.infinity.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 缩略图的排版宽度：画布按手机宽度排版后整体缩小，卡片多窄都不会挤乱网页布局。 */
internal val CanvasThumbnailVirtualWidth = 400.dp

/**
 * 按固定虚拟宽度测量内容，再等比缩小到外框大小绘制。外框尺寸由 [modifier] 决定，
 * 虚拟高度随外框宽高比换算，内容超出外框的部分裁掉。
 */
@Composable
internal fun ScaledCanvasThumbnail(
    modifier: Modifier = Modifier,
    virtualWidth: Dp = CanvasThumbnailVirtualWidth,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val frameWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else virtualWidth.roundToPx()
        val frameHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else frameWidth * 10 / 16
        val scale = frameWidth / virtualWidth.toPx()
        val virtualHeight = (frameHeight / scale).roundToInt().coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints.fixed(virtualWidth.roundToPx(), virtualHeight)) }
        layout(frameWidth, frameHeight) {
            placeables.forEach { placeable ->
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

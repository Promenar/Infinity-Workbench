package com.promenar.nexara.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * 页签与主导航的选中底色：主色淡染叠在所在容器上。
 * 深色底叠 28%、浅色底叠 16%，与选中文字用的主色一起，靠底色明度与色相两重提示选中。
 */
fun ColorScheme.selectionIndicator(container: Color = surfaceContainer): Color =
    primary.copy(alpha = if (container.luminance() < 0.5f) 0.28f else 0.16f).compositeOver(container)

/** 选中底色上的文字与图标色。 */
val ColorScheme.onSelectionIndicator: Color get() = primary

package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.chat.LedEmptyChat
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.EmptyChatStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 外观页“生成动效”分组：输入框流光下方是浮岛材质分段控件与静态预览，示例正文垫在浮岛后面。
// 静态预览不录制图层，毛玻璃只显示半透明色与亮边，模糊效果以真机为准。
@PreviewTest
@Preview(name = "浮岛材质 毛玻璃 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun composerSurfaceFrostedDarkPreview() = MotionSection(ComposerSurfaceStyle.FROSTED, InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "浮岛材质 毛玻璃 浅英360 大字号", widthDp = 360, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun composerSurfaceFrostedLightPreview() = MotionSection(ComposerSurfaceStyle.FROSTED, InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "浮岛材质 环境光 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun composerSurfaceAmbientDarkPreview() = MotionSection(ComposerSurfaceStyle.AMBIENT, InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "浮岛材质 环境光 浅中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
fun composerSurfaceAmbientLightPreview() = MotionSection(ComposerSurfaceStyle.AMBIENT, InfinityThemeMode.LIGHT)

// 空会话“灯珠变形”：静态预览与系统关闭动画时一样，只显示灯珠字标。
@PreviewTest
@Preview(name = "灯珠变形 静态字标 深中411", widthDp = 411, heightDp = 600, locale = "zh-rCN")
@Composable
fun ledMorphStaticDarkPreview() = LedMorphStatic(InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "灯珠变形 静态字标 浅英360", widthDp = 360, heightDp = 600, locale = "en")
@Composable
fun ledMorphStaticLightPreview() = LedMorphStatic(InfinityThemeMode.LIGHT)

@Composable
private fun MotionSection(surface: ComposerSurfaceStyle, mode: InfinityThemeMode) {
    val motion = InfinityMotionStyles(surface = surface)
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY, motion = motion)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column {
                MotionStyleRows(motion = motion, onComposerSelect = {}, onIndicatorSelect = {}, onEmptyChatSelect = {})
            }
        }
    }
}

@Composable
private fun LedMorphStatic(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LedEmptyChat(style = EmptyChatStyle.LED_MORPH, dispersed = false, modifier = Modifier.fillMaxSize())
        }
    }
}

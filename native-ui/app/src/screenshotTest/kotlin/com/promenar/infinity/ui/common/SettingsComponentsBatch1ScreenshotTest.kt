package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.*
import com.promenar.infinity.ui.settings.ToolLoopLimitSpec
import com.promenar.infinity.ui.settings.ToolTokenLimitSpec
import com.promenar.infinity.R
import androidx.compose.ui.res.stringResource

@PreviewTest
@Preview(name = "设置组件 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "设置组件 深中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Preview(name = "设置组件 深中130", widthDp = 411, heightDp = 850, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun settingsComponentsBatch1Preview() = ComponentTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        InfinitySettingsSection("设置行", footer = "详细说明在分组下方，保持行内控件与文字块垂直居中。") {
            InfinitySettingsItem(title = "单行设置", onClick = {}, showChevron = true)
            InfinitySettingsItem(title = "双行设置", subtitle = "当前状态", onClick = {}, showChevron = true)
            InfinitySettingsItem(title = "禁用设置", subtitle = "暂不可用", enabled = false, onClick = {})
            SettingsToggle(title = "开关设置", checked = true, onCheckedChange = {})
            InfinityValueRow(title = "数值设置", value = "80%", onClick = {})
            InfinityStepperSetting(title = "中间值", value = "5", onDecrease = {}, onIncrease = {})
            InfinityStepperSetting(title = "最小值", value = "1", canDecrease = false, onDecrease = {}, onIncrease = {})
            InfinityStepperSetting(title = "最大值", value = "20", canIncrease = false, onDecrease = {}, onIncrease = {})
        }
    }
}

@PreviewTest
@Preview(name = "离散弹层 深中411", widthDp = 411, heightDp = 500, locale = "zh-rCN")
@Preview(name = "离散弹层 深中360", widthDp = 360, heightDp = 500, locale = "zh-rCN")
@Composable
fun discretePickerBatch1Preview() = ComponentTheme { PickerFrame { TurnPicker() } }

@PreviewTest
@Preview(name = "离散自定义 深中411", widthDp = 411, heightDp = 700, locale = "zh-rCN")
@Preview(name = "离散自定义 深中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun customPickerBatch1Preview() = ComponentTheme { PickerFrame { TurnPicker(custom = true) } }

@PreviewTest
@Preview(name = "离散错误 深中411", widthDp = 411, heightDp = 700, locale = "zh-rCN")
@Preview(name = "离散错误 深中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun invalidPickerBatch1Preview() = ComponentTheme { PickerFrame { TurnPicker(custom = true, input = "101") } }

@PreviewTest
@Preview(name = "连续弹层 深中411", widthDp = 411, heightDp = 350, locale = "zh-rCN")
@Preview(name = "连续弹层 深中360", widthDp = 360, heightDp = 350, locale = "zh-rCN")
@Composable
fun continuousPickerBatch1Preview() = ComponentTheme {
    PickerFrame {
        InfinityValuePickerContent("Top P", 1.0, NumericSettingSpec(0.0, 1.0, step = 0.0, defaultValue = 1.0,
            presets = listOf(0.5, 0.8, 0.95, 1.0).map { NumericSettingSpec.Preset(it) },
            kind = NumericSettingKind.Continuous, decimalPlaces = 2), {}, {})
    }
}

@PreviewTest
@Preview(name = "Token上限弹层 深中411", widthDp = 411, heightDp = 500, locale = "zh-rCN")
@Preview(name = "Token上限弹层 深中360", widthDp = 360, heightDp = 500, locale = "zh-rCN")
@Composable
fun tokenPickerBatch1Preview() = ComponentTheme {
    PickerFrame { InfinityValuePickerContent(stringResource(R.string.p0_token_limit), 0.0, ToolTokenLimitSpec, {}, {},
        description = stringResource(R.string.p0_runtime_limits_desc)) }
}

@Composable
private fun TurnPicker(custom: Boolean = false, input: String? = null) {
    InfinityValuePickerContent(stringResource(R.string.skills_loop_limit), 50.0,
        ToolLoopLimitSpec, {}, {},
        description = stringResource(R.string.skills_loop_limit_desc),
        initialCustomExpanded = custom, initialCustomInput = input)
}

@PreviewTest
@Preview(name = "窄屏长标题 English", widthDp = 220, heightDp = 240, locale = "en")
@Preview(name = "窄屏长标题 English 2x", widthDp = 220, heightDp = 480, locale = "en", fontScale = 2f)
@Composable
fun narrowSettingsTitleBatch1Preview() = ComponentTheme {
    InfinitySettingsItem(
        icon = Icons.Rounded.Info,
        title = "Very long settings title that must wrap naturally on a narrow phone layout",
        onClick = {},
    )
}

@Composable
private fun PickerFrame(content: @Composable () -> Unit) = Column(Modifier.fillMaxSize().padding(24.dp)) { content() }

@Composable
private fun ComponentTheme(content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    }
}

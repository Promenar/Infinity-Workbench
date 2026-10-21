package com.promenar.nexara.ui.common

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
import com.promenar.nexara.ui.theme.*
import com.promenar.nexara.ui.settings.ToolLoopLimitSpec
import com.promenar.nexara.ui.settings.ToolTokenLimitSpec
import com.promenar.nexara.R
import com.promenar.nexara.data.model.params.SamplingParam
import com.promenar.nexara.ui.common.params.samplingParamSpec
import androidx.compose.ui.res.stringResource

@PreviewTest
@Preview(name = "设置组件 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "设置组件 深中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Preview(name = "设置组件 深中130", widthDp = 411, heightDp = 850, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun settingsComponentsBatch1Preview() = ComponentTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        NexaraSettingsSection("设置行", footer = "详细说明在分组下方，保持行内控件与文字块垂直居中。") {
            NexaraSettingsItem(title = "单行设置", onClick = {}, showChevron = true)
            NexaraSettingsItem(title = "双行设置", subtitle = "当前状态", onClick = {}, showChevron = true)
            NexaraSettingsItem(title = "禁用设置", subtitle = "暂不可用", enabled = false, onClick = {})
            SettingsToggle(title = "开关设置", checked = true, onCheckedChange = {})
            NexaraValueRow(title = "数值设置", value = "80%", onClick = {})
            NexaraStepperSetting(title = "中间值", value = "5", onDecrease = {}, onIncrease = {})
            NexaraStepperSetting(title = "最小值", value = "1", canDecrease = false, onDecrease = {}, onIncrease = {})
            NexaraStepperSetting(title = "最大值", value = "20", canIncrease = false, onDecrease = {}, onIncrease = {})
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
        NexaraValuePickerContent("Top P", 1.0, NumericSettingSpec(0.0, 1.0, step = 0.0, defaultValue = 1.0,
            presets = listOf(0.5, 0.8, 0.95, 1.0).map { NumericSettingSpec.Preset(it) },
            kind = NumericSettingKind.Continuous, decimalPlaces = 2), {}, {})
    }
}

@PreviewTest
@Preview(name = "Token上限弹层 深中411", widthDp = 411, heightDp = 500, locale = "zh-rCN")
@Preview(name = "Token上限弹层 深中360", widthDp = 360, heightDp = 500, locale = "zh-rCN")
@Composable
fun tokenPickerBatch1Preview() = ComponentTheme {
    PickerFrame { NexaraValuePickerContent(stringResource(R.string.p0_token_limit), 0.0, ToolTokenLimitSpec, {}, {},
        description = stringResource(R.string.p0_runtime_limits_desc)) }
}

@Composable
private fun TurnPicker(custom: Boolean = false, input: String? = null) {
    NexaraValuePickerContent(stringResource(R.string.skills_loop_limit), 50.0,
        ToolLoopLimitSpec, {}, {},
        description = stringResource(R.string.skills_loop_limit_desc),
        initialCustomExpanded = custom, initialCustomInput = input)
}

@PreviewTest
@Preview(name = "窄屏长标题 English", widthDp = 220, heightDp = 240, locale = "en")
@Preview(name = "窄屏长标题 English 2x", widthDp = 220, heightDp = 480, locale = "en", fontScale = 2f)
@Composable
fun narrowSettingsTitleBatch1Preview() = ComponentTheme {
    NexaraSettingsItem(
        icon = Icons.Rounded.Info,
        title = "Very long settings title that must wrap naturally on a narrow phone layout",
        onClick = {},
    )
}

@Composable
private fun PickerFrame(content: @Composable () -> Unit) = Column(Modifier.fillMaxSize().padding(24.dp)) { content() }

@Composable
private fun ComponentTheme(content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = NexaraThemeMode.DARK, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    }
}

@PreviewTest
@Preview(name = "未设置连续弹层 深中411", widthDp = 411, heightDp = 350, locale = "zh-rCN")
@Preview(name = "未设置连续弹层 深英360", widthDp = 360, heightDp = 350, locale = "en")
@Composable
fun unsetContinuousPickerBatch1Preview() = ComponentTheme {
    PickerFrame {
        NexaraValuePickerContent(stringResource(R.string.params_temperature), 1.0, samplingParamSpec(SamplingParam.TEMPERATURE), {}, {},
            restoreDefault = PickerRestoreAction(stringResource(R.string.params_use_model_default),
                activeLabel = stringResource(R.string.params_value_default)) {})
    }
}

@PreviewTest
@Preview(name = "未设置离散弹层 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Preview(name = "未设置离散弹层 深英360", widthDp = 360, heightDp = 560, locale = "en")
@Composable
fun unsetDiscretePickerBatch1Preview() = ComponentTheme {
    PickerFrame {
        NexaraValuePickerContent("Top K", 40.0, samplingParamSpec(SamplingParam.TOP_K), {}, {},
            restoreDefault = PickerRestoreAction(stringResource(R.string.params_use_model_default),
                activeLabel = stringResource(R.string.params_value_default)) {})
    }
}

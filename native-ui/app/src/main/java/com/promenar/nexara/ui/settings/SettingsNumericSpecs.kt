package com.promenar.nexara.ui.settings

import com.promenar.nexara.R
import com.promenar.nexara.data.remote.ModelTimeoutKind
import com.promenar.nexara.data.remote.ModelTimeoutSettings
import com.promenar.nexara.ui.common.NumericSettingSpec

/** 数值入口与弹层预览共用的取值契约，偏好读写由页面回调承担。 */
internal val ToolLoopLimitSpec = NumericSettingSpec(
    min = 1.0,
    max = 100.0,
    presets = listOf(
        NumericSettingSpec.Preset(10.0),
        NumericSettingSpec.Preset(25.0),
        NumericSettingSpec.Preset(50.0, R.string.settings_batch1_rounds_50),
        NumericSettingSpec.Preset(100.0),
        NumericSettingSpec.Preset(0.0, R.string.p0_unlimited),
    ),
    defaultValue = 50.0,
    unlimitedValue = 0.0,
)

internal val ToolTokenLimitSpec = NumericSettingSpec(
    min = 0.0,
    max = 10_000_000.0,
    step = 10_000.0,
    presets = listOf(
        NumericSettingSpec.Preset(0.0, R.string.settings_batch1_disabled),
        NumericSettingSpec.Preset(100_000.0, R.string.settings_batch1_tokens_100k),
        NumericSettingSpec.Preset(500_000.0, R.string.settings_batch1_tokens_500k),
        NumericSettingSpec.Preset(1_000_000.0, R.string.settings_batch1_tokens_1m),
        NumericSettingSpec.Preset(5_000_000.0, R.string.settings_batch1_tokens_5m),
    ),
    defaultValue = 0.0,
    unlimitedValue = 0.0,
)

internal val InterfaceTextScaleSpec = NumericSettingSpec(
    min = 80.0,
    max = 140.0,
    step = 10.0,
    presets = (80..140 step 10).map { percent ->
        NumericSettingSpec.Preset(
            percent.toDouble(),
            if (percent == 100) R.string.settings_batch1_scale_100 else null,
        )
    },
    defaultValue = 100.0,
    unit = "%",
)

/** 等待响应超时（秒）：对话与生图按分钟级预设，向量与重排按十秒级预设；默认值与 [ModelTimeoutSettings] 一致。 */
internal fun modelTimeoutSpec(kind: ModelTimeoutKind, unit: String): NumericSettingSpec {
    val presets = when (kind) {
        ModelTimeoutKind.LLM, ModelTimeoutKind.IMAGE -> listOf(60, 120, 300, 600)
        ModelTimeoutKind.EMBEDDING, ModelTimeoutKind.RERANK -> listOf(15, 30, 60, 120)
    }
    return NumericSettingSpec(
        min = ModelTimeoutSettings.MIN_SECONDS.toDouble(),
        max = ModelTimeoutSettings.MAX_SECONDS.toDouble(),
        presets = presets.map { NumericSettingSpec.Preset(it.toDouble()) },
        defaultValue = ModelTimeoutSettings().seconds(kind).toDouble(),
        unit = unit,
    )
}

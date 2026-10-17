package com.promenar.nexara.ui.settings

import com.promenar.nexara.R
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

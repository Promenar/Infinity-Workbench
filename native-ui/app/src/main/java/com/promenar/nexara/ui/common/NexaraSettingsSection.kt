package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing

/**
 * 连续设置列表的分组容器。
 *
 * 标题、内容和分隔线共用一个透明 Column，避免将每个设置项包装为独立卡片。
 */
@Composable
fun NexaraSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    headerStartPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = title,
            titleStyle = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                start = headerStartPadding,
                top = NexaraSpacing.Small,
            ),
        )
        content()
        HorizontalDivider(
            modifier = Modifier
                .padding(top = NexaraSpacing.XSmall)
                .testTag("nexara_settings_section_divider"),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        )
    }
}

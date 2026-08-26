package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing

/** Bettbox 风格设置分组：弱化标题，设置行收进统一圆角色面。 */
@Composable
fun NexaraSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    headerStartPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val groupHorizontalPadding = headerStartPadding
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = NexaraSpacing.Small),
    ) {
        SettingsSectionHeader(
            title = title,
            titleStyle = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                start = groupHorizontalPadding + NexaraSpacing.Small,
                end = groupHorizontalPadding + NexaraSpacing.Small,
                bottom = NexaraSpacing.Small,
            ),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = groupHorizontalPadding),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(content = content)
        }
    }
}

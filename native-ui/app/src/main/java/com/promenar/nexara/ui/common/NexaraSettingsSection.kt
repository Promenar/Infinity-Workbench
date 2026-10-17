package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forReading

/** Bettbox 风格设置分组：弱化标题，设置行收进统一圆角色面。 */
@Composable
fun NexaraSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    headerStartPadding: Dp = 0.dp,
    footer: String? = null,
    footerModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val groupHorizontalPadding = headerStartPadding
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = title,
            titleStyle = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                start = groupHorizontalPadding + NexaraSpacing.RowHorizontal,
                end = groupHorizontalPadding + NexaraSpacing.RowHorizontal,
                bottom = NexaraSpacing.SectionHeaderBottom,
            ),
        )
        NexaraSettingsGroup(
            footer = footer,
            footerModifier = footerModifier,
            modifier = Modifier.padding(horizontal = groupHorizontalPadding),
            content = content,
        )
    }
}

/** 不带重复标题的设置分组，列表中的说明紧邻所属项目。 */
@Composable
fun NexaraSettingsGroup(
    modifier: Modifier = Modifier,
    footer: String? = null,
    footerModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = NexaraSpacing.SectionGap)) {
        BettboxListGroup(modifier = Modifier.fillMaxWidth(), content = content)
        if (!footer.isNullOrBlank()) {
            SelectionContainer {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = footerModifier.padding(
                        horizontal = NexaraSpacing.RowHorizontal,
                    ).padding(top = NexaraSpacing.FooterTop),
                )
            }
        }
    }
}

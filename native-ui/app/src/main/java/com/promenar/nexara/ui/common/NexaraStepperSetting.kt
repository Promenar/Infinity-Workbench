package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraSpacing

/** 设置页数值步进器：完整标签与说明保留独立空间，数值与操作垂直对齐。 */
@Composable
fun NexaraStepperSetting(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = NexaraSpacing.Large,
            vertical = NexaraSpacing.Medium,
        ),
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = NexaraSpacing.XSmall).size(NexaraSpacing.XLarge),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Row {
                IconButton(
                    onClick = onDecrease,
                    enabled = enabled && canDecrease,
                    modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget),
                ) {
                    Icon(Icons.Rounded.Remove, stringResource(R.string.p0_decrease_setting, title))
                }
                IconButton(
                    onClick = onIncrease,
                    enabled = enabled && canIncrease,
                    modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget),
                ) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.p0_increase_setting, title))
                }
            }
        }
    }
}

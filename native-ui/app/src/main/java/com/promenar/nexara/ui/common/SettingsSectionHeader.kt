package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.promenar.nexara.ui.theme.NexaraSpacing

@Composable
fun SettingsSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.Large + NexaraSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = (titleStyle ?: MaterialTheme.typography.labelLarge).copy(
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(modifier = Modifier.weight(1f))

        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.defaultMinSize(
                    minHeight = NexaraSpacing.MinimumTouchTarget,
                ),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

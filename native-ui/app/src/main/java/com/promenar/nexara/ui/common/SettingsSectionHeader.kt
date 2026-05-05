package com.promenar.nexara.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.Manrope
import com.promenar.nexara.ui.theme.NexaraColors

@Composable
fun SettingsSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            fontFamily = Manrope,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.sp,
            color = NexaraColors.Outline
        )

        Spacer(modifier = Modifier.weight(1f))

        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                fontFamily = Manrope,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = NexaraColors.Primary,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

package com.promenar.nexara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraCollapsibleSection
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun TokenUsageScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val providers by viewModel.tokenStats.collectAsState()

    val totalTokens = providers.sumOf { it.totalTokens }
    val totalCost = providers.sumOf { it.cost }

    NexaraPageLayout(
        title = stringResource(R.string.token_title),
        onBack = onNavigateBack
    ) {
        Text(
            text = stringResource(R.string.token_desc),
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaraShapes.large as RoundedCornerShape
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NexaraShapes.large)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                NexaraColors.Primary.copy(alpha = 0.15f),
                                NexaraColors.PrimaryContainer.copy(alpha = 0.08f)
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.token_total_usage),
                        style = NexaraTypography.labelMedium.copy(
                            fontSize = 10.sp,
                            letterSpacing = 0.15.sp
                        ),
                        color = NexaraColors.OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatTokenCount(totalTokens),
                        style = NexaraTypography.headlineLarge.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaraColors.Primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.token_tokens),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(NexaraColors.Primary.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$${String.format("%.2f", totalCost)}",
                            style = NexaraTypography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = NexaraColors.Primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        providers.forEach { provider ->
            NexaraCollapsibleSection(
                title = "${provider.name} — ${formatTokenCount(provider.totalTokens)} tokens · $${String.format("%.2f", provider.cost)}",
                initiallyExpanded = false
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    provider.models.forEach { model ->
                        ModelUsageRow(model)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NexaraShapes.medium)
                .background(NexaraColors.Error.copy(alpha = 0.1f))
                .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), NexaraShapes.medium)
                .clickable { viewModel.clearTokenStats() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.token_clear_history),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.Error
            )
        }
    }
}

@Composable
private fun ModelUsageRow(model: ModelStat) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.medium as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.token_in_out, formatTokenCount(model.inputTokens), formatTokenCount(model.outputTokens)),
                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Text(
                text = "$${String.format("%.2f", model.cost)}",
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant
            )
        }
    }
}

private fun formatTokenCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "${(count / 100_000).toInt() / 10.0}M"
        count >= 1_000 -> "${(count / 100).toInt() / 10.0}K"
        else -> count.toString()
    }
}

package com.promenar.nexara.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.domain.repository.DailyTokenStats
import com.promenar.nexara.domain.repository.SessionTokenUsage
import com.promenar.nexara.ui.common.ConfirmDialog
import com.promenar.nexara.ui.common.NexaraCollapsibleSection
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader

@Composable
fun TokenUsageScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: TokenUsageViewModel = viewModel(factory = TokenUsageViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.state.collectAsState()

    ConfirmDialog(
        show = state.showClearConfirm,
        onDismiss = { viewModel.dismissClearConfirm() },
        onConfirm = { viewModel.clearStats() },
        title = stringResource(R.string.token_clear_confirm_title),
        description = stringResource(R.string.token_clear_confirm_desc),
        confirmLabel = stringResource(R.string.token_clear_history),
        destructive = true
    )

    NexaraSettingsPageLayout(
        title = stringResource(R.string.token_title),
        onBack = onNavigateBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
        Text(
            text = stringResource(R.string.token_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
        } else {
            GlobalStatsCard(state)

            Spacer(modifier = Modifier.height(20.dp))

            if (state.topSessions.isNotEmpty()) {
                SettingsSectionHeader(stringResource(R.string.token_top_sessions))
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.topSessions.forEachIndexed { index, session ->
                        SessionRankingRow(index + 1, session)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (state.dailyTrend.isNotEmpty()) {
                SettingsSectionHeader(stringResource(R.string.token_7day_trend))
                TrendChart(state.dailyTrend)
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (state.modelBreakdown.isNotEmpty()) {
                state.modelBreakdown.forEach { model ->
                    NexaraCollapsibleSection(
                        title = buildString {
                            append(model.name)
                            append(" \u2014 ")
                            append(formatTokenCount(model.inputTokens + model.outputTokens))
                            append(" ")
                            append(stringResource(R.string.token_tokens))
                        },
                        initiallyExpanded = state.modelBreakdown.size <= 3
                    ) {
                        ModelBreakdownRow(model)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (state.globalInput == 0L && state.globalOutput == 0L) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.token_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!state.isLoading) {
            OutlinedButton(
                onClick = { viewModel.showClearConfirm() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Text(
                    text = stringResource(R.string.token_clear_history),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        }
    }
}

@Composable
private fun GlobalStatsCard(state: TokenStatsState) {
    val totalTokens = state.globalInput + state.globalOutput

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.token_total_usage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (state.globalHasEstimated) {
                    Text(
                        text = "≈ ",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = formatTokenCount(totalTokens),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.token_tokens),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.token_in_short, formatTokenCount(state.globalInput)),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Text(
                    text = stringResource(R.string.token_out_short, formatTokenCount(state.globalOutput)),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (state.globalCostUSD > 0.0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.token_est_cost, state.globalCostUSD),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun SessionRankingRow(rank: Int, session: SessionTokenUsage) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (rank) {
                            1 -> MaterialTheme.colorScheme.tertiaryContainer
                            2 -> MaterialTheme.colorScheme.secondaryContainer
                            3 -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = when (rank) {
                        1 -> MaterialTheme.colorScheme.onTertiaryContainer
                        2 -> MaterialTheme.colorScheme.onSecondaryContainer
                        3 -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        headlineContent = {
            Text(
                text = session.title ?: session.sessionId.take(8),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        },
        supportingContent = {
            Text(
                text = stringResource(
                    R.string.token_in_out,
                    formatTokenCount(session.inputTokens),
                    formatTokenCount(session.outputTokens)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Text(
                text = formatTokenCount(session.totalTokens),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}

@Composable
private fun TrendChart(dailyTrend: List<DailyTokenStats>) {
    if (dailyTrend.isEmpty()) return

    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(16.dp)
            ) {
                val maxTokens = dailyTrend.maxOf { it.totalTokens }.coerceAtLeast(1)
                val chartWidth = size.width
                val chartHeight = size.height - 24.dp.toPx()

                val stepX = if (dailyTrend.size > 1) chartWidth / (dailyTrend.size - 1) else chartWidth

                for (i in 1..4) {
                    val y = chartHeight - (chartHeight * i / 4f)
                    drawLine(
                        color = outlineVariantColor.copy(alpha = 0.3f),
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1f
                    )
                }

                val inputPath = Path()
                val outputPath = Path()
                dailyTrend.forEachIndexed { index, stats ->
                    val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                    val inputY = chartHeight - (stats.inputTokens.toFloat() / maxTokens * chartHeight)
                    val outputY = chartHeight - (stats.outputTokens.toFloat() / maxTokens * chartHeight)
                    if (index == 0) {
                        inputPath.moveTo(x, inputY)
                        outputPath.moveTo(x, outputY)
                    } else {
                        inputPath.lineTo(x, inputY)
                        outputPath.lineTo(x, outputY)
                    }
                }

                drawPath(
                    path = outputPath,
                    color = tertiaryColor.copy(alpha = 0.6f),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawPath(
                    path = inputPath,
                    color = primaryColor,
                    style = Stroke(width = 2.dp.toPx())
                )

                dailyTrend.forEachIndexed { index, stats ->
                    val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                    val inputY = chartHeight - (stats.inputTokens.toFloat() / maxTokens * chartHeight)
                    drawCircle(
                        color = primaryColor,
                        radius = 3.dp.toPx(),
                        center = Offset(x, inputY)
                    )
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = onSurfaceVariantColor.copy(alpha = 0.5f).value.toLong().toInt()
                        textSize = 10.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    dailyTrend.forEachIndexed { index, stats ->
                        val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                        drawText(
                            stats.day.takeLast(5),
                            x,
                            size.height,
                            paint
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.token_input_label),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = onSurfaceVariantColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tertiaryColor.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.token_output_label),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = onSurfaceVariantColor
                )
            }
        }
    }
}

@Composable
private fun ModelBreakdownRow(model: ModelCostInfo) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(R.string.token_in_out, formatTokenCount(model.inputTokens), formatTokenCount(model.outputTokens)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.pricingAvailable && model.costUSD > 0.0) {
                    Text(
                        text = "$%.4f".format(model.costUSD),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (!model.pricingAvailable) {
                    Text(
                        text = stringResource(R.string.token_pricing_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        },
        trailingContent = {
            Text(
                text = formatTokenCount(model.inputTokens + model.outputTokens),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}

private fun formatTokenCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}

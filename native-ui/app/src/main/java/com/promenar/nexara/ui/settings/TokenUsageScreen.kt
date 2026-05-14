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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    val viewModel: TokenUsageViewModel = viewModel(factory = TokenUsageViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.state.collectAsState()

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

        GlobalStatsCard(state)

        Spacer(modifier = Modifier.height(20.dp))

        if (state.topSessions.isNotEmpty()) {
            Text(
                text = "Top Sessions",
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            state.topSessions.forEachIndexed { index, session ->
                SessionRankingRow(index + 1, session)
                Spacer(modifier = Modifier.height(6.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (state.dailyTrend.isNotEmpty()) {
            Text(
                text = "7-Day Trend",
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                        append(" tokens")
                    },
                    initiallyExpanded = state.modelBreakdown.size <= 3
                ) {
                    ModelBreakdownRow(model)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (state.globalInput == 0L && state.globalOutput == 0L) {
            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.medium as RoundedCornerShape
            ) {
                Text(
                    text = stringResource(R.string.token_no_data),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NexaraShapes.medium)
                .background(NexaraColors.Error.copy(alpha = 0.1f))
                .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), NexaraShapes.medium)
                .clickable { viewModel.clearStats() }
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
private fun GlobalStatsCard(state: TokenStatsState) {
    val totalTokens = state.globalInput + state.globalOutput

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
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (state.globalHasEstimated) "\u2248 " else "",
                        style = NexaraTypography.headlineLarge.copy(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaraColors.Primary.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatTokenCount(totalTokens),
                        style = NexaraTypography.headlineLarge.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaraColors.Primary
                    )
                }
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
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "In: ${formatTokenCount(state.globalInput)}",
                        style = NexaraTypography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NexaraColors.Primary
                    )
                    Text(
                        text = "\u00B7",
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.Primary.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Out: ${formatTokenCount(state.globalOutput)}",
                        style = NexaraTypography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NexaraColors.Primary
                    )
                }

                if (state.globalCostUSD > 0.0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Est. Cost: $%.4f".format(state.globalCostUSD),
                        style = NexaraTypography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NexaraColors.Tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRankingRow(rank: Int, session: SessionTokenUsage) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.medium as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (rank) {
                            1 -> NexaraColors.Tertiary.copy(alpha = 0.2f)
                            2 -> NexaraColors.Secondary.copy(alpha = 0.2f)
                            3 -> NexaraColors.Primary.copy(alpha = 0.15f)
                            else -> NexaraColors.SurfaceHigh
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    style = NexaraTypography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = when (rank) {
                        1 -> NexaraColors.Tertiary
                        2 -> NexaraColors.Secondary
                        3 -> NexaraColors.Primary
                        else -> NexaraColors.OnSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: session.sessionId.take(8),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurface,
                    maxLines = 1
                )
                Text(
                    text = "In: ${formatTokenCount(session.inputTokens)} \u00B7 Out: ${formatTokenCount(session.outputTokens)}",
                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Text(
                text = formatTokenCount(session.totalTokens),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = NexaraColors.Primary
            )
        }
    }
}

@Composable
private fun TrendChart(dailyTrend: List<DailyTokenStats>) {
    if (dailyTrend.isEmpty()) return

    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
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
                    color = NexaraColors.OutlineVariant.copy(alpha = 0.3f),
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
                color = NexaraColors.Tertiary.copy(alpha = 0.6f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawPath(
                path = inputPath,
                color = NexaraColors.Primary,
                style = Stroke(width = 2.dp.toPx())
            )

            dailyTrend.forEachIndexed { index, stats ->
                val x = if (dailyTrend.size > 1) index * stepX else chartWidth / 2f
                val inputY = chartHeight - (stats.inputTokens.toFloat() / maxTokens * chartHeight)
                drawCircle(
                    color = NexaraColors.Primary,
                    radius = 3.dp.toPx(),
                    center = Offset(x, inputY)
                )
            }

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f).value.toLong().toInt()
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
                    .background(NexaraColors.Primary)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Input",
                style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                color = NexaraColors.OnSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.Tertiary.copy(alpha = 0.6f))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Output",
                style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                color = NexaraColors.OnSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelBreakdownRow(model: ModelCostInfo) {
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
                if (model.pricingAvailable && model.costUSD > 0.0) {
                    Text(
                        text = "$%.4f".format(model.costUSD),
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                        color = NexaraColors.Tertiary.copy(alpha = 0.8f)
                    )
                } else if (!model.pricingAvailable) {
                    Text(
                        text = "Pricing unavailable",
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                        color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
            Text(
                text = formatTokenCount(model.inputTokens + model.outputTokens),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = NexaraColors.Primary
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

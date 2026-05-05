package com.promenar.nexara.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.ExecutionMode
import com.promenar.nexara.ui.common.ExecutionModeSelector
import com.promenar.nexara.ui.common.ModelCapability
import com.promenar.nexara.ui.common.ModelItem
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch

private data class ThinkingLevelOption(
    val id: String,
    val icon: ImageVector,
    val tint: Color
)

private val thinkingLevels = listOf(
    ThinkingLevelOption("minimal", Icons.Rounded.Bolt, Color(0xFFA78BFA)),
    ThinkingLevelOption("low", Icons.Rounded.Psychology, Color(0xFF22D3EE)),
    ThinkingLevelOption("medium", Icons.Rounded.AutoFixHigh, Color(0xFFFBBF24)),
    ThinkingLevelOption("high", Icons.Rounded.School, Color(0xFF10B981))
)

@Composable
private fun thinkingLevelTitle(id: String): String = when (id) {
    "minimal" -> stringResource(R.string.sheet_thinking_minimal)
    "low" -> stringResource(R.string.sheet_thinking_low)
    "medium" -> stringResource(R.string.sheet_thinking_medium)
    "high" -> stringResource(R.string.sheet_thinking_high)
    else -> id
}

@Composable
private fun thinkingLevelDesc(id: String): String = when (id) {
    "minimal" -> stringResource(R.string.sheet_thinking_minimal_desc)
    "low" -> stringResource(R.string.sheet_thinking_low_desc)
    "medium" -> stringResource(R.string.sheet_thinking_medium_desc)
    "high" -> stringResource(R.string.sheet_thinking_high_desc)
    else -> ""
}

private val sampleModels = listOf(
    ModelItem("gpt-4o", "GPT-4o", "OpenAI", listOf(ModelCapability.VISION, ModelCapability.CHAT), 128000),
    ModelItem("claude-3.5-sonnet", "Claude 3.5 Sonnet", "Anthropic", listOf(ModelCapability.CHAT, ModelCapability.REASONING), 200000),
    ModelItem("deepseek-v3", "DeepSeek V3", "DeepSeek", listOf(ModelCapability.REASONING, ModelCapability.CHAT), 64000),
    ModelItem("gemini-3-flash", "Gemini 3 Flash", "Google", listOf(ModelCapability.VISION, ModelCapability.CHAT), 1000000)
)

private val capabilityColorMap: Map<ModelCapability, Pair<Color, Color>> = mapOf(
    ModelCapability.REASONING to (Color(0xFFA78BFA) to Color(0xFF1E1B4B)),
    ModelCapability.VISION to (Color(0xFFF472B6) to Color(0xFF4A1942)),
    ModelCapability.WEB to (Color(0xFF38BDF8) to Color(0xFF0C2D48)),
    ModelCapability.CHAT to (Color(0xFF34D399) to Color(0xFF022C22)),
    ModelCapability.RERANK to (Color(0xFFFB923C) to Color(0xFF431407)),
    ModelCapability.EMBEDDING to (Color(0xFF22D3EE) to Color(0xFF083344))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    sessionId: String
) {
    if (!show) return

    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * 0.7f).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabTitles = listOf(
        stringResource(R.string.sheet_tab_model),
        stringResource(R.string.sheet_tab_thinking),
        stringResource(R.string.sheet_tab_stats),
        stringResource(R.string.sheet_tab_tools)
    )

    LaunchedEffect(pagerState.currentPage) {}

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexaraColors.SurfaceContainer,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .background(NexaraColors.OutlineVariant, CircleShape)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sheet_title), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                    IconButton(onClick = onDismiss) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(NexaraColors.GlassSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Close, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                contentColor = NexaraColors.OnSurface,
                edgePadding = 24.dp,
                divider = {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = NexaraColors.GlassBorder
                    )
                },
                indicator = { tabPositions ->
                    val pos = tabPositions[pagerState.currentPage]
                    Box(
                        Modifier
                            .offset(x = pos.left)
                            .width(pos.width)
                            .height(2.dp)
                            .background(NexaraColors.Primary)
                    )
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                title,
                                style = NexaraTypography.labelMedium,
                                color = if (pagerState.currentPage == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                            )
                        },
                        selectedContentColor = NexaraColors.Primary,
                        unselectedContentColor = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> ModelPanel(onSelect = { onDismiss() })
                    1 -> ThinkingLevelPanel()
                    2 -> StatsPanel()
                    3 -> ToolsPanel()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPanel(onSelect: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp)
    ) {
        NexaraSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.common_model_picker_search)
        )

        Spacer(modifier = Modifier.height(12.dp))

        val filtered = if (searchQuery.isBlank()) sampleModels else sampleModels.filter {
            it.name.contains(searchQuery, true) || it.providerName.contains(searchQuery, true)
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.id }) { model ->
                val isSelected = model.id == "gpt-4o"
                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) Modifier.border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .clickable { onSelect() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) NexaraColors.Primary.copy(alpha = 0.1f)
                                    else NexaraColors.SurfaceHigh
                                )
                                .then(
                                    if (isSelected) Modifier.border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.2f), CircleShape)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Memory, null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Storage, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(10.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    "${model.providerName} • ${(model.contextLength ?: 0) / 1000}K Context",
                                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    color = NexaraColors.OnSurfaceVariant
                                )
                            }
                            if (model.capabilities.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    model.capabilities.forEach { cap ->
                                        val (fg, bg) = capabilityColorMap[cap]!!
                                        Box(
                                            modifier = Modifier
                                                .background(bg, RoundedCornerShape(50))
                                                .border(0.5.dp, fg.copy(alpha = 0.3f), RoundedCornerShape(50))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                cap.name.lowercase().replaceFirstChar { it.uppercase() },
                                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                                color = fg
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (isSelected) {
                            Icon(Icons.Rounded.Check, null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingLevelPanel() {
    var selectedLevel by remember { mutableStateOf("medium") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in listOf(thinkingLevels.take(2), thinkingLevels.drop(2))) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { level ->
                        val isSelected = selectedLevel == level.id
                        NexaraGlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (isSelected) Modifier.border(1.dp, NexaraColors.Primary, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .clickable { selectedLevel = level.id },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (isSelected) Modifier.background(NexaraColors.Primary.copy(alpha = 0.08f)) else Modifier)
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(level.tint.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(level.icon, null, tint = level.tint, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(thinkingLevelTitle(level.id), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(thinkingLevelDesc(level.id), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsPanel() {
    val infiniteTransition = rememberInfiniteTransition(label = "statsPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse"
    )

    val totalTokens = 4250
    val promptTokens = 1800
    val completionTokens = 2100
    val ragTokens = 350

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            val animatedProgress = (totalTokens.toFloat() / 8192f).coerceIn(0f, 1f)
            androidx.compose.material3.CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(100.dp),
                color = NexaraColors.Primary,
                strokeWidth = 6.dp,
                trackColor = NexaraColors.SurfaceHighest,
                strokeCap = StrokeCap.Round
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    totalTokens.toString(),
                    style = NexaraTypography.headlineLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    color = NexaraColors.OnSurface
                )
                Text(stringResource(R.string.sheet_stats_tokens), style = NexaraTypography.labelMedium.copy(fontSize = 10.sp), color = NexaraColors.OnSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricRow(
                icon = Icons.Rounded.Chat,
                iconTint = Color(0xFFA78BFA),
                label = stringResource(R.string.sheet_stats_prompt),
                value = promptTokens,
                max = 8192,
                color = Color(0xFFA78BFA)
            )
            MetricRow(
                icon = Icons.Rounded.Bolt,
                iconTint = Color(0xFFFBBF24),
                label = stringResource(R.string.sheet_stats_completion),
                value = completionTokens,
                max = 8192,
                color = Color(0xFFFBBF24)
            )
            MetricRow(
                icon = Icons.Rounded.Storage,
                iconTint = Color(0xFF10B981),
                label = stringResource(R.string.sheet_stats_rag),
                value = ragTokens,
                max = 8192,
                color = Color(0xFF10B981)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            onClick = {},
            shape = RoundedCornerShape(12.dp),
            color = NexaraColors.ErrorContainer.copy(alpha = 0.2f),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sheet_stats_reset), style = NexaraTypography.labelMedium, color = NexaraColors.Error)
            }
        }
    }
}

@Composable
private fun MetricRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: Int,
    max: Int,
    color: Color
) {
    val progress = (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
            }
            Text(
                value.toString(),
                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = color
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NexaraColors.SurfaceHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ToolsPanel() {
    var timeInjection by remember { mutableStateOf(true) }
    var strictMode by remember { mutableStateOf(false) }
    var executionMode by remember { mutableStateOf(ExecutionMode.AUTO) }
    var skillSearch by remember { mutableStateOf(true) }
    var skillCodeAnalysis by remember { mutableStateOf(true) }
    var skillWebSearch by remember { mutableStateOf(false) }
    var mcpServer1 by remember { mutableStateOf(true) }
    var mcpServer2 by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ToolToggleRow(stringResource(R.string.sheet_tool_time_injection), Icons.Rounded.Timer, timeInjection) { timeInjection = it }

        Text(stringResource(R.string.sheet_tool_skills), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)
        ToolToggleRow(stringResource(R.string.sheet_tool_search_retrieval), Icons.Rounded.Storage, skillSearch) { skillSearch = it }
        ToolToggleRow(stringResource(R.string.sheet_tool_code_analysis), Icons.Rounded.Psychology, skillCodeAnalysis) { skillCodeAnalysis = it }
        ToolToggleRow(stringResource(R.string.sheet_tool_web_search), Icons.Rounded.Memory, skillWebSearch) { skillWebSearch = it }

        ToolToggleRow(stringResource(R.string.sheet_tool_strict_mode), Icons.Rounded.School, strictMode) { strictMode = it }

        Text(stringResource(R.string.sheet_tool_execution_mode), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)
        ExecutionModeSelector(selected = executionMode, onSelect = { executionMode = it })

        Text(stringResource(R.string.sheet_tool_mcp_servers), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)
        ToolToggleRow(stringResource(R.string.sheet_tool_filesystem_server), Icons.Rounded.Storage, mcpServer1) { mcpServer1 = it }
        ToolToggleRow(stringResource(R.string.sheet_tool_github_server), Icons.Rounded.Storage, mcpServer2) { mcpServer2 = it }
    }
}

@Composable
private fun ToolToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NexaraColors.SurfaceLow)
            .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = NexaraTypography.bodyMedium.copy(fontSize = 14.sp), color = NexaraColors.OnSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = NexaraColors.InversePrimary,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = NexaraColors.SurfaceHighest,
                uncheckedThumbColor = NexaraColors.Outline
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
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

private val capabilityColorMap: Map<ModelCapability, Pair<Color, Color>> = mapOf(
    ModelCapability.REASONING to (Color(0xFFA78BFA) to Color(0xFF1E1B4B)),
    ModelCapability.VISION to (Color(0xFFF472B6) to Color(0xFF4A1942)),
    ModelCapability.WEB to (Color(0xFF38BDF8) to Color(0xFF0C2D48)),
    ModelCapability.CHAT to (Color(0xFF34D399) to Color(0xFF022C22)),
    ModelCapability.RERANK to (Color(0xFFFB923C) to Color(0xFF431407)),
    ModelCapability.EMBEDDING to (Color(0xFF22D3EE) to Color(0xFF083344)),
    ModelCapability.IMAGE to (Color(0xFFFCD34D) to Color(0xFF451A03))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    sessionId: String
) {
    if (!show) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(context.applicationContext as android.app.Application))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    
    val uiState by chatViewModel.uiState.collectAsState()
    val session = uiState.session
    
    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * 0.7f).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabTitles = listOf(
        stringResource(R.string.sheet_tab_model),
        stringResource(R.string.sheet_tab_thinking),
        stringResource(R.string.sheet_tab_tools),
        stringResource(R.string.sheet_tab_settings)
    )

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
                Text(stringResource(R.string.sheet_title), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f) // Increased slightly for more content
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
                    if (pagerState.currentPage < tabPositions.size) {
                        val pos = tabPositions[pagerState.currentPage]
                        Box(
                            Modifier
                                .tabIndicatorOffset(pos)
                                .padding(horizontal = 32.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NexaraColors.Primary)
                        )
                    }
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                title,
                                style = NexaraTypography.labelMedium.copy(
                                    fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal
                                ),
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
                    0 -> ModelPanel(
                        selectedModelId = session?.modelId ?: "",
                        onSelect = { modelId ->
                            chatViewModel.updateModelId(modelId)
                            onDismiss()
                        },
                        settingsViewModel = settingsViewModel
                    )
                    1 -> ParamsPanel(
                        session = session,
                        chatViewModel = chatViewModel
                    )
                    2 -> ToolsPanel(chatViewModel = chatViewModel, session = session)
                    3 -> SettingsPanel(
                        session = session,
                        chatViewModel = chatViewModel
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPanel(
    selectedModelId: String,
    onSelect: (String) -> Unit,
    settingsViewModel: SettingsViewModel
) {
    var searchQuery by remember { mutableStateOf("") }
    val allModels by settingsViewModel.providerModels.collectAsState()
    
    val modelItems = allModels.filter { 
        it.enabled && (it.type in listOf("chat", "reasoning", "image") || it.capabilities.any { cap -> cap.lowercase() in listOf("chat", "reasoning", "vision") })
    }.map { info ->
        val mappedCaps = mutableSetOf<ModelCapability>()
        when (info.type) {
            "chat" -> mappedCaps.add(ModelCapability.CHAT)
            "reasoning" -> mappedCaps.add(ModelCapability.REASONING)
            "image" -> mappedCaps.add(ModelCapability.IMAGE)
        }
        info.capabilities.forEach { capStr ->
            when (capStr.lowercase()) {
                "vision" -> mappedCaps.add(ModelCapability.VISION)
                "internet", "web" -> mappedCaps.add(ModelCapability.WEB)
                "reasoning" -> mappedCaps.add(ModelCapability.REASONING)
                "image" -> mappedCaps.add(ModelCapability.IMAGE)
                "embedding" -> mappedCaps.add(ModelCapability.EMBEDDING)
                "rerank" -> mappedCaps.add(ModelCapability.RERANK)
                "chat" -> mappedCaps.add(ModelCapability.CHAT)
            }
        }
        if (mappedCaps.isEmpty()) mappedCaps.add(ModelCapability.CHAT)

        ModelItem(
            id = info.id,
            name = info.name,
            providerName = info.providerName,
            capabilities = mappedCaps.toList(),
            contextLength = info.contextLength
        )
    }

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

        val filtered = if (searchQuery.isBlank()) modelItems else modelItems.filter {
            it.name.contains(searchQuery, true)
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered) { model ->
                val isSelected = model.id == selectedModelId
                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) Modifier.border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .clickable { onSelect(model.id) },
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
                                        val colors = capabilityColorMap[cap] ?: (Color.Gray to Color.DarkGray)
                                        val (fg, bg) = colors
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
private fun ParamsPanel(
    session: com.promenar.nexara.data.model.Session?,
    chatViewModel: ChatViewModel
) {
    val params = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams()
    
    var currentTemperature by remember(params.temperature) { mutableStateOf((params.temperature ?: 0.7).toFloat()) }
    var currentTimeout by remember(params.streamTimeout) { mutableStateOf(params.streamTimeout ?: 120) }
    var currentTopP by remember(params.topP) { mutableStateOf((params.topP ?: 1.0).toFloat()) }
    var currentMaxTokens by remember(params.maxTokens) { mutableStateOf(params.maxTokens ?: 0) }
    
    var currentTopK by remember(params.topK) { mutableStateOf(params.topK ?: 0) }
    var currentRepetitionPenalty by remember(params.repetitionPenalty) { mutableStateOf((params.repetitionPenalty ?: 1.0).toFloat()) }
    var currentPresencePenalty by remember(params.presencePenalty) { mutableStateOf((params.presencePenalty ?: 0.0).toFloat()) }
    var currentFrequencyPenalty by remember(params.frequencyPenalty) { mutableStateOf((params.frequencyPenalty ?: 0.0).toFloat()) }
    val selectedLevel = when {
        currentTemperature <= 0.3f -> "minimal"
        currentTemperature <= 0.6f -> "low"
        currentTemperature <= 0.9f -> "medium"
        else -> "high"
    }

    val onLevelClick: (String) -> Unit = { levelId ->
        val (newTemp, newTopP, newTopK) = when (levelId) {
            "minimal" -> Triple(0.1f, 0.5f, 10)
            "low"     -> Triple(0.4f, 0.8f, 30)
            "medium"  -> Triple(0.7f, 0.9f, 50)
            "high"    -> Triple(1.0f, 1.0f, 100)
            else      -> Triple(0.7f, 0.9f, 50)
        }
        currentTemperature = newTemp
        currentTopP = newTopP
        currentTopK = newTopK
        chatViewModel.updateInferenceParams(params.copy(
            temperature = newTemp.toDouble(),
            topP = newTopP.toDouble(),
            topK = newTopK
        ))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp)
    ) {
        item {
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
                                    .clickable { onLevelClick(level.id) },
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
            Spacer(modifier = Modifier.height(32.dp))
        }

        // --- Standard Generation Parameters ---
        item {
            HorizontalDivider(color = NexaraColors.GlassBorder, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_section_inference),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NexaraColors.OnSurface
            )
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_stream_timeout),
                style = NexaraTypography.titleSmall,
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexaraSliderInt(
                    value = currentTimeout,
                    onValueChange = {
                        currentTimeout = it
                        chatViewModel.updateInferenceParams(params.copy(streamTimeout = currentTimeout))
                    },
                    valueRange = 30..300,
                    steps = 26,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.session_settings_unit_seconds, currentTimeout),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_top_p),
                style = NexaraTypography.titleSmall,
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexaraSlider(
                    value = currentTopP,
                    onValueChange = {
                        currentTopP = it
                        chatViewModel.updateInferenceParams(params.copy(topP = it.toDouble()))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = String.format("%.2f", currentTopP),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_max_tokens),
                style = NexaraTypography.titleSmall,
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexaraSlider(
                    value = currentMaxTokens.toFloat(),
                    onValueChange = {
                        currentMaxTokens = it.toInt()
                        chatViewModel.updateInferenceParams(params.copy(maxTokens = if (currentMaxTokens == 0) null else currentMaxTokens))
                    },
                    valueRange = 0f..8192f,
                    steps = 64,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (currentMaxTokens == 0) stringResource(R.string.sheet_settings_unlimited) else currentMaxTokens.toString(),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        // --- Advanced Parameters ---
        item {
            NexaraCollapsibleSection(
                title = stringResource(R.string.sheet_settings_advanced_params),
                initiallyExpanded = false
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Top K
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_top_k),
                            style = NexaraTypography.titleSmall,
                            color = NexaraColors.OnSurface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NexaraSliderInt(
                                value = currentTopK,
                                onValueChange = {
                                    currentTopK = it
                                    chatViewModel.updateInferenceParams(params.copy(topK = if (currentTopK == 0) null else currentTopK))
                                },
                                valueRange = 0..100,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = if (currentTopK == 0) stringResource(R.string.sheet_settings_unlimited) else currentTopK.toString(),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    // Repetition Penalty
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_repetition_penalty),
                            style = NexaraTypography.titleSmall,
                            color = NexaraColors.OnSurface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NexaraSlider(
                                value = currentRepetitionPenalty,
                                onValueChange = {
                                    currentRepetitionPenalty = it
                                    chatViewModel.updateInferenceParams(params.copy(repetitionPenalty = it.toDouble()))
                                },
                                valueRange = 0.5f..2.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = String.format("%.2f", currentRepetitionPenalty),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    // Presence Penalty
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_presence_penalty),
                            style = NexaraTypography.titleSmall,
                            color = NexaraColors.OnSurface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NexaraSlider(
                                value = currentPresencePenalty,
                                onValueChange = {
                                    currentPresencePenalty = it
                                    chatViewModel.updateInferenceParams(params.copy(presencePenalty = it.toDouble()))
                                },
                                valueRange = -2.0f..2.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = String.format("%.2f", currentPresencePenalty),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    // Frequency Penalty
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_frequency_penalty),
                            style = NexaraTypography.titleSmall,
                            color = NexaraColors.OnSurface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NexaraSlider(
                                value = currentFrequencyPenalty,
                                onValueChange = {
                                    currentFrequencyPenalty = it
                                    chatViewModel.updateInferenceParams(params.copy(frequencyPenalty = it.toDouble()))
                                },
                                valueRange = -2.0f..2.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = String.format("%.2f", currentFrequencyPenalty),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsPanel(
    chatViewModel: ChatViewModel,
    session: com.promenar.nexara.data.model.Session?
) {
    val options = session?.options ?: com.promenar.nexara.data.model.SessionOptions()
    
    var timeInjection by remember(options.enableTimeInjection) { mutableStateOf(options.enableTimeInjection) }
    var toolsEnabled by remember(options.toolsEnabled) { mutableStateOf(options.toolsEnabled) }
    
    val onToggle: (String, Boolean) -> Unit = { tool, enabled ->
        chatViewModel.toggleTool(tool, enabled)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ToolToggleRow(stringResource(R.string.sheet_tool_time_injection), Icons.Rounded.Timer, timeInjection) { 
            timeInjection = it
            onToggle("timeInjection", it)
        }

        Text(stringResource(R.string.sheet_tab_tools), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)
        ToolToggleRow(stringResource(R.string.sheet_tool_search_retrieval), Icons.Rounded.Storage, toolsEnabled) { 
            toolsEnabled = it
            onToggle("toolsEnabled", it)
        }

        val executionMode = session?.executionMode?.ifEmpty { "semi" } ?: "semi"
        val modeDesc = when (executionMode) {
            "auto" -> stringResource(R.string.sheet_execution_mode_auto)
            "manual" -> stringResource(R.string.sheet_execution_mode_manual)
            else -> stringResource(R.string.sheet_execution_mode_semi)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = NexaraColors.SurfaceLow
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.sheet_tool_execution_mode),
                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NexaraColors.OnSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    modeDesc,
                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    session: com.promenar.nexara.data.model.Session?,
    chatViewModel: ChatViewModel
) {
    val params = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams()
    val ragOptions = session?.ragOptions ?: com.promenar.nexara.data.model.RagOptions()

    var currentSummaryThreshold by remember(params.autoSummaryThreshold) { mutableStateOf(params.autoSummaryThreshold.toFloat()) }
    var currentActiveWindow by remember(params.activeContextWindow) { mutableStateOf(params.activeContextWindow) }
    
    var rerankEnabled by remember(ragOptions.enableRerank) { mutableStateOf(ragOptions.enableRerank) }
    var memoryEnabled by remember(ragOptions.enableMemory) { mutableStateOf(ragOptions.enableMemory) }
    var globalMemoryEnabled by remember(ragOptions.isGlobal) { mutableStateOf(ragOptions.isGlobal) }
    var docsEnabled by remember(ragOptions.enableDocs) { mutableStateOf(ragOptions.enableDocs) }
    var kgEnabled by remember(ragOptions.enableKnowledgeGraph) { mutableStateOf(ragOptions.enableKnowledgeGraph ?: false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_section_vectorization),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NexaraColors.OnSurface
            )
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_summary_threshold),
                style = NexaraTypography.titleSmall,
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexaraSlider(
                    value = currentSummaryThreshold,
                    onValueChange = {
                        currentSummaryThreshold = it
                        val p = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams()
                        chatViewModel.updateInferenceParams(p.copy(autoSummaryThreshold = it.toDouble()))
                    },
                    valueRange = 0.5f..0.95f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "${(currentSummaryThreshold * 100).toInt()}%",
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_active_window),
                style = NexaraTypography.titleSmall,
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexaraSliderInt(
                    value = currentActiveWindow,
                    onValueChange = {
                        currentActiveWindow = it
                        val p = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams()
                        chatViewModel.updateInferenceParams(p.copy(activeContextWindow = currentActiveWindow))
                    },
                    valueRange = 5..50,
                    steps = 8,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.sheet_settings_active_window_unit, currentActiveWindow),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary,
                    modifier = Modifier.width(80.dp)
                )
            }
        }

        // --- Vector Retrieval ---
        item {
            HorizontalDivider(color = NexaraColors.GlassBorder, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_section_retrieval),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NexaraColors.OnSurface
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolToggleRow(stringResource(R.string.sheet_settings_memory), Icons.Rounded.History, memoryEnabled) { 
                    memoryEnabled = it
                    chatViewModel.updateRagOptions(ragOptions.copy(enableMemory = it))
                }
                ToolToggleRow(stringResource(R.string.sheet_settings_global_memory), Icons.Rounded.Share, globalMemoryEnabled) { 
                    globalMemoryEnabled = it
                    chatViewModel.updateRagOptions(ragOptions.copy(isGlobal = it))
                }
                ToolToggleRow(stringResource(R.string.sheet_settings_docs), Icons.Rounded.Description, docsEnabled) { 
                    docsEnabled = it
                    chatViewModel.updateRagOptions(ragOptions.copy(enableDocs = it))
                }
                ToolToggleRow(stringResource(R.string.sheet_tool_rerank), Icons.Rounded.Sync, rerankEnabled) { 
                    rerankEnabled = it
                    chatViewModel.updateRagOptions(ragOptions.copy(enableRerank = it))
                }
                ToolToggleRow(stringResource(R.string.sheet_settings_kg), Icons.Rounded.Psychology, kgEnabled) { 
                    kgEnabled = it
                    chatViewModel.updateRagOptions(ragOptions.copy(enableKnowledgeGraph = it))
                }
                val isRagFullyDisabled = !memoryEnabled && !globalMemoryEnabled && !docsEnabled && !kgEnabled
                if (isRagFullyDisabled) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = NexaraColors.SurfaceLow.copy(alpha = 0.5f),
                        border = BorderStroke(0.5.dp, NexaraColors.GlassBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = NexaraColors.OnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    stringResource(R.string.sheet_settings_rag_disabled),
                                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = NexaraColors.OnSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.sheet_settings_rag_disabled_hint),
                                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- UI Settings ---
        item {
            HorizontalDivider(color = NexaraColors.GlassBorder, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_ui_title),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NexaraColors.OnSurface
            )
        }

        item {
            val options = session?.options ?: com.promenar.nexara.data.model.SessionOptions()
            var currentFontSize by remember(options.fontSize) { mutableStateOf(options.fontSize) }

            Text(
                text = stringResource(R.string.sheet_settings_font_size),
                style = NexaraTypography.titleSmall,
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexaraSliderInt(
                    value = currentFontSize,
                    onValueChange = { 
                        currentFontSize = it
                        chatViewModel.updateFontSizeLocally(currentFontSize)
                    },
                    onValueChangeFinished = {
                        chatViewModel.updateFontSize(currentFontSize)
                    },
                    valueRange = 10..18,
                    steps = 7,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "$currentFontSize ${stringResource(R.string.sheet_settings_font_size_unit)}",
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
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

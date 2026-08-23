package com.promenar.nexara.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.MaterialTheme
import com.promenar.nexara.ui.common.ModelSelectionUiModel
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.ui.common.ModelSelectionListItem
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.promenar.nexara.data.manager.ProviderManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.nexara.R
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.launch

private data class ThinkingLevelOption(
    val id: String,
    val icon: ImageVector
)

private val thinkingLevels = listOf(
    ThinkingLevelOption("minimal", Icons.Rounded.Bolt),
    ThinkingLevelOption("low", Icons.Rounded.Psychology),
    ThinkingLevelOption("medium", Icons.Rounded.AutoFixHigh),
    ThinkingLevelOption("high", Icons.Rounded.School)
)

@Composable
private fun getThinkingLevelColor(id: String): Color {
    return when (id) {
        "minimal" -> MaterialTheme.colorScheme.secondary
        "low" -> MaterialTheme.colorScheme.primary
        "medium" -> MaterialTheme.colorScheme.tertiary
        "high" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
}

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


internal fun filterLocalInferenceModels(
    models: List<ModelInfo>,
    localProviderIds: Set<String>,
    localInferenceAvailable: Boolean,
): List<ModelInfo> {
    if (localInferenceAvailable || localProviderIds.isEmpty()) return models
    return models.filterNot { model ->
        val providerId = model.providerId ?: model.id.substringBefore("::").takeIf { "::" in model.id }
        providerId in localProviderIds
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    chatViewModel: ChatViewModel,
) {
    if (!show) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val providerModels by settingsViewModel.providerModels.collectAsStateWithLifecycle()
    val providers by settingsViewModel.providers.collectAsStateWithLifecycle()
    val visibleProviderModels = filterLocalInferenceModels(
        models = providerModels,
        localProviderIds = providers.filter { it.protocolType is ProtocolType.Local }.mapTo(mutableSetOf()) { it.id },
        localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    )
    
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.sheet_title), style = NexaraTypography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
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
                contentColor = MaterialTheme.colorScheme.onSurface,
                edgePadding = 24.dp,
                divider = {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
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
                                .background(MaterialTheme.colorScheme.primary)
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
                                color = if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                        allModels = visibleProviderModels,
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

@Composable
internal fun ModelPanel(
    selectedModelId: String,
    onSelect: (String) -> Unit,
    allModels: List<ModelInfo>,
) {
    var searchQuery by remember { mutableStateOf("") }
    val resolver = remember { ModelMetadataResolver() }

    val projectedModels = remember(allModels) {
        allModels.filter { it.enabled }.map { it.toModelSelectionUiModel(resolver) }
    }
    val modelItems = remember(projectedModels, selectedModelId) {
        filterSessionSelectionModels(
            models = projectedModels,
            selectedModelId = selectedModelId,
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

        val filtered = remember(searchQuery, modelItems) {
            if (searchQuery.isBlank()) modelItems else modelItems.filter {
                it.displayName.contains(searchQuery, true)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTags.CHAT_MODEL_LIST)
        ) {
            itemsIndexed(filtered, key = { _, it -> it.selectionId }) { index, model ->
                val isSelected = model.selectionId == selectedModelId
                ModelSelectionListItem(
                    model = model,
                    selected = isSelected,
                    onClick = { onSelect(model.selectionId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.chatModelOption(model.selectionId))
                )

                if (index < filtered.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

internal fun filterSessionSelectionModels(
    models: List<ModelSelectionUiModel>,
    selectedModelId: String,
): List<ModelSelectionUiModel> = models.filter { model ->
    model.selectionId == selectedModelId ||
        model.isChatSelectionCandidate()
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
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                        else Modifier
                                    )
                                    .clickable { onLevelClick(level.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier)
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val levelColor = getThinkingLevelColor(level.id)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(levelColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(level.icon, null, tint = levelColor, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(thinkingLevelTitle(level.id), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(thinkingLevelDesc(level.id), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_section_inference),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_stream_timeout),
                style = NexaraTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_top_p),
                style = NexaraTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_max_tokens),
                style = NexaraTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.primary,
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
                            color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    // Repetition Penalty
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_repetition_penalty),
                            style = NexaraTypography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    // Presence Penalty
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_presence_penalty),
                            style = NexaraTypography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    // Frequency Penalty
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_settings_frequency_penalty),
                            style = NexaraTypography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.primary,
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

    val isGeminiModel = session?.modelId?.contains("gemini", ignoreCase = true) == true
    var geminiSearchEnabled by remember(options.enableGeminiSearch) { mutableStateOf(options.enableGeminiSearch) }

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

        var economyMode by remember(options.economyMode) { mutableStateOf(options.economyMode) }
        ToolToggleRow(stringResource(R.string.sheet_tool_economy_mode), Icons.Rounded.Tune, economyMode) {
            economyMode = it
            onToggle("economyMode", it)
        }

        if (isGeminiModel) {
            ToolToggleRow(stringResource(R.string.sheet_tool_gemini_grounding), Icons.Rounded.Language, geminiSearchEnabled) {
                geminiSearchEnabled = it
                onToggle("enableGeminiSearch", it)
            }
        }

        Text(stringResource(R.string.sheet_tab_tools), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        ToolToggleRow(stringResource(R.string.sheet_tool_search_retrieval), Icons.Rounded.Storage, toolsEnabled) { 
            toolsEnabled = it
            onToggle("toolsEnabled", it)
        }

        val executionMode = session?.executionMode?.ifEmpty { "semi" } ?: "semi"
        val selectedExecutionMode = when (executionMode) {
            "auto" -> ExecutionMode.AUTO
            "manual" -> ExecutionMode.MANUAL
            else -> ExecutionMode.SEMI
        }
        val modeDesc = when (executionMode) {
            "auto" -> stringResource(R.string.sheet_execution_mode_auto)
            "manual" -> stringResource(R.string.sheet_execution_mode_manual)
            else -> stringResource(R.string.sheet_execution_mode_semi)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.sheet_tool_execution_mode),
                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                ExecutionModeSelector(
                    selected = selectedExecutionMode,
                    onSelect = { selected ->
                        chatViewModel.updateExecutionMode(selected.name.lowercase())
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    modeDesc,
                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

    val presetRerankModel by ProviderManager.getInstance().rerankModelId.collectAsStateWithLifecycle()
    val isRerankAvailable = presetRerankModel.isNotBlank()

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
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_summary_threshold),
                style = NexaraTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(60.dp)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.sheet_settings_active_window),
                style = NexaraTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(80.dp)
                )
            }
        }

        // --- Vector Retrieval ---
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_section_retrieval),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolToggleRow(stringResource(R.string.sheet_settings_memory), Icons.Rounded.History, memoryEnabled) { 
                    memoryEnabled = it
                    chatViewModel.updateRagOptions(chatViewModel.currentRagOptions.value.copy(enableMemory = it))
                }
                ToolToggleRow(stringResource(R.string.sheet_settings_global_memory), Icons.Rounded.Share, globalMemoryEnabled) { 
                    globalMemoryEnabled = it
                    chatViewModel.updateRagOptions(chatViewModel.currentRagOptions.value.copy(isGlobal = it))
                }
                ToolToggleRow(stringResource(R.string.sheet_settings_docs), Icons.Rounded.Description, docsEnabled) { 
                    docsEnabled = it
                    chatViewModel.updateRagOptions(chatViewModel.currentRagOptions.value.copy(enableDocs = it))
                }
                ToolToggleRow(
                    label = stringResource(R.string.sheet_tool_rerank),
                    icon = Icons.Rounded.Sync,
                    checked = isRerankAvailable && rerankEnabled,
                    enabled = isRerankAvailable
                ) { 
                    rerankEnabled = it
                    chatViewModel.updateRagOptions(chatViewModel.currentRagOptions.value.copy(enableRerank = it))
                }
                if (!isRerankAvailable) {
                    Text(
                        text = stringResource(R.string.sheet_settings_rerank_unavailable),
                        style = NexaraTypography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
                    )
                }
                ToolToggleRow(stringResource(R.string.sheet_settings_kg), Icons.Rounded.Psychology, kgEnabled) { 
                    kgEnabled = it
                    chatViewModel.updateRagOptions(chatViewModel.currentRagOptions.value.copy(enableKnowledgeGraph = it))
                }
                val isRagFullyDisabled = !memoryEnabled && !globalMemoryEnabled && !docsEnabled && !kgEnabled
                if (isRagFullyDisabled) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    stringResource(R.string.sheet_settings_rag_disabled),
                                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.sheet_settings_rag_disabled_hint),
                                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- UI Settings ---
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.sheet_settings_ui_title),
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            val options = session?.options ?: com.promenar.nexara.data.model.SessionOptions()
            var currentFontSize by remember(options.fontSize) { mutableStateOf(options.fontSize) }

            Text(
                text = stringResource(R.string.sheet_settings_font_size),
                style = NexaraTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.primary,
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
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .alpha(alpha)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = NexaraTypography.bodyMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.inversePrimary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import com.promenar.nexara.ui.common.NexaraBackButton
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.findModelSpec
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSnackbarData
import com.promenar.nexara.ui.common.NexaraSnackbarHost
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun Message.hasRagArtifacts(): Boolean =
    !ragReferences.isNullOrEmpty() || !kgPaths.isNullOrEmpty() ||
        !citations.isNullOrEmpty() || ragReferencesLoading

internal fun selectRagActiveMessage(messages: List<Message>): Message? =
    messages.find { it.hasRagArtifacts() } ?: messages.lastOrNull()

internal fun chatRenderStateTag(uiState: ChatUiState): String = when {
    uiState.error != null || uiState.generationNotice != null ||
        uiState.status == GenerationStatus.ERROR -> UiTags.CHAT_STATE_ERROR
    uiState.approvalRequest != null -> UiTags.CHAT_STATE_APPROVAL
    uiState.isGenerating -> UiTags.CHAT_STATE_GENERATING
    uiState.isLoading -> UiTags.CHAT_STATE_LOADING
    uiState.messages.isEmpty() -> UiTags.CHAT_STATE_EMPTY
    else -> UiTags.CHAT_STATE_READY
}

data class ChatScreenState(
    val uiState: ChatUiState = ChatUiState(),
    val inputText: String = "",
    val tokenState: ChatViewModel.TokenIndicatorState = ChatViewModel.TokenIndicatorState(),
    val ragPhases: List<com.promenar.nexara.data.model.RagPhase> = emptyList(),
    val compressionState: ChatViewModel.CompressionState = ChatViewModel.CompressionState(),
    val postProcessTasks: List<PostProcessTask> = emptyList(),
    val selectedImageUris: List<android.net.Uri> = emptyList(),
)

data class ChatScreenActions(
    val onNavigateBack: () -> Unit = {},
    val onOpenWorkspace: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenPromptEditor: () -> Unit = {},
    val onOpenClearDialog: () -> Unit = {},
    val onOpenRenameDialog: () -> Unit = {},
    val onOpenDeleteDialog: () -> Unit = {},
    val onContentChange: (String, String) -> Unit = { _, _ -> },
    val onCopy: (String) -> Unit = {},
    val onDeleteMessage: (String) -> Unit = {},
    val onRegenerateMessage: (String) -> Unit = {},
    val onApprove: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onRemovePostProcessTask: (String) -> Unit = {},
    val onManualSummary: () -> Unit = {},
    val onTextChange: (String) -> Unit = {},
    val onSend: (String, List<android.net.Uri>) -> Unit = { _, _ -> },
    val onStop: () -> Unit = {},
    val onPickImages: () -> Unit = {},
    val onRemoveImage: (Int) -> Unit = {},
    val onSnackbarAction: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreenContent(
    state: ChatScreenState,
    actions: ChatScreenActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    snackbarData: NexaraSnackbarData? = null,
    taskPanel: @Composable () -> Unit = {},
) {
    val uiState = state.uiState
    val inputText = state.inputText
    val tokenState = state.tokenState
    val ragPhases = state.ragPhases
    val compressionState = state.compressionState
    val selectedImageUris = state.selectedImageUris
    val listState = rememberLazyListState()
    var showModelHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(showModelHint) {
        if (showModelHint) {
            delay(2500)
            showModelHint = false
        }
    }

    val sessionTitle = uiState.session?.title ?: stringResource(R.string.chat_title_new)
    val agentName = uiState.agentName

    val pipelineGroups = remember(uiState.messages) { buildPipelineGroups(uiState.messages) }

    val density = LocalDensity.current
    val isUserScrolledAway by remember(pipelineGroups.size, uiState.isGenerating) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf false

            val totalItemsCount = layoutInfo.totalItemsCount
            val threshold = with(density) { 60.dp.toPx() }
            val viewportBottom = layoutInfo.viewportEndOffset

            val targetIndex = if (uiState.isGenerating && pipelineGroups.isNotEmpty()) {
                pipelineGroups.lastIndex
            } else {
                totalItemsCount - 1
            }

            val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
                ?: return@derivedStateOf true
            val targetBottom = targetItem.offset + targetItem.size
            targetBottom > viewportBottom + threshold
        }
    }

    val describeImagePrompt = stringResource(R.string.chat_image_only_prompt)
    val approvalArgumentsLabel = stringResource(R.string.chat_approval_arguments)
    val approvalFallback = stringResource(R.string.chat_approval_fallback)

    // ═══════════════════════════════════════════════════════════
    //  智能视角追踪 — Pin to Bottom
    //  新消息发送 → 滚到底部开启追踪 → 流式输出时跟随最新行 →
    //  用户触摸介入 → 切断追踪 → FAB 点击恢复追踪
    // ═══════════════════════════════════════════════════════════

    var autoFollowEnabled by remember { mutableStateOf(true) }

    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    autoFollowEnabled = false
                }
                return Offset.Zero
            }
        }
    }

    suspend fun scrollToStreamingTail() {
        val activeIndex = pipelineGroups.lastIndex
        if (activeIndex < 0) return

        val inputOverlapPx = with(density) { 180.dp.roundToPx() }

        // 基于最新一次布局读取目标 item，做像素级遮挡校正。
        // 目标 item 不在组成窗口内时直接返回，由调用方先粗滚再重新校正。
        suspend fun correctActiveOverlap() {
            val layoutInfo = listState.layoutInfo
            val activeItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
                ?: return
            val targetBottom = layoutInfo.viewportEndOffset - inputOverlapPx
            val overflow = (activeItem.offset + activeItem.size) - targetBottom
            if (overflow > 0) {
                listState.scrollBy(overflow.toFloat())
            }
        }

        val activeItemVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
        if (!activeItemVisible) {
            // 目标 item 在屏幕外：先把它拉进组成窗口。scrollToItem 会挂起直到该 item
            // 完成一次布局，返回后 layoutInfo 已反映新位置；随后重新读取并精确校正遮挡，
            // 避免仅做粗滚而把尾部消息留在输入浮岛覆盖区内。
            listState.scrollToItem(activeIndex, 100_000)
            withFrameNanos { }
        }

        correctActiveOverlap()
    }

    // 新用户消息 → 恢复追踪 + 滚到底部
    val latestUserMsgId = uiState.messages.lastOrNull { it.role == MessageRole.USER }?.id ?: ""
    LaunchedEffect(latestUserMsgId) {
        if (latestUserMsgId.isNotEmpty()) {
            autoFollowEnabled = true
            delay(32)
            scrollToStreamingTail()
        }
    }

    val latestAssistantMsg = uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val followContentLength = (latestAssistantMsg?.content?.length ?: 0) +
        (latestAssistantMsg?.reasoning?.length ?: 0) +
        uiState.streamingContent.length

    // 生成中跟随当前 AI item 的尾部。不能锚定 bottom_spacer：超长思考块生成时
    // spacer 往往不在 LazyColumn 组成窗口内，bringIntoView 会退化成 no-op。
    LaunchedEffect(
        uiState.isGenerating,
        autoFollowEnabled,
        latestAssistantMsg?.id,
        followContentLength,
        ragPhases.size
    ) {
        if (uiState.isGenerating && autoFollowEnabled) {
            delay(16)
            scrollToStreamingTail()
        }
    }

    // IME 键盘避让：IME 弹出会通过 imePadding() 收缩列表视口。若在动画过程中校正，
    // 滚动会基于尚未稳定的视口，动画结束后尾消息被推出可见区且不再重滚。
    val isImeVisible = WindowInsets.isImeVisible
    val imeInsets = WindowInsets.ime
    LaunchedEffect(isImeVisible, autoFollowEnabled, pipelineGroups.size) {
        if (!isImeVisible || !autoFollowEnabled || pipelineGroups.isEmpty()) return@LaunchedEffect

        snapshotFlow { imeInsets.getBottom(density) }.collectLatest { insetBottom ->
            if (insetBottom <= 0) return@collectLatest
            // collectLatest 会取消上一帧尚未完成的等待；只有 inset 稳定后才执行校正。
            delay(64)
            scrollToStreamingTail()
        }
    }



    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            ChatTopBar(
                title = sessionTitle,
                subtitle = if (uiState.isGenerating) stringResource(R.string.chat_status_thinking) else agentName.ifBlank { sessionTitle },
                onBack = actions.onNavigateBack,
                onWorkspace = actions.onOpenWorkspace,
                onSettings = actions.onOpenSettings,
                onSessionPrompt = actions.onOpenPromptEditor,
                onClearHistory = actions.onOpenClearDialog,
                onRename = actions.onOpenRenameDialog,
                onDeleteSession = actions.onOpenDeleteDialog,
            )
        },
        snackbarHost = {
            Box(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                NexaraSnackbarHost(
                    hostState = snackbarHostState,
                    snackbarData = snackbarData,
                    onAction = {
                        actions.onSnackbarAction()
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .testTag(UiTags.CHAT_ROOT),
        ) {
            val renderStateTag = chatRenderStateTag(uiState)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .widthIn(max = 960.dp)
                    .fillMaxWidth()
                    .nestedScroll(userScrollConnection)
                    .testTag(renderStateTag),
                // 底部留白必须大于 180dp 输入浮岛避让目标，确保滚动校正始终可达。
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                    // pipelineGroups 已在外部通过 remember 计算，此处直接引用
                    items(pipelineGroups.size, key = { pipelineGroups[it].messages.first().id }) { idx ->
                        val group = pipelineGroups[idx]
                        val isGeneratingGroup = idx == pipelineGroups.lastIndex && uiState.isGenerating

                        if (!group.isUser) {
                            val ragActiveMsg = selectRagActiveMessage(group.assistantMessages)

                            if (ragActiveMsg != null) {
                                val targetPhases = if (isGeneratingGroup) ragPhases else emptyList()
                                val hasRagArtifacts = ragActiveMsg.hasRagArtifacts()
                                val ragLoading = isGeneratingGroup && targetPhases.any { it.status == PhaseStatus.ACTIVE }
                                val ragComplete = targetPhases.isNotEmpty() && targetPhases.all { it.status == PhaseStatus.DONE }
                                if (targetPhases.isNotEmpty() || hasRagArtifacts) {
                                    RagProgressCard(
                                        phases = targetPhases,
                                        references = ragActiveMsg.ragReferences,
                                        kgPaths = ragActiveMsg.kgPaths,
                                        citations = ragActiveMsg.citations,
                                        isComplete = if (isGeneratingGroup) (ragComplete || !ragLoading) else true
                                    )
                                }
                            }
                        }

                        PipelineBubble(
                            group = group,
                            isGenerating = isGeneratingGroup,
                            status = uiState.status,
                            streamingContent = uiState.streamingContent,
                            fontSize = uiState.session?.options?.fontSize ?: 13,
                            onContentChange = { newContent ->
                                group.assistantMessages.lastOrNull()?.let { lastMsg ->
                                    actions.onContentChange(lastMsg.id, newContent)
                                }
                            },
                            onCopy = { text ->
                                actions.onCopy(text)
                            },
                            onDelete = actions.onDeleteMessage,
                            onRegenerate = actions.onRegenerateMessage,
                        )
                    }

                    if (compressionState.isCompressing || compressionState.result != null) {
                        item(key = "summary_card") {
                            SummaryCard(
                                isCompressing = compressionState.isCompressing,
                                progress = compressionState.progress,
                                detail = compressionState.detail,
                                result = compressionState.result
                            )
                        }
                    }

                    uiState.approvalRequest?.let { request ->
                        item(key = "approval_request") {
                            ChatApprovalLiveRegion {
                                ApprovalCard(
                                    toolName = request.toolName ?: stringResource(R.string.chat_approval_unknown_tool),
                                    description = approvalDescription(
                                        request,
                                        approvalArgumentsLabel,
                                        approvalFallback,
                                    ),
                                    onApprove = actions.onApprove,
                                    onDecline = actions.onDecline,
                                )
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
            }

            // ── Skeleton 与其他 Overlay 需在 LazyColumn 之上但独立于输入框 ──
            AnimatedVisibility(
                visible = uiState.isLoading && uiState.messages.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .widthIn(max = 960.dp)
                    .fillMaxWidth()
                    .padding(bottom = 160.dp)
                    .testTag(UiTags.CHAT_LOADING_SKELETON)
            ) {
                ChatSkeleton(modifier = Modifier.fillMaxSize())
            }

                // ── 宽幅低矮版 MD3 风格浮岛 (Optimized Solid MD3 Island) ──
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 4.dp) // 极窄外边距，显著加宽
                        .padding(bottom = 8.dp)
                        .widthIn(max = 960.dp)
                        .fillMaxWidth(),
                    color = NexaraColors.SurfaceLow, // 调整颜色为更深的 SurfaceLow，契合 Header
                    shape = RoundedCornerShape(24.dp), // 略微减小圆角，配合加宽效果
                    border = BorderStroke(1.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f)),
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 10.dp), // 降低水平间距从 18dp -> 8dp，拓宽本体
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val modelDisplayName = remember(uiState.session?.modelId) {
                            uiState.session?.modelId?.let { id ->
                                findModelSpec(id)?.note ?: id
                            } ?: ""
                        }
                        ChatInputTopBar(
                            modelName = modelDisplayName,
                            tokenState = tokenState,
                            postProcessTasks = state.postProcessTasks,
                            onRemovePostProcessTask = actions.onRemovePostProcessTask,
                            onModelClick = actions.onOpenSettings,
                            onManualSummary = actions.onManualSummary,
                        )

                        if (selectedImageUris.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(selectedImageUris.size) { index ->
                                    val uri = selectedImageUris[index]
                                    Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) {
                                        coil3.compose.AsyncImage(
                                            model = uri,
                                            contentDescription = stringResource(R.string.chat_cd_selected_image),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = { actions.onRemoveImage(index) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                stringResource(R.string.chat_cd_remove_image),
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 任务浮动面板
                        taskPanel()

                        Box(modifier = Modifier.fillMaxWidth()) {
                            ChatInputBar(
                                text = inputText,
                                placeholder = if (agentName.isNotBlank()) stringResource(R.string.chat_input_placeholder, agentName) else stringResource(R.string.chat_input_placeholder_default),
                                onTextChange = actions.onTextChange,
                                onSend = {
                                    if (inputText.isNotBlank() || selectedImageUris.isNotEmpty()) {
                                        val textToSend = inputText.ifBlank { describeImagePrompt }
                                        actions.onSend(textToSend, selectedImageUris)
                                    }
                                },
                                status = uiState.status,
                                onStop = actions.onStop,
                                isModelSelected = uiState.session?.modelId?.isNotBlank() == true,
                                onModelHint = { showModelHint = true },
                                onPickImage = actions.onPickImages,
                                hasImages = selectedImageUris.isNotEmpty()
                            )
    
                            // ── 模型未选择提示气泡 ──
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showModelHint,
                                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(y = (-45).dp, x = (-10).dp)
                            ) {
                                Surface(
                                    color = NexaraColors.Primary,
                                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 2.dp),
                                    shadowElevation = 8.dp
                                ) {
                                    Text(
                                        text = stringResource(R.string.chat_hint_select_model),
                                        style = NexaraTypography.labelMedium,
                                        color = NexaraColors.OnPrimary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            
                AnimatedVisibility(
                    visible = isUserScrolledAway && (!uiState.isGenerating || !autoFollowEnabled),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            autoFollowEnabled = true
                            scope.launch {
                                scrollToStreamingTail()
                            }
                        },
                        containerColor = NexaraColors.SurfaceHigh,
                        contentColor = NexaraColors.Primary,
                        shape = CircleShape,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("chat_scroll_bottom")
                    ) {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            stringResource(R.string.chat_cd_scroll_bottom),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

}

private fun approvalDescription(
    request: com.promenar.nexara.data.model.ApprovalRequest,
    argumentsLabel: String,
    fallback: String,
): String {
    val reason = request.reason?.takeIf { it.isNotBlank() }
    val args = request.args
        ?.takeIf { it.isNotBlank() }
        ?.replace(Regex("\\s+"), " ")
        ?.let { if (it.length > 180) it.take(177) + "..." else it }

    return listOfNotNull(
        reason,
        args?.let { "$argumentsLabel: $it" }
    ).joinToString("\n").ifBlank {
        fallback
    }
}

@Composable
internal fun ChatApprovalLiveRegion(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .testTag("chat_approval_live_region"),
    ) { content() }
}

@Composable
fun ContextCircularIndicator(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = color.copy(alpha = 0.2f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatInputTopBar(
    modelName: String,
    tokenState: ChatViewModel.TokenIndicatorState,
    postProcessTasks: List<PostProcessTask>,
    onRemovePostProcessTask: (String) -> Unit,
    onModelClick: () -> Unit,
    onManualSummary: () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Model Indicator
        NexaraGlassCard(
            onClick = onModelClick,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag(UiTags.CHAT_MODEL_SELECTOR)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Rounded.Memory, null, tint = NexaraColors.Primary, modifier = Modifier.size(14.dp))
                Text(
                    text = modelName.ifBlank { stringResource(R.string.chat_model_placeholder) },
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = if (modelName.isBlank()) NexaraColors.OnSurfaceVariant else NexaraColors.OnSurface
                )
            }
        }

        // Token Indicator
        TokenIndicator(state = tokenState, onManualSummary = onManualSummary)

        // PostProcess Tasks (e.g. Session RAG, Summary)
        postProcessTasks.forEach { task ->
            PostProcessChip(
                task = task,
                onRemove = { onRemovePostProcessTask(task.id) }
            )
        }

    }
}

@Composable
private fun TokenIndicator(
    state: ChatViewModel.TokenIndicatorState,
    onManualSummary: () -> Unit
) {
    var showTooltip by remember { mutableStateOf(false) }

    Box {
        NexaraGlassCard(
            onClick = { showTooltip = !showTooltip },
            shape = RoundedCornerShape(50),
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ContextCircularIndicator(
                    progress = (state.used.toFloat() / state.max.toFloat()).coerceIn(0f, 1f),
                    color = if (state.used > state.max * 0.8) NexaraColors.StatusWarning else NexaraColors.StatusSuccess,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "${state.used / 1000}K / ${state.max / 1000}K",
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = NexaraColors.OnSurface
                )
            }
        }

        if (showTooltip) {
            MaterialTheme(
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(24.dp))
            ) {
                DropdownMenu(
                    expanded = showTooltip,
                    onDismissRequest = { showTooltip = false },
                    offset = DpOffset(x = (-60).dp, y = (-8).dp),
                    modifier = Modifier.background(Color.Transparent).width(220.dp)
                ) {
                    NexaraGlassCard(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.chat_context_usage_title), style = NexaraTypography.titleSmall, color = NexaraColors.Primary)
                            Spacer(modifier = Modifier.height(10.dp))
                            TokenDetailRow(stringResource(R.string.chat_context_label_system), state.systemTokens)
                            TokenDetailRow(stringResource(R.string.chat_context_label_summary), state.summaryTokens)
                            TokenDetailRow(stringResource(R.string.chat_context_label_active), state.activeTokens)
                            TokenDetailRow(stringResource(R.string.chat_context_label_rag), state.ragTokens)
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = NexaraColors.OutlineVariant.copy(alpha = 0.3f))
                            
                            Button(
                                onClick = {
                                    onManualSummary()
                                    showTooltip = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Primary),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(stringResource(R.string.chat_context_btn_compress), style = NexaraTypography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenDetailRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = NexaraTypography.bodySmall, color = NexaraColors.OnSurfaceVariant)
        Text("$value", style = NexaraTypography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = NexaraColors.OnSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onWorkspace: () -> Unit,
    onSettings: () -> Unit,
    onSessionPrompt: () -> Unit = {},
    onClearHistory: () -> Unit,
    onRename: () -> Unit,
    onDeleteSession: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    TopAppBar(
        title = {
            Column {
                Text(title, style = NexaraTypography.titleMedium, color = NexaraColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        navigationIcon = {
            NexaraBackButton(onClick = onBack)
        },
        actions = {
            IconButton(onClick = onWorkspace) {
                Icon(
                    Icons.Rounded.Folder,
                    stringResource(R.string.chat_cd_workspace),
                    tint = NexaraColors.OnSurface,
                )
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag(UiTags.CHAT_OPTIONS),
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        stringResource(R.string.chat_cd_options),
                        tint = NexaraColors.OnSurface,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(NexaraColors.SurfaceContainer)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_session_settings), style = NexaraTypography.labelMedium) },
                        modifier = Modifier.testTag(UiTags.CHAT_SESSION_SETTINGS),
                        onClick = {
                            showMenu = false
                            onSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_session_prompt_title), style = NexaraTypography.labelMedium) },
                        onClick = {
                            showMenu = false
                            onSessionPrompt()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_clear_history), style = NexaraTypography.labelMedium) },
                        onClick = {
                            showMenu = false
                            onClearHistory()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = NexaraColors.OutlineVariant.copy(alpha = 0.3f)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_rename), style = NexaraTypography.labelMedium) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_delete_session), style = NexaraTypography.labelMedium, color = NexaraColors.Error) },
                        onClick = {
                            showMenu = false
                            onDeleteSession()
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    
    Dialog(onDismissRequest = onDismiss) {
        NexaraGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.chat_dialog_rename_title), style = NexaraTypography.titleMedium, color = NexaraColors.OnSurface)
                
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexaraColors.SurfaceLowest, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                    cursorBrush = SolidColor(NexaraColors.Primary),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(stringResource(R.string.chat_dialog_rename_placeholder), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f))
                        }
                        innerTextField()
                    }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_btn_cancel), color = NexaraColors.OnSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(text) },
                        colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Primary)
                    ) {
                        Text(stringResource(R.string.common_btn_confirm))
                    }
                }
            }
        }
    }
}




@Composable
fun ChatInputBar(
    text: String,
    placeholder: String = "",
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    status: GenerationStatus = GenerationStatus.IDLE,
    onStop: () -> Unit = {},
    isModelSelected: Boolean = true,
    onModelHint: () -> Unit = {},
    onPickImage: () -> Unit = {},
    hasImages: Boolean = false
) {
    val isGenerating = status != GenerationStatus.IDLE
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = NexaraShapes.extraLarge as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPickImage,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                enabled = !isGenerating
            ) {
                Icon(
                    Icons.Rounded.AddPhotoAlternate,
                    stringResource(R.string.chat_cd_add_image),
                    tint = if (hasImages) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .then(
                        if (placeholder.isNotBlank()) {
                            Modifier.semantics { contentDescription = placeholder }
                        } else {
                            Modifier
                        }
                    )
                    .testTag(UiTags.CHAT_INPUT),
                textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnBackground),
                cursorBrush = SolidColor(NexaraColors.Primary),
                enabled = !isGenerating,
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = NexaraTypography.bodyMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )

            GenerationStatusButton(
                status = status,
                onSend = {
                    if (isModelSelected) onSend() else onModelHint()
                },
                onStop = onStop,
                enabled = text.isNotBlank() || hasImages,
                isModelSelected = isModelSelected
            )
        }
    }
}





@Composable
private fun GenerationStatusButton(
    status: GenerationStatus,
    onSend: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
    isModelSelected: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gen_status")
    
    // Track last active status to show where error occurred
    var lastActiveStatus by remember { mutableStateOf(GenerationStatus.IDLE) }
    LaunchedEffect(status) {
        if (status != GenerationStatus.ERROR && status != GenerationStatus.IDLE && status != GenerationStatus.COMPLETED) {
            lastActiveStatus = status
        }
    }

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val errorFlash by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "error_flash"
    )

    val shakeOffset by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(50, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shake"
    )

    val containerColor by animateColorAsState(
        when (status) {
            GenerationStatus.IDLE -> {
                if (enabled) {
                    if (isModelSelected) NexaraColors.Primary else NexaraColors.SurfaceHighest
                } else {
                    NexaraColors.SurfaceHighest
                }
            }
            GenerationStatus.UPLOADING -> NexaraColors.Primary.copy(alpha = pulseAlpha)
            GenerationStatus.THINKING -> NexaraColors.Primary
            GenerationStatus.RECEIVING -> NexaraColors.Error
            GenerationStatus.COMPLETED -> NexaraColors.StatusSuccess
            GenerationStatus.ERROR -> NexaraColors.Error
        },
        label = "container_color"
    )

    val contentColor by animateColorAsState(
        when (status) {
            GenerationStatus.IDLE -> {
                if (enabled) {
                    if (isModelSelected) NexaraColors.OnPrimary else NexaraColors.OnSurfaceVariant
                } else {
                    NexaraColors.OnSurfaceVariant
                }
            }
            GenerationStatus.UPLOADING -> NexaraColors.OnPrimary
            GenerationStatus.THINKING -> NexaraColors.OnPrimary
            GenerationStatus.RECEIVING -> Color.White
            GenerationStatus.COMPLETED -> Color.White
            GenerationStatus.ERROR -> Color.White
        },
        label = "content_color"
    )

    fun getIconForStatus(s: GenerationStatus) = when (s) {
        GenerationStatus.IDLE -> Icons.Rounded.ArrowUpward
        GenerationStatus.UPLOADING -> Icons.Rounded.CloudUpload
        GenerationStatus.THINKING -> Icons.Rounded.HourglassEmpty
        GenerationStatus.RECEIVING -> Icons.Rounded.Close
        GenerationStatus.COMPLETED -> Icons.Rounded.Check
        GenerationStatus.ERROR -> Icons.Rounded.ErrorOutline
    }

    val icon = if (status == GenerationStatus.ERROR) {
        if (errorFlash > 0.5f) Icons.Rounded.ErrorOutline else getIconForStatus(lastActiveStatus)
    } else {
        getIconForStatus(status)
    }
    val statusDescription = stringResource(
        when (status) {
            GenerationStatus.IDLE -> R.string.chat_status_ready
            GenerationStatus.UPLOADING -> R.string.chat_status_uploading
            GenerationStatus.THINKING -> R.string.chat_status_thinking
            GenerationStatus.RECEIVING -> R.string.chat_status_receiving
            GenerationStatus.COMPLETED -> R.string.chat_status_completed
            GenerationStatus.ERROR -> R.string.chat_status_error
        }
    )
    val actionDescription = when (status) {
        GenerationStatus.IDLE -> stringResource(R.string.chat_cd_send)
        GenerationStatus.UPLOADING,
        GenerationStatus.THINKING,
        GenerationStatus.RECEIVING -> stringResource(R.string.chat_cd_stop)
        GenerationStatus.COMPLETED,
        GenerationStatus.ERROR -> statusDescription
    }

    IconButton(
        onClick = {
            if (status == GenerationStatus.IDLE) onSend()
            else if (status != GenerationStatus.COMPLETED && status != GenerationStatus.ERROR) onStop()
        },
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .offset { IntOffset(x = if (status == GenerationStatus.ERROR) (shakeOffset.dp).roundToPx() else 0, y = 0) }
            .clip(CircleShape)
            .background(containerColor)
            .semantics {
                stateDescription = statusDescription
                liveRegion = if (status == GenerationStatus.ERROR) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            }
            .testTag(UiTags.CHAT_GENERATION_ACTION),
        enabled = (status == GenerationStatus.IDLE || status == GenerationStatus.RECEIVING || status == GenerationStatus.THINKING || status == GenerationStatus.UPLOADING)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = actionDescription,
            tint = contentColor,
            modifier = Modifier
                .size(20.dp)
                .then(
                    if (status == GenerationStatus.THINKING || (status == GenerationStatus.ERROR && lastActiveStatus == GenerationStatus.THINKING)) 
                        Modifier.rotate(rotation)
                    else Modifier
                )
        )
    }
}

@Composable
fun ChatSkeleton(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Mock User Message
        Column(modifier = Modifier.align(Alignment.End), horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .size(width = 160.dp, height = 48.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(NexaraColors.SurfaceHigh.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 12.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.SurfaceHigh.copy(alpha = alpha * 0.5f))
            )
        }

        // Mock AI Message 1
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.SurfaceVariant.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.SurfaceVariant.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.SurfaceVariant.copy(alpha = alpha))
            )
        }

        // Mock AI Message 2 (Thinking + Content)
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.Primary.copy(alpha = alpha * 0.2f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.SurfaceVariant.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.SurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

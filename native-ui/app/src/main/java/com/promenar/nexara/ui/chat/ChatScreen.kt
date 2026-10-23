package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import com.promenar.nexara.ui.common.ModelBrandIcon
import com.promenar.nexara.ui.common.NexaraBackButton
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.ui.chat.components.TaskFloatingPanel
import com.promenar.nexara.ui.chat.components.TaskPanelSizeSpring
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.chat.components.TaskStatusDot
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraSnackbarData
import com.promenar.nexara.ui.common.NexaraSnackbarHost
import com.promenar.nexara.ui.theme.NexaraElevation
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.forInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 未配置嵌入模型或无检索范围的历史轨迹表示检索未执行，不单独构成检索步骤。 */
internal fun Message.hasRagArtifacts(): Boolean =
    !ragReferences.isNullOrEmpty() || !kgPaths.isNullOrEmpty() ||
        !citations.isNullOrEmpty() || ragReferencesLoading ||
        ragMetadata?.trace?.takeUnless { it.isSkipped } != null

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

/**
 * 空会话粒子字标的显示条件：会话已就位，且没有任何消息、审批、加载或生成。
 * 进入会话的首帧会话对象尚未载入（消息同样为空、也未标记加载），必须排除，
 * 否则非空会话打开时字标会先淡入再随消息到达淡出。
 */
internal fun shouldShowEmptyWordmark(uiState: ChatUiState): Boolean =
    uiState.session != null && !uiState.isLoading && uiState.messages.isEmpty() &&
        uiState.approvalRequest == null && !uiState.isGenerating

data class ChatScreenState(
    val uiState: ChatUiState = ChatUiState(),
    val inputText: String = "",
    val tokenState: ChatViewModel.TokenIndicatorState = ChatViewModel.TokenIndicatorState(),
    val ragPhases: List<com.promenar.nexara.data.model.RagPhase> = emptyList(),
    val compressionState: ChatViewModel.CompressionState = ChatViewModel.CompressionState(),
    val postProcessTasks: List<PostProcessTask> = emptyList(),
    val generationRetry: com.promenar.nexara.domain.generation.GenerationRetry? = null,
    /** 等待内置 Tailscale 接入时的连接浮窗内容。 */
    val tailnetWait: com.promenar.nexara.data.generation.TailnetWaitState? = null,
    val selectedImageUris: List<android.net.Uri> = emptyList(),
    val draftDocuments: List<MessageDocumentAttachment> = emptyList(),
    val isImportingDocument: Boolean = false,
    val modelDisplayNames: Map<String, String> = emptyMap(),
    /** 输入区推理强度胶囊；当前模型不支持调节时为 null。 */
    val reasoningControl: ChatReasoningControl? = null,
    /** 服务商 ID → 显示名，供生成统计浮卡使用。 */
    val providerDisplayNames: Map<String, String> = emptyMap(),
    val taskPanel: TaskPanelUiState? = null,
    val generationProgress: com.promenar.nexara.domain.generation.GenerationProgress? = null,
    /** 外观页“完成后折叠过程”，由路由层从外观偏好读取后传入。 */
    val collapseFinishedTurns: Boolean = true,
    /** 当前会话各轮的图谱抽取状态，键为该轮最后一条助手消息。 */
    val turnGraphStates: Map<String, com.promenar.nexara.data.rag.TurnGraphState> = emptyMap(),
    /** 生成中按发送键排队的后续消息。 */
    val composerQueue: ComposerQueueUiState = ComposerQueueUiState(),
    /** 本次生成是否保持亮屏；为 null 时浮岛不显示亮屏开关。 */
    val keepScreenOn: Boolean? = null,
)

data class ChatScreenActions(
    val onNavigateBack: () -> Unit = {},
    val onOpenWorkspace: () -> Unit = {},
    /** 打开资源管理器并定位到给定的工作区虚拟目录（媒体不可用占位的“在资源管理器中定位”）。 */
    val onLocateWorkspacePath: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenPromptEditor: () -> Unit = {},
    val onOpenClearDialog: () -> Unit = {},
    val onOpenRenameDialog: () -> Unit = {},
    val onOpenDeleteDialog: () -> Unit = {},
    val onContentChange: (String, String) -> Unit = { _, _ -> },
    val onCopy: (String) -> Unit = {},
    val onDeleteMessage: (String) -> Unit = {},
    val onRegenerateMessage: (String) -> Unit = {},
    /** 最新一轮失败后直接重发（tailnet 失败的“重试”）。 */
    val onRetryLastMessage: () -> Unit = {},
    /** 打开 Tailscale 设置；为 null 时失败提示不显示设置与登录入口。 */
    val onOpenTailscaleSettings: (() -> Unit)? = null,
    val onBranchMessage: (String) -> Unit = {},
    /** 打开某轮回复的“本轮记忆”面板，参数为记忆状态所在的助手消息 ID。 */
    val onOpenTurnMemory: (String) -> Unit = {},
    val onApprove: (com.promenar.nexara.data.model.ApprovalRequest) -> Unit = {},
    val onApproveAll: (com.promenar.nexara.data.model.ApprovalRequest) -> Unit = {},
    val onAlwaysAllowTarget: (com.promenar.nexara.data.model.ApprovalRequest) -> Unit = {},
    val onDecline: (com.promenar.nexara.data.model.ApprovalRequest) -> Unit = {},
    val onRemovePostProcessTask: (String) -> Unit = {},
    val onManualSummary: () -> Unit = {},
    val onReasoningEffortChange: (com.promenar.nexara.data.model.params.ReasoningEffort?) -> Unit = {},
    val onDismissCompressionResult: () -> Unit = {},
    val onTextChange: (String) -> Unit = {},
    val onSend: (String, List<android.net.Uri>) -> Unit = { _, _ -> },
    val onStop: () -> Unit = {},
    val onDismissGenerationError: () -> Unit = {},
    val onPickImages: () -> Unit = {},
    val onPickDocuments: () -> Unit = {},
    val onRemoveImage: (Int) -> Unit = {},
    val onRemoveDocument: (String) -> Unit = {},
    val onSnackbarAction: () -> Unit = {},
    val onContinueTask: () -> Unit = {},
    val onCompleteTask: () -> Unit = {},
    val onClearCompletedTask: () -> Unit = {},
    val onSteerQueued: (String) -> Unit = {},
    val onEditQueued: (String) -> Unit = {},
    val onMoveQueuedToFront: (String) -> Unit = {},
    val onRemoveQueued: (String) -> Unit = {},
    val onResumeQueue: () -> Unit = {},
    val onToggleKeepScreenOn: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreenContent(
    state: ChatScreenState,
    actions: ChatScreenActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    snackbarData: NexaraSnackbarData? = null,
    attachmentMenuExpandedState: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    val uiState = state.uiState
    val inputText = state.inputText
    val tokenState = state.tokenState
    val ragPhases = state.ragPhases
    val compressionState = state.compressionState
    val selectedImageUris = state.selectedImageUris
    val draftDocuments = state.draftDocuments
    val listState = rememberLazyListState()
    var showModelHint by remember { mutableStateOf(false) }
    var attachmentMenuExpanded by attachmentMenuExpandedState
    var chatRootBounds by remember { mutableStateOf(Rect.Zero) }
    var composerTopInRoot by remember { mutableStateOf(0f) }
    var attachmentAnchorInRoot by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(showModelHint) {
        if (showModelHint) {
            delay(2500)
            showModelHint = false
        }
    }

    // 生成中也可以添加附件（随排队消息一起发出）；只有导入文档期间关闭菜单。
    LaunchedEffect(state.isImportingDocument) {
        if (state.isImportingDocument) {
            attachmentMenuExpanded = false
        }
    }

    val sessionTitle = uiState.session?.title ?: stringResource(R.string.chat_title_new)
    val showTopBarDivider by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val agentName = uiState.agentName

    val pipelineGroups = remember(uiState.messages) { buildPipelineGroups(uiState.messages) }
    val collapseFinishedTurns = state.collapseFinishedTurns

    val density = LocalDensity.current
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val currentComposerHeightPx by rememberUpdatedState(composerHeightPx)
    val composerHeight = with(density) { composerHeightPx.toDp() }
    val composerInsets = chatComposerInsets(composerHeight)
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
    val documentOnlyPrompt = stringResource(R.string.chat_document_only_prompt)

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

    suspend fun scrollItemAboveComposer(activeIndex: Int) {
        if (activeIndex < 0 || !autoFollowEnabled) return

        val liveComposerInsets = chatComposerInsets(
            with(density) { currentComposerHeightPx.toDp() },
        )
        val inputOverlapPx = with(density) { liveComposerInsets.streamingOverlap.roundToPx() }

        // 基于最新一次布局读取目标 item，做像素级遮挡校正。
        // 目标 item 不在组成窗口内时直接返回，由调用方先粗滚再重新校正。
        suspend fun correctActiveOverlap() {
            if (!autoFollowEnabled) return
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

    suspend fun scrollToStreamingTail() = scrollItemAboveComposer(pipelineGroups.lastIndex)

    // 新用户消息 → 恢复追踪 + 滚到底部
    val latestUserMsgId = uiState.messages.lastOrNull { it.role == MessageRole.USER }?.id ?: ""
    LaunchedEffect(latestUserMsgId) {
        if (latestUserMsgId.isNotEmpty()) {
            autoFollowEnabled = true
            delay(32)
            scrollToStreamingTail()
        }
    }

    // 新的审批请求排在消息之后，自动跟随只对齐最后一条消息时它会落在输入栏与键盘下方；
    // 出现时把审批卡滚到输入栏上方，确保批准与拒绝按钮可见可点。
    val approvalIdentity = uiState.approvalRequest?.let { it.identityHash ?: it.hashCode().toString() }
    val showCompressionFeedback = compressionState.isCompressing ||
        compressionState.result != null || compressionState.detail.isNotBlank()
    val compressionItemIndex = pipelineGroups.size
    val approvalItemIndex = compressionItemIndex + (if (showCompressionFeedback) 1 else 0)
    LaunchedEffect(showCompressionFeedback, compressionState.isCompressing, compressionState.result) {
        if (!showCompressionFeedback) return@LaunchedEffect
        withFrameNanos { }
        scrollItemAboveComposer(compressionItemIndex)
    }
    LaunchedEffect(approvalIdentity) {
        if (approvalIdentity == null) return@LaunchedEffect
        withFrameNanos { }
        scrollItemAboveComposer(approvalItemIndex)
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
        ragPhases.size,
        composerHeightPx,
    ) {
        if (uiState.isGenerating && autoFollowEnabled) {
            scrollToStreamingTail()
        }
    }

    // 输入区首次测量、多行输入与 IME 动画都会改变尾部可见范围。
    // 同时观察目标条目的位置与高度，覆盖流结束后的操作行、积压放出与异步解析。
    val imeInsets = WindowInsets.ime
    val followTargetIndex = when {
        approvalIdentity != null -> approvalItemIndex
        showCompressionFeedback -> compressionItemIndex
        else -> pipelineGroups.lastIndex
    }
    LaunchedEffect(autoFollowEnabled, followTargetIndex, approvalIdentity) {
        if (!autoFollowEnabled || followTargetIndex < 0) return@LaunchedEffect

        snapshotFlow {
            val layout = listState.layoutInfo
            val target = layout.visibleItemsInfo.firstOrNull { it.index == followTargetIndex }
            listOf(
                currentComposerHeightPx, imeInsets.getBottom(density), layout.viewportEndOffset,
                layout.afterContentPadding, layout.totalItemsCount,
                target?.offset ?: Int.MIN_VALUE, target?.size ?: 0,
            )
        }.collectLatest { geometry ->
            if (geometry[0] <= 0 || geometry[2] <= 0 || geometry[4] == 0) return@collectLatest
            scrollItemAboveComposer(followTargetIndex)
        }
    }

    // 会话内工作区媒体：解析环境、操作提示与播放协调（会话切换时暂停）。
    val mediaSnackbarHostState = remember { SnackbarHostState() }
    val mediaMessenger = com.promenar.nexara.ui.media.rememberSnackbarMediaMessenger(mediaSnackbarHostState)
    val workspaceMedia = com.promenar.nexara.ui.media.rememberSessionWorkspaceMedia(
        sessionId = uiState.session?.id,
        onLocate = actions.onLocateWorkspacePath,
    )
    CompositionLocalProvider(
        com.promenar.nexara.ui.media.LocalWorkspaceMedia provides workspaceMedia,
        com.promenar.nexara.ui.media.LocalMediaMessenger provides mediaMessenger,
    ) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { chatRootBounds = it.boundsInRoot() }
            .testTag(UiTags.CHAT_ROOT),
    ) {
        val rootWidthPx = with(density) { maxWidth.toPx() }
        val rootHeightPx = with(density) { maxHeight.toPx() }
        val fallbackAnchorWidth = with(density) { 48.dp.toPx() }
        val fallbackMargin = with(density) { 16.dp.toPx() }
        val fallbackAnchorTop = rootHeightPx - with(density) { 72.dp.toPx() }
        // 状态恢复或静态预览可能先于首轮布局展开菜单；实测坐标到达前先约束在根布局内。
        val fallbackAnchorLeft = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
            fallbackMargin
        } else {
            rootWidthPx - fallbackMargin - fallbackAnchorWidth
        }
        val fallbackAnchorBounds = Rect(
            left = fallbackAnchorLeft,
            top = fallbackAnchorTop,
            right = fallbackAnchorLeft + fallbackAnchorWidth,
            bottom = fallbackAnchorTop + fallbackAnchorWidth,
        )
        val fallbackComposerReserve = if (density.fontScale >= 1.8f) 176.dp else 136.dp
        val fallbackMaximumBottom = rootHeightPx - with(density) { fallbackComposerReserve.toPx() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
              Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                ChatTopBar(
                    title = sessionTitle,
                    showDivider = showTopBarDivider,
                    onBack = actions.onNavigateBack,
                    onWorkspace = actions.onOpenWorkspace,
                    onSettings = actions.onOpenSettings,
                    onSessionPrompt = actions.onOpenPromptEditor,
                    onClearHistory = actions.onOpenClearDialog,
                    onRename = actions.onOpenRenameDialog,
                    onDeleteSession = actions.onOpenDeleteDialog,
                )
              }
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
                    com.promenar.nexara.ui.media.MediaSnackbarHost(hostState = mediaSnackbarHostState)
                }
            }
        ) { padding ->
            val wordmarkPointer = remember { WordmarkPointer() }
            val showWordmark = shouldShowEmptyWordmark(uiState)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .then(if (showWordmark) Modifier.trackWordmarkPointer(wordmarkPointer) else Modifier),
            ) {
            // 空会话的粒子字标画在列表下层，不拦截触摸；有了第一条消息就淡出并离开组合，粒子循环随之停止。
            AnimatedVisibility(
                visible = showWordmark,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(500)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .widthIn(max = NexaraSpacing.ChatMaxWidth)
                    .fillMaxWidth()
                    .padding(bottom = with(density) { composerHeightPx.toDp() }),
            ) {
                EmptyChatWordmark(dispersed = inputText.isNotEmpty(), pointer = wordmarkPointer)
            }
            val renderStateTag = chatRenderStateTag(uiState)
            // 生成中多留一段底部空间：增长中的思考与工具块在跟随校正前也不贴着输入区；结束后平滑收回。
            val generatingExtraBottom by animateDpAsState(
                targetValue = if (uiState.isGenerating) ChatGeneratingExtraBottom else 0.dp,
                label = "chat_generating_bottom",
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .widthIn(max = NexaraSpacing.ChatMaxWidth)
                    .fillMaxWidth()
                    .nestedScroll(userScrollConnection)
                    .testTag(renderStateTag),
                contentPadding = PaddingValues(
                    start = NexaraSpacing.ChatHorizontal,
                    end = NexaraSpacing.ChatHorizontal,
                    bottom = composerInsets.contentBottom + generatingExtraBottom,
                ),
            ) {
                // pipelineGroups 已在外部通过 remember 计算，此处直接引用
                items(pipelineGroups.size, key = { pipelineGroups[it].messages.first().id }) { idx ->
                    val group = pipelineGroups[idx]
                    val isGeneratingGroup = idx == pipelineGroups.lastIndex && uiState.isGenerating
                    val hasPendingApproval = groupHasPendingApproval(
                        group = group,
                        request = uiState.approvalRequest,
                        isLastGroup = idx == pipelineGroups.lastIndex,
                    )

                    val itemKind = if (group.isUser) ChatItemKind.USER else ChatItemKind.ASSISTANT
                    val previousKind = pipelineGroups.getOrNull(idx - 1)?.let {
                        if (it.isUser) ChatItemKind.USER else ChatItemKind.ASSISTANT
                    }
                    val spacing = chatItemSpacing(previousKind, itemKind)
                    val hasRagContent = selectRagActiveMessage(group.assistantMessages)?.let {
                        (if (isGeneratingGroup) ragPhases else emptyList()).isNotEmpty() || it.hasRagArtifacts()
                    } == true
                    PipelineBubble(
                        hasRagContent = hasRagContent,
                        // 新消息只淡入、不做位移动画：位移会与生成中按像素跟随滚动的逻辑互相拉扯。
                        modifier = Modifier
                            .animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = null)
                            .padding(top = spacing.beforeDp.dp),
                        ragContent = {
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
                                        isComplete = if (isGeneratingGroup) (ragComplete || !ragLoading) else true,
                                        trace = ragActiveMsg.ragMetadata?.trace,
                                    )
                                }
                            }
                        },
                        group = group,
                        isGenerating = isGeneratingGroup,
                        collapseFinishedTurn = collapseFinishedTurns,
                        hasPendingApproval = hasPendingApproval,
                        interruptedByError = groupInterruptedByError(pipelineGroups, idx),
                        status = uiState.status,
                        streamingContent = uiState.streamingContent,
                        fontSize = uiState.session?.options?.fontSize ?: 15,
                        modelDisplayNames = state.modelDisplayNames,
                        providerDisplayNames = state.providerDisplayNames,
                        onMessageContentChange = actions.onContentChange,
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
                        onBranch = actions.onBranchMessage,
                        onOpenTurnMemory = actions.onOpenTurnMemory.takeIf { uiState.session?.let { (it.ragOptions ?: RagOptions()).enableMemory } == true },
                        turnGraphStates = state.turnGraphStates,
                        failureActions = if (idx == pipelineGroups.lastIndex && !uiState.isGenerating) {
                            GenerationFailureActions(
                                onRetry = actions.onRetryLastMessage,
                                onSwitchModel = actions.onOpenSettings,
                                onOpenTailscaleSettings = actions.onOpenTailscaleSettings,
                            )
                        } else {
                            null
                        },
                    )
                }

                if (showCompressionFeedback) {
                    item(key = "summary_card") {
                        Box(Modifier.animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = ChatItemFadeOut)) {
                            ContextCompactionFeedback(
                                state = compressionState,
                                onDismiss = actions.onDismissCompressionResult,
                            )
                        }
                    }
                }

                uiState.approvalRequest?.let { request ->
                    item(key = "approval_request") {
                        ChatApprovalLiveRegion(Modifier.animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = ChatItemFadeOut)) {
                            ApprovalCard(
                                toolName = request.calls.firstOrNull()?.toolName
                                    ?: request.toolName
                                    ?: stringResource(R.string.chat_approval_unknown_tool),
                                description = request.reason.orEmpty(),
                                calls = request.calls.map { call ->
                                    ApprovalDisplayCall(
                                        name = call.toolName,
                                        argumentsSummary = call.argumentsSummary,
                                        riskLabel = approvalRiskLabel(call.risk),
                                        executionTarget = call.executionTarget,
                                        highRisk = call.risk in setOf("delete", "process"),
                                        preview = remember(call, request.assistantMessageId, uiState.messages) {
                                            ApprovalPreview.forCall(call, uiState.messages, request.assistantMessageId)
                                        },
                                        diffPreview = com.promenar.nexara.ui.chat.diff.rememberApprovalDiffPreview(
                                            call = call,
                                            messages = uiState.messages,
                                            assistantMessageId = request.assistantMessageId,
                                            sessionId = uiState.session?.id,
                                            privateWorkspaceRootUuid = uiState.session?.workspaceRootUuid,
                                        ),
                                    )
                                }.ifEmpty {
                                    listOf(ApprovalDisplayCall(
                                        name = request.toolName ?: stringResource(R.string.chat_approval_unknown_tool),
                                        argumentsSummary = request.args.orEmpty(),
                                        riskLabel = approvalRiskLabel(request.type.orEmpty()),
                                    ))
                                },
                                enabled = !uiState.isApprovalSubmitting,
                                onApprove = { actions.onApprove(request) },
                                onApproveAll = { actions.onApproveAll(request) },
                                onAlwaysAllow = { actions.onAlwaysAllowTarget(request) },
                                onDecline = { actions.onDecline(request) },
                            )
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(chatItemSpacing(
                        previous = null, current = ChatItemKind.ASSISTANT, isLast = true,
                    ).afterDp.dp))
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
                    .widthIn(max = NexaraSpacing.ChatMaxWidth)
                    .fillMaxWidth()
                    .padding(bottom = 160.dp)
                    .testTag(UiTags.CHAT_LOADING_SKELETON)
            ) {
                ChatSkeleton(modifier = Modifier.fillMaxSize())
            }

            TailnetConnectOverlay(
                wait = state.tailnetWait,
                onCancel = actions.onStop,
                bottomInset = with(density) { composerHeightPx.toDp() },
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = NexaraSpacing.ChatMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
                    .onGloballyPositioned { composerTopInRoot = it.boundsInRoot().top }
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp)
                    .testTag(UiTags.CHAT_COMPOSER),
            ) {
                val modelDisplayName = uiState.session?.modelId
                    ?.let(state.modelDisplayNames::get)
                    .orEmpty()
                val taskPanel = state.taskPanel
                var hiddenPlanSignature by rememberSaveable(uiState.session?.id) { mutableStateOf<String?>(null) }
                var planObservedIncomplete by rememberSaveable(uiState.session?.id, taskPanel?.planId) {
                    mutableStateOf(false)
                }
                val autoHideDelay = completedPlanAutoHideDelay(taskPanel, uiState.isGenerating, planObservedIncomplete)
                // 打开会话时就已完成的计划不再出现；本次看着完成的计划停留片刻后自动收起。
                val planBarVisible = isTaskPlanBarVisible(taskPanel, hiddenPlanSignature) && autoHideDelay != 0L
                // 退场动画期间计划已不再可见，仍用最后一次可见的状态绘制，避免内容瞬间消失。
                var lastVisiblePlan by remember(uiState.session?.id) { mutableStateOf<TaskPanelUiState?>(null) }
                if (taskPanel != null && planBarVisible) lastVisiblePlan = taskPanel
                LaunchedEffect(taskPanel?.visibilitySignature(), autoHideDelay) {
                    if (taskPanel?.isCompleted == false) planObservedIncomplete = true
                    val delayMs = autoHideDelay?.takeIf { it > 0 } ?: return@LaunchedEffect
                    delay(delayMs)
                    hiddenPlanSignature = taskPanel?.visibilitySignature()
                }
                ChatComposerStatusBar(
                    taskPanel = taskPanel.takeUnless { planBarVisible },
                    postProcessTasks = state.postProcessTasks,
                    retry = state.generationRetry,
                    onRemovePostProcessTask = actions.onRemovePostProcessTask,
                    onTaskClick = { hiddenPlanSignature = null },
                )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ChatInputBar(
                            text = inputText,
                            placeholder = if (agentName.isNotBlank()) {
                                stringResource(R.string.chat_input_placeholder, agentName)
                            } else {
                                stringResource(R.string.chat_input_placeholder_default)
                            },
                            onTextChange = { next ->
                                if (uiState.status == GenerationStatus.ERROR) {
                                    actions.onDismissGenerationError()
                                }
                                actions.onTextChange(next)
                            },
                            onSend = {
                                if (
                                    inputText.isNotBlank() ||
                                    selectedImageUris.isNotEmpty() ||
                                    draftDocuments.isNotEmpty()
                                ) {
                                    val fallback = if (draftDocuments.isNotEmpty()) {
                                        documentOnlyPrompt
                                    } else {
                                        describeImagePrompt
                                    }
                                    val textToSend = inputText.ifBlank { fallback }
                                    actions.onSend(textToSend, selectedImageUris)
                                }
                            },
                            status = uiState.status,
                            isGenerating = uiState.isGenerating,
                            onStop = actions.onStop,
                            onDismissError = actions.onDismissGenerationError,
                            isModelSelected = uiState.session?.modelId?.isNotBlank() == true,
                            onModelHint = { showModelHint = true },
                            hasImages = selectedImageUris.isNotEmpty(),
                            hasDocuments = draftDocuments.isNotEmpty(),
                            isImportingDocument = state.isImportingDocument,
                            attachmentMenuExpanded = attachmentMenuExpanded,
                            onAttachmentMenuExpandedChange = { attachmentMenuExpanded = it },
                            onAttachmentAnchorBoundsChange = { attachmentAnchorInRoot = it },
                            topContent = {
                                // 生成中的活动统计并入浮岛顶部：有计划时替换计划收起行，没有计划时单独占一行。
                                val activity = if (uiState.isGenerating) state.generationProgress ?: GenerationProgress() else null
                                var lastActivity by remember { mutableStateOf(GenerationProgress()) }
                                if (activity != null) lastActivity = activity
                                AnimatedVisibility(
                                    visible = activity != null && !(taskPanel != null && planBarVisible),
                                    enter = fadeIn(tween(220, delayMillis = 60)) +
                                        expandVertically(TaskPanelSizeSpring, expandFrom = Alignment.Bottom),
                                    exit = fadeOut(tween(160)) +
                                        shrinkVertically(TaskPanelSizeSpring, shrinkTowards = Alignment.Bottom),
                                ) {
                                    GenerationActivityRow(
                                        progress = lastActivity,
                                        keepScreenOn = state.keepScreenOn,
                                        onToggleKeepScreenOn = actions.onToggleKeepScreenOn,
                                    )
                                }
                                AnimatedVisibility(
                                    visible = taskPanel != null && planBarVisible,
                                    enter = fadeIn(tween(220, delayMillis = 60)) +
                                        expandVertically(TaskPanelSizeSpring, expandFrom = Alignment.Bottom),
                                    exit = fadeOut(tween(160)) +
                                        shrinkVertically(TaskPanelSizeSpring, shrinkTowards = Alignment.Bottom),
                                ) {
                                    lastVisiblePlan?.let { plan ->
                                        TaskFloatingPanel(
                                            state = plan,
                                            isGenerating = uiState.isGenerating,
                                            generationProgress = activity,
                                            keepScreenOn = state.keepScreenOn,
                                            onToggleKeepScreenOn = actions.onToggleKeepScreenOn,
                                            onContinue = actions.onContinueTask,
                                            onComplete = actions.onCompleteTask,
                                            onClose = {
                                                if (plan.isCompleted) {
                                                    actions.onClearCompletedTask()
                                                } else {
                                                    hiddenPlanSignature = plan.visibilitySignature()
                                                }
                                            },
                                        )
                                    }
                                }
                                // 排队托盘紧贴输入区：自下而上是正在写的、排着的、正在运行的。
                                val queue = state.composerQueue
                                var lastQueue by remember { mutableStateOf(queue) }
                                if (queue.items.isNotEmpty()) lastQueue = queue
                                AnimatedVisibility(
                                    visible = queue.items.isNotEmpty(),
                                    enter = fadeIn(tween(220, delayMillis = 60)) +
                                        expandVertically(TaskPanelSizeSpring, expandFrom = Alignment.Bottom),
                                    exit = fadeOut(tween(160)) +
                                        shrinkVertically(TaskPanelSizeSpring, shrinkTowards = Alignment.Bottom),
                                ) {
                                    ComposerQueueTray(
                                        queue = lastQueue,
                                        canEdit = inputText.isBlank() && selectedImageUris.isEmpty() && draftDocuments.isEmpty(),
                                        onSteer = actions.onSteerQueued,
                                        onEdit = actions.onEditQueued,
                                        onMoveToFront = actions.onMoveQueuedToFront,
                                        onRemove = actions.onRemoveQueued,
                                        onResume = actions.onResumeQueue,
                                    )
                                }
                            },
                            modelName = modelDisplayName,
                            modelId = uiState.session?.modelId,
                            tokenState = tokenState.copy(isGenerating = tokenState.isGenerating || uiState.isGenerating),
                            isCompressing = compressionState.isCompressing,
                            onModelClick = actions.onOpenSettings,
                            onManualSummary = actions.onManualSummary,
                            reasoningControl = state.reasoningControl,
                            onReasoningChange = actions.onReasoningEffortChange,
                            attachmentContent = {
                                ChatComposerAttachments(
                                    imageUris = selectedImageUris,
                                    documents = draftDocuments,
                                    isImporting = state.isImportingDocument,
                                    onRemoveImage = actions.onRemoveImage,
                                    onRemoveDocument = actions.onRemoveDocument,
                                )
                            },
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
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.medium,
                                shadowElevation = NexaraElevation.Level3,
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_hint_select_model),
                                    style = NexaraTypography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(
                                        horizontal = NexaraSpacing.Medium,
                                        vertical = NexaraSpacing.Small,
                                    ),
                                )
                            }
                        }
                    }
            }

            AnimatedVisibility(
                visible = isUserScrolledAway && (!uiState.isGenerating || !autoFollowEnabled),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerInsets.fabBottom)
            ) {
                FloatingActionButton(
                    onClick = {
                        autoFollowEnabled = true
                        scope.launch {
                            scrollToStreamingTail()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
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

        val attachmentPositionsReady = chatRootBounds != Rect.Zero &&
            attachmentAnchorInRoot != Rect.Zero && composerTopInRoot > chatRootBounds.top
        val attachmentAnchorBounds = if (attachmentPositionsReady) {
            Rect(
                left = attachmentAnchorInRoot.left - chatRootBounds.left,
                top = attachmentAnchorInRoot.top - chatRootBounds.top,
                right = attachmentAnchorInRoot.right - chatRootBounds.left,
                bottom = attachmentAnchorInRoot.bottom - chatRootBounds.top,
            )
        } else {
            fallbackAnchorBounds
        }
        AttachmentActionMenu(
            expanded = attachmentMenuExpanded,
            anchorBounds = attachmentAnchorBounds,
            maximumBottom = if (attachmentPositionsReady) {
                composerTopInRoot - chatRootBounds.top
            } else {
                fallbackMaximumBottom
            },
            enabled = !state.isImportingDocument,
            onDismiss = { attachmentMenuExpanded = false },
            onPickImage = actions.onPickImages,
            onPickDocument = actions.onPickDocuments,
        )
    }
    }
}

@Composable
internal fun approvalRiskLabel(risk: String): String = stringResource(
    when (risk) {
        "safe_read" -> R.string.chat_approval_risk_safe_read
        "session_state" -> R.string.chat_approval_risk_session_state
        "file_write" -> R.string.chat_approval_risk_file_write
        "patch" -> R.string.chat_approval_risk_patch
        "delete" -> R.string.chat_approval_risk_delete
        "process" -> R.string.chat_approval_risk_process
        "script" -> R.string.chat_approval_risk_script
        "external_write" -> R.string.chat_approval_risk_external_write
        else -> R.string.chat_approval_risk_unknown
    },
)

/** 消息列表条目的淡入淡出；只用于透明度，不参与位移。 */
private val ChatItemFadeIn = tween<Float>(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)
private val ChatItemFadeOut = tween<Float>(NexaraMotion.DurationShort)

@Composable
internal fun ChatApprovalLiveRegion(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
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

/** 自动重试提示：与任务胶囊同为无底色文字，用强调色显示原因、倒计时与次数；倒计时结束后显示“正在重试”直到新一轮开始输出。 */
@Composable
internal fun GenerationRetryCapsule(retry: com.promenar.nexara.domain.generation.GenerationRetry) {
    var remainingSeconds by remember(retry) { mutableStateOf(((retry.delayMs + 999) / 1_000).toInt()) }
    LaunchedEffect(retry) {
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds--
        }
    }
    val reason = stringResource(
        when (retry.code) {
            com.promenar.nexara.domain.generation.GenerationFailureCode.RATE_LIMIT -> R.string.chat_retry_reason_rate_limit
            com.promenar.nexara.domain.generation.GenerationFailureCode.SERVER,
            com.promenar.nexara.domain.generation.GenerationFailureCode.UNKNOWN -> R.string.chat_retry_reason_server
            else -> R.string.chat_retry_reason_network
        },
    )
    val label = if (remainingSeconds > 0) {
        stringResource(R.string.chat_retry_countdown, reason, remainingSeconds, retry.attempt, retry.maxAttempts)
    } else {
        stringResource(R.string.chat_retry_in_progress, reason, retry.attempt, retry.maxAttempts)
    }
    Row(
        modifier = Modifier.height(28.dp).padding(horizontal = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(UiTags.CHAT_RETRY_CAPSULE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ChatComposerStatusBar(
    taskPanel: TaskPanelUiState?,
    postProcessTasks: List<PostProcessTask>,
    onRemovePostProcessTask: (String) -> Unit = {},
    onTaskClick: () -> Unit = {},
    retry: com.promenar.nexara.domain.generation.GenerationRetry? = null,
) {
    val livePanel = taskPanel?.takeUnless { it.isCompleted }
    val visible = livePanel != null || postProcessTasks.isNotEmpty() || retry != null
    // 整条出现与消失时纵向展开收起；收起期间沿用最后一次的胶囊内容，不在动画中途变空。
    val shown = remember { arrayOfNulls<ComposerStatusContent>(1) }
    if (visible) shown[0] = ComposerStatusContent(livePanel, postProcessTasks, retry)
    AnimatedVisibility(visible = visible, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
        val content = shown[0] ?: return@AnimatedVisibility
        ComposerStatusRow(content, onRemovePostProcessTask, onTaskClick)
    }
}

private data class ComposerStatusContent(
    val panel: TaskPanelUiState?,
    val postProcessTasks: List<PostProcessTask>,
    val retry: com.promenar.nexara.domain.generation.GenerationRetry?,
)

@Composable
private fun ComposerStatusRow(
    content: ComposerStatusContent,
    onRemovePostProcessTask: (String) -> Unit,
    onTaskClick: () -> Unit,
) {
    val panel = content.panel
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .horizontalScroll(rememberScrollState()).height(28.dp)
            .testTag("chat_composer_status_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content.retry?.let { GenerationRetryCapsule(it) }
        panel?.let {
            val generating = it.capsuleMode == TaskCapsuleMode.GENERATING
            val description = if (generating) stringResource(R.string.chat_task_generating)
                else stringResource(R.string.chat_task_pending_accessibility, it.pendingCount)
            Row(
                modifier = Modifier.height(28.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = onTaskClick)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = description }
                    .testTag(UiTags.CHAT_TASK_CAPSULE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TaskStatusDot(
                    pulsing = it.shouldPulse,
                    color = if (generating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = if (generating) stringResource(R.string.chat_task_generating)
                        else stringResource(R.string.chat_task_pending, it.pendingCount),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        content.postProcessTasks.forEach { task ->
            key(task.id) { PostProcessChip(task = task, onRemove = { onRemovePostProcessTask(task.id) }) }
        }
    }
}

@Composable
internal fun ChatModelSelector(
    modelName: String,
    modelId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(40.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.sheet_tab_model), onClick = onClick)
            .testTag(UiTags.CHAT_MODEL_SELECTOR),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).padding(horizontal = 6.dp)
                .testTag("chat_model_capsule"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModelBrandIcon(modelId = modelId, modifier = Modifier.size(16.dp), fallbackTint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = modelName.ifBlank { stringResource(R.string.chat_model_placeholder) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp),
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TokenIndicator(
    state: ChatViewModel.TokenIndicatorState,
    onManualSummary: () -> Unit,
    isCompressing: Boolean,
) {
    var showTooltip by remember { mutableStateOf(false) }

    val progress = (state.used.toFloat() / state.max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val percentage = (state.used.toDouble() / state.max.coerceAtLeast(1) * 100).toInt()
    val description = stringResource(R.string.chat_context_usage_percent, percentage)
    Box {
        Row(
            modifier = Modifier.height(40.dp).clickable(role = Role.Button) { showTooltip = !showTooltip }
                .semantics { contentDescription = description }
                .testTag(UiTags.CHAT_TOKEN_INDICATOR)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ContextCircularIndicator(
                progress = progress,
                color = if (state.used > state.max * state.autoCompactionThreshold) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.chat_context_percentage, percentage),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (showTooltip) {
            DropdownMenu(
                expanded = showTooltip,
                onDismissRequest = { showTooltip = false },
                offset = DpOffset(x = (-60).dp, y = (-8).dp),
                modifier = Modifier.width(300.dp),
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = NexaraElevation.Level2,
            ) {
                ContextUsagePanel(
                    state = state,
                    isCompressing = isCompressing,
                    onManualSummary = {
                        onManualSummary()
                        showTooltip = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun ContextUsagePanel(
    state: ChatViewModel.TokenIndicatorState,
    onManualSummary: () -> Unit,
    modifier: Modifier = Modifier,
    isCompressing: Boolean = false,
) {
    val progress = (state.used.toFloat() / state.max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val threshold = state.autoCompactionThreshold.toFloat().coerceIn(0.5f, 0.95f)
    val thresholdLabel = stringResource(R.string.chat_context_threshold, (threshold * 100).toInt())
    val usageLabel = stringResource(R.string.chat_context_usage_percent, (progress * 100).toInt())
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = if (progress > threshold) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.onSurface
    val compressionUnavailableReason = when {
        isCompressing -> stringResource(R.string.chat_context_compress_unavailable_running)
        state.isGenerating -> stringResource(R.string.chat_context_compress_unavailable_generating)
        else -> null
    }
    Column(
        modifier = modifier.padding(NexaraSpacing.Large).testTag("context_usage_panel"),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Text(
            stringResource(R.string.chat_context_usage_title),
            style = NexaraTypography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(
                R.string.chat_context_usage_total,
                ChatViewModel.formatTokenCount(state.used),
                ChatViewModel.formatTokenCount(state.max),
            ),
            style = NexaraTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Canvas(
            modifier = Modifier.fillMaxWidth().height(NexaraSpacing.Large).semantics {
                contentDescription = thresholdLabel
                stateDescription = usageLabel
            },
        ) {
            val inset = NexaraSpacing.XSmall.toPx()
            val trackWidth = (size.width - inset * 2).coerceAtLeast(0f)
            val trackY = size.height / 2
            val strokeWidth = NexaraSpacing.Small.toPx()
            drawLine(
                color = trackColor,
                start = Offset(inset, trackY),
                end = Offset(inset + trackWidth, trackY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            if (progress > 0f) {
                drawLine(
                    color = progressColor,
                    start = Offset(inset, trackY),
                    end = Offset(inset + trackWidth * progress, trackY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            val thresholdX = inset + trackWidth * threshold
            drawLine(
                color = markerColor,
                start = Offset(thresholdX, 0f),
                end = Offset(thresholdX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Text(
            text = thresholdLabel,
            style = NexaraTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TokenDetailRow(stringResource(R.string.chat_context_label_system), state.systemTokens)
        TokenDetailRow(stringResource(R.string.chat_context_label_summary), state.summaryTokens)
        TokenDetailRow(stringResource(R.string.chat_context_label_active), state.activeTokens)
        TokenDetailRow(stringResource(R.string.chat_context_label_rag), state.ragTokens)
        HorizontalDivider(modifier = Modifier.padding(vertical = NexaraSpacing.XSmall))
        TokenDetailRow(stringResource(R.string.chat_context_label_folded_tools), state.foldedToolResults)
        Button(
            onClick = onManualSummary,
            enabled = compressionUnavailableReason == null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .testTag("context_usage_compact_now"),
        ) {
            Text(stringResource(R.string.chat_context_btn_compress))
        }
        if (compressionUnavailableReason != null) {
            Text(
                text = compressionUnavailableReason,
                style = NexaraTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ContextCompactionFeedback(
    state: ChatViewModel.CompressionState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("context_compaction_feedback").semantics {
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        if (state.isCompressing || state.result != null) {
            SummaryCard(
                isCompressing = state.isCompressing,
                progress = state.progress,
                detail = state.detail,
                result = state.result,
            )
        } else if (state.detail.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    text = state.detail,
                    style = NexaraTypography.bodyMedium,
                    color = if (state.progress < 1f) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
                )
            }
        }
        if (!state.isCompressing) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .testTag("context_compaction_dismiss"),
            ) {
                Text(stringResource(R.string.common_dismiss))
            }
        }
    }
}

@Composable
private fun TokenDetailRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = NexaraTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$value", style = NexaraTypography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String,
    onBack: () -> Unit,
    onWorkspace: () -> Unit,
    onSettings: () -> Unit,
    onSessionPrompt: () -> Unit = {},
    onClearHistory: () -> Unit,
    onRename: () -> Unit,
    onDeleteSession: () -> Unit,
    showDivider: Boolean = false,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
) {
    var showMenu by remember { mutableStateOf(false) }
    
    val dividerAlpha by animateFloatAsState(
        targetValue = if (showDivider) 1f else 0f,
        animationSpec = tween(150),
        label = "chat_top_divider",
    )
    Box {
    TopAppBar(
        expandedHeight = 56.dp,
        windowInsets = windowInsets,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_menu_rename), onClick = onRename)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .testTag("chat_top_title"),
            )
        },
        navigationIcon = {
            NexaraBackButton(onClick = onBack)
        },
        actions = {
            IconButton(onClick = onWorkspace) {
                Icon(
                    Icons.Outlined.Folder,
                    stringResource(R.string.chat_cd_workspace),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
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
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                NexaraDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    minWidth = 190.dp,
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.chat_menu_session_settings), style = NexaraTypography.labelLarge) },
                        modifier = Modifier.testTag(UiTags.CHAT_SESSION_SETTINGS),
                        onClick = {
                            showMenu = false
                            onSettings()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.chat_session_prompt_title), style = NexaraTypography.labelLarge) },
                        onClick = {
                            showMenu = false
                            onSessionPrompt()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.chat_menu_clear_history), style = NexaraTypography.labelLarge) },
                        onClick = {
                            showMenu = false
                            onClearHistory()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = { Text(stringResource(R.string.chat_menu_rename), style = NexaraTypography.labelLarge) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        text = {
                            Text(
                                stringResource(R.string.chat_menu_delete_session),
                                style = NexaraTypography.labelLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDeleteSession()
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("chat_top_bar"),
    )
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.align(Alignment.BottomCenter).alpha(dividerAlpha))
    }
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_dialog_rename_title)) },
        text = {
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.chat_dialog_rename_placeholder))
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
    )
}

@Composable
internal fun ChatComposerAttachments(
    imageUris: List<android.net.Uri>,
    documents: List<MessageDocumentAttachment>,
    isImporting: Boolean,
    onRemoveImage: (Int) -> Unit = {},
    onRemoveDocument: (String) -> Unit = {},
) {
    if (imageUris.isEmpty() && documents.isEmpty() && !isImporting) return
    val removeDescription = stringResource(R.string.chat_cd_remove_document)
    // 同一张图可能被选两次，按出现次序区分，保证删除一张时其余缩略图平滑补位。
    val imageKeys = remember(imageUris) {
        val seen = HashMap<String, Int>()
        imageUris.map { uri -> uri.toString().let { "image:$it#" + seen.merge(it, 1, Int::plus) } }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .then(if (documents.isNotEmpty() || isImporting) Modifier.testTag(UiTags.CHAT_DOCUMENT_CHIPS) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(imageUris.size, key = { imageKeys[it] }) { index ->
            Box(Modifier.animateItem().size(56.dp)) {
                coil3.compose.AsyncImage(
                    model = imageUris[index],
                    contentDescription = stringResource(R.string.chat_cd_selected_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                IconButton(onClick = { onRemoveImage(index) }, modifier = Modifier.size(48.dp).align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.chat_cd_remove_image),
                        modifier = Modifier.size(20.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape).padding(2.dp))
                }
            }
        }
        items(documents, key = MessageDocumentAttachment::id) { document ->
            Box(
                modifier = Modifier.animateItem().size(56.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(role = Role.Button, onClickLabel = removeDescription) { onRemoveDocument(document.id) }
                    .testTag(UiTags.chatDocumentChip(document.id)),
            ) {
                Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.Bottom) {
                    Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(document.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    onClick = { onRemoveDocument(document.id) },
                    modifier = Modifier.size(48.dp).align(Alignment.TopEnd).testTag(UiTags.chatDocumentRemove(document.id)),
                ) { Icon(Icons.Rounded.Close, removeDescription, modifier = Modifier.size(16.dp)) }
            }
        }
        if (isImporting) {
            item(key = "document_importing") {
                Box(Modifier.animateItem().size(56.dp).testTag(UiTags.CHAT_DOCUMENT_IMPORTING), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
    isGenerating: Boolean = status == GenerationStatus.UPLOADING ||
        status == GenerationStatus.THINKING || status == GenerationStatus.RECEIVING,
    onStop: () -> Unit = {},
    onDismissError: () -> Unit = {},
    isModelSelected: Boolean = true,
    onModelHint: () -> Unit = {},
    hasImages: Boolean = false,
    hasDocuments: Boolean = false,
    isImportingDocument: Boolean = false,
    attachmentMenuExpanded: Boolean = false,
    onAttachmentMenuExpandedChange: (Boolean) -> Unit = {},
    onAttachmentAnchorBoundsChange: (Rect) -> Unit = {},
    modelName: String = "",
    modelId: String? = null,
    tokenState: ChatViewModel.TokenIndicatorState = ChatViewModel.TokenIndicatorState(),
    isCompressing: Boolean = false,
    onModelClick: () -> Unit = {},
    onManualSummary: () -> Unit = {},
    reasoningControl: ChatReasoningControl? = null,
    onReasoningChange: (com.promenar.nexara.data.model.params.ReasoningEffort?) -> Unit = {},
    attachmentContent: @Composable () -> Unit = {},
    /** 浮岛顶部区域（计划条），与输入区共用底色和圆角。 */
    topContent: @Composable ColumnScope.() -> Unit = {},
) {
    val shownPlaceholder = if (isGenerating) stringResource(R.string.chat_input_placeholder_generating) else placeholder
    val effectiveStatus = if (isGenerating || status == GenerationStatus.ERROR) {
        status
    } else {
        GenerationStatus.IDLE
    }
    val attachmentIconRotation by animateFloatAsState(
        targetValue = if (attachmentMenuExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "attachment_menu_rotation",
    )
    val attachmentStateDescription = stringResource(
        if (attachmentMenuExpanded) R.string.common_state_expanded else R.string.common_state_collapsed,
    )
    val attachmentContentDescription = stringResource(
        if (attachmentMenuExpanded) R.string.common_dismiss else R.string.chat_cd_add_attachment,
    )
    // 不裁剪首末行行距：最多六行的高度上限按完整行高计算，第六行墨迹不会被输入框底边裁掉。
    val inputStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
    )
    // 浮岛与底部导航栏同一套材质：抬升底色、半透明描边与投影；生成时外圈流光环绕，出错时整圈泛红。
    val material = composerIslandMaterial()
    val glow = rememberComposerGlowState(generating = isGenerating, error = status == GenerationStatus.ERROR)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .composerIslandGlow(glow, material.topHighlight)
            .animateContentSize()
            .testTag(UiTags.CHAT_INPUT_BAR_SURFACE),
        shape = RoundedCornerShape(ComposerIslandCorner),
        color = material.container,
        border = material.border,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        // 输入框自带上下 12dp 留白并保证 48dp 点击高度；有附件时附件行与容器顶边另留 12dp。
        val hasAttachments = hasImages || hasDocuments || isImportingDocument
        Column {
        topContent()
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = if (hasAttachments) 12.dp else 0.dp, bottom = 8.dp)) {
            attachmentContent()
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .semantics { if (shownPlaceholder.isNotBlank()) contentDescription = shownPlaceholder }
                    .testTag(UiTags.CHAT_INPUT),
                textStyle = inputStyle.forInput(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // 生成中仍可先写下一条消息；发送键在生成期间保持为停止，生成结束后再发送。
                enabled = true,
                minLines = 1,
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(shownPlaceholder, style = inputStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        innerTextField()
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("chat_composer_toolbar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatCompactIconButton(
                    onClick = { onAttachmentMenuExpandedChange(!attachmentMenuExpanded) },
                    // 按钮左缘贴齐容器边缘：图标墨迹距输入文字左缘约 1.6dp，且不被容器裁剪。
                    modifier = Modifier.offset(x = (-12).dp)
                        .onGloballyPositioned { onAttachmentAnchorBoundsChange(it.boundsInRoot()) }
                        .semantics {
                            stateDescription = attachmentStateDescription
                            contentDescription = attachmentContentDescription
                        }
                        .testTag(UiTags.CHAT_ADD_ATTACHMENT),
                    enabled = !isImportingDocument,
                ) {
                    if (isImportingDocument) CircularProgressIndicator(Modifier.size(20.dp).testTag(UiTags.CHAT_DOCUMENT_IMPORTING), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Add, contentDescription = null,
                        tint = if (hasImages || hasDocuments) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp).rotate(attachmentIconRotation))
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    // 空间不足时推理强度胶囊只留灯泡，模型名优先显示。
                    val compactReasoning = maxWidth < ReasoningChipCompactBelow
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatModelSelector(modelName, modelId, onModelClick, Modifier.weight(1f, fill = false).widthIn(max = 216.dp))
                        reasoningControl?.let { control ->
                            ChatReasoningChip(control, onReasoningChange, Modifier.padding(start = 2.dp), compact = compactReasoning)
                        }
                    }
                }
                TokenIndicator(state = tokenState, onManualSummary = onManualSummary, isCompressing = isCompressing)
                Spacer(Modifier.width(4.dp))
                // 生成中写好了下一条：本该显示停止的主按钮变为排队发送，停止退到左侧的次级按钮；完成与出错状态照常播报。
                val hasDraft = text.isNotBlank() || hasImages || hasDocuments
                val queueing = isGenerating && hasDraft && status in QueueableStatuses
                AnimatedVisibility(
                    visible = queueing,
                    enter = expandHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) +
                        fadeIn(tween(NexaraMotion.DurationMedium)),
                    exit = shrinkHorizontally(tween(NexaraMotion.DurationShort)) + fadeOut(tween(NexaraMotion.DurationShort)),
                ) {
                    QueueStopButton(onStop = onStop, modifier = Modifier.padding(end = 6.dp))
                }
                GenerationStatusButton(
                    status = if (queueing) GenerationStatus.IDLE else effectiveStatus,
                    onSend = { if (isModelSelected) onSend() else onModelHint() },
                    onStop = onStop,
                    onDismissError = onDismissError,
                    enabled = hasDraft && !isImportingDocument,
                    isModelSelected = isModelSelected,
                    sendDescription = if (queueing) stringResource(R.string.chat_queue_send_cd) else null,
                )
            }
        }
        }
    }
}

private val ReasoningChipCompactBelow = 200.dp
private val QueueableStatuses = setOf(GenerationStatus.UPLOADING, GenerationStatus.THINKING, GenerationStatus.RECEIVING)

@Composable
private fun GenerationStatusButton(
    status: GenerationStatus,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    enabled: Boolean,
    isModelSelected: Boolean = true,
    /** 生成中排队时发送键的读屏描述；为 null 时为普通“发送”。 */
    sendDescription: String? = null,
) {
    val active = status == GenerationStatus.UPLOADING || status == GenerationStatus.THINKING || status == GenerationStatus.RECEIVING
    val canSend = enabled && isModelSelected
    val containerColor by animateColorAsState(
        targetValue = when {
            status == GenerationStatus.ERROR -> MaterialTheme.colorScheme.error
            active || canSend -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.Standard),
        label = "chat_send_container",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            status == GenerationStatus.ERROR -> MaterialTheme.colorScheme.onError
            active || canSend -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.Standard),
        label = "chat_send_content",
    )
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
        GenerationStatus.IDLE -> sendDescription ?: stringResource(R.string.chat_cd_send)
        GenerationStatus.UPLOADING,
        GenerationStatus.THINKING,
        GenerationStatus.RECEIVING -> stringResource(R.string.chat_cd_stop)
        GenerationStatus.COMPLETED,
        GenerationStatus.ERROR -> statusDescription
    }

    val haptics = com.promenar.nexara.ui.common.LocalNexaraHaptics.current
    ChatCompactIconButton(
        onClick = {
            when (status) {
                GenerationStatus.IDLE -> {
                    if (canSend) haptics.perform(com.promenar.nexara.ui.common.NexaraHapticEvent.CONFIRM)
                    onSend()
                }
                GenerationStatus.ERROR -> onDismissError()
                GenerationStatus.COMPLETED -> Unit
                else -> {
                    haptics.perform(com.promenar.nexara.ui.common.NexaraHapticEvent.SELECTION)
                    onStop()
                }
            }
        },
        modifier = Modifier
            .semantics {
                contentDescription = actionDescription
                stateDescription = statusDescription
                liveRegion = if (status == GenerationStatus.ERROR) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            }
            .testTag(UiTags.CHAT_GENERATION_ACTION),
        containerColor = containerColor,
        enabled = (status == GenerationStatus.IDLE || status == GenerationStatus.RECEIVING || status == GenerationStatus.THINKING || status == GenerationStatus.UPLOADING || status == GenerationStatus.ERROR)
    ) {
        // 发送、停止与错误三种图标之间缩放交叉切换；读屏描述挂在按钮上，不随图标动画重复播报。
        AnimatedContent(
            targetState = when {
                active -> 1
                status == GenerationStatus.ERROR -> 2
                else -> 0
            },
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = scaleIn(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate), initialScale = 0.6f) +
                        fadeIn(tween(NexaraMotion.DurationShort)),
                    initialContentExit = scaleOut(tween(NexaraMotion.DurationShort), targetScale = 0.6f) +
                        fadeOut(tween(NexaraMotion.DurationFadeOut)),
                )
            },
            contentAlignment = Alignment.Center,
            label = "chat_send_icon",
        ) { kind ->
            when (kind) {
                1 -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(contentColor))
                else -> Icon(
                    imageVector = if (kind == 2) Icons.Rounded.ErrorOutline else Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 排队时的次级停止键：中性底色，与主按钮区分主次。 */
@Composable
private fun QueueStopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = com.promenar.nexara.ui.common.LocalNexaraHaptics.current
    val description = stringResource(R.string.chat_queue_stop_cd)
    ChatCompactIconButton(
        onClick = {
            haptics.perform(com.promenar.nexara.ui.common.NexaraHapticEvent.SELECTION)
            onStop()
        },
        modifier = modifier
            .semantics { contentDescription = description }
            .testTag(UiTags.CHAT_GENERATION_STOP),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface))
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
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha * 0.5f))
            )
        }

        // Mock AI Message 1
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            )
        }

        // Mock AI Message 2 (Thinking + Content)
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.2f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

/** 助手组后紧跟错误组时，本组是被错误截断的半轮回复。 */
internal fun groupInterruptedByError(groups: List<PipelineGroup>, index: Int): Boolean {
    val group = groups.getOrNull(index) ?: return false
    if (group.isUser || group.isNotice) return false
    val next = groups.getOrNull(index + 1) ?: return false
    if (next.isUser || next.isNotice) return false
    return next.messages.firstOrNull()?.let { it.isError || it.errorMessage != null } == true
}

/** 审批请求指向本组的助手消息时视为待审批；旧请求缺少消息 ID 时只作用于最后一组。 */
internal fun groupHasPendingApproval(
    group: PipelineGroup,
    request: com.promenar.nexara.data.model.ApprovalRequest?,
    isLastGroup: Boolean,
): Boolean {
    if (request == null || group.isUser || group.isNotice) return false
    val targetId = request.assistantMessageId ?: return isLastGroup
    return group.messages.any { it.id == targetId }
}

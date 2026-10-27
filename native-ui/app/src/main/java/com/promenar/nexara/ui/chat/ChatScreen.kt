package com.promenar.nexara.ui.chat

import com.promenar.nexara.ui.chat.runmode.labelRes
import com.promenar.nexara.ui.theme.NexaraMonospace
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import com.promenar.nexara.ui.common.ModelBrandIcon
import com.promenar.nexara.ui.common.NexaraBackButton
import com.promenar.nexara.ui.common.NexaraMetaSeparator
import com.promenar.nexara.ui.common.nexaraPopoverAnchor
import com.promenar.nexara.ui.common.rememberNexaraPopoverAnchor
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Schedule
import com.promenar.nexara.ui.common.NexaraMenuDivider
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.ui.chat.components.TaskPanelSizeSpring
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
import com.promenar.nexara.ui.common.NexaraMenuItem
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraHapticEvent
import com.promenar.nexara.ui.common.NexaraPopover
import com.promenar.nexara.ui.common.NexaraPopoverSide
import com.promenar.nexara.ui.common.NexaraPopoverAlign
import androidx.compose.material3.FilledTonalButton
import com.promenar.nexara.ui.theme.nexaraDomainColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Compress

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
    /** 本次生成任务开始的墙钟时间；没有进行中的任务时为 null。 */
    val generationStartedAt: Long? = null,
    /** 外观页“完成后折叠过程”，由路由层从外观偏好读取后传入。 */
    val collapseFinishedTurns: Boolean = true,
    /** 当前会话各轮的图谱抽取状态，键为该轮最后一条助手消息。 */
    val turnGraphStates: Map<String, com.promenar.nexara.data.rag.TurnGraphState> = emptyMap(),
    /** 生成中按发送键排队的后续消息。 */
    val composerQueue: ComposerQueueUiState = ComposerQueueUiState(),
    /** 是否已锁定触摸；生成结束后仍保持，直到用户解锁。 */
    val touchLocked: Boolean = false,
    val touchLockUnlock: com.promenar.nexara.ui.theme.TouchLockUnlock = com.promenar.nexara.ui.theme.TouchLockUnlock.HOLD,
    /** 输入框斜杠命令目录（内置命令与当前会话可用技能）。 */
    val slashCommands: List<com.promenar.nexara.ui.chat.command.SlashCommand> = emptyList(),
    val stateBoard: com.promenar.nexara.data.state.StateBoardSnapshot? = null,
    /** 当前会话中等待用户决定的子代理审批，按请求时间排列，在会话流底部以卡片呈现。 */
    val subagentApprovals: List<com.promenar.nexara.data.subagent.SubagentApproval> = emptyList(),
    /** 本轮因 `ask_user` 暂停、等待回答的提问；卡片在会话流底部，输入栏写下的文字作为其他回答提交。 */
    val pendingQuestion: com.promenar.nexara.domain.ask.PendingQuestion? = null,
    /** 会话工作区是 Git 仓库时的分支与改动摘要，显示在顶栏标题下方；否则为 null。 */
    val gitSummary: com.promenar.nexara.ui.git.GitEntrySummary.Repository? = null,
    /** 本会话或其工作区因启动自检问题暂停写入，顶栏下方显示提示条。 */
    val startupWritePaused: Boolean = false,
    /** 侧载版顶栏标题旁的运行模式标识；Play 版为 null。 */
    val runMode: com.promenar.nexara.ui.chat.runmode.RunModeBadge? = null,
    /** 浮岛工具行的执行模式；会话关闭工具时为 null，不显示盾牌。 */
    val executionMode: com.promenar.nexara.ui.common.ExecutionMode? = null,
    /** 顶栏状态行的会话工作区；解析完成前为 null，不显示。 */
    val workspace: com.promenar.nexara.ui.workspace.SessionWorkspaceUi? = null,
    /** 会话二级内容层级；宽窗口下其根即检视器当前页签，为空表示检视器收起。 */
    val inspectorPages: List<ChatPage> = emptyList(),
    /** 本会话 Agent 浏览器的页面与操作权状态；还没有打开网页时为 null，入口仍常驻并进入空状态。 */
    val browser: com.promenar.nexara.data.browser.BrowserUiState? = null,
)

data class ChatScreenActions(
    val onNavigateBack: () -> Unit = {},
    /** 允许或拒绝一项子代理审批（审批 id，是否允许）。 */
    val onResolveSubagentApproval: (String, Boolean) -> Unit = { _, _ -> },
    val onOpenWorkspace: () -> Unit = {},
    val onOpenGit: () -> Unit = {},
    val onExecutionModeChange: (com.promenar.nexara.ui.common.ExecutionMode) -> Unit = {},
    /** 点按顶栏状态行的工作区：打开工作区与 Agent 可达范围面板，可在其中切换。 */
    val onOpenWorkspaceStatus: () -> Unit = {},
    /** 窄窗口打开 Agent 浏览器整页；宽窗口由检视器页签承载。 */
    val onOpenBrowser: () -> Unit = {},
    /** 宽窗口检视器：替换整个层级（打开页签、收起）。 */
    val onSetInspectorPages: (List<ChatPage>) -> Unit = {},
    /** 宽窗口检视器的返回：弹出最上层，只剩根层时收起。 */
    val onInspectorBack: () -> Unit = {},
    /** 打开启动自检问题面板（会话暂停写入提示条的“处理”）。 */
    val onOpenStartupIssues: () -> Unit = {},
    /** 点运行模式标识或菜单项：环境不可用时回到检查点，否则打开模式切换。 */
    val onOpenRunMode: () -> Unit = {},
    /** 打开资源管理器并定位到给定的工作区虚拟目录（媒体不可用占位的“在资源管理器中定位”）。 */
    val onLocateWorkspacePath: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** 点输入区模型按钮；为 null 时与 [onOpenSettings] 相同。 */
    val onOpenModelPicker: (() -> Unit)? = null,
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
    val onEditState: suspend (Long, com.promenar.nexara.data.state.StateOperation) -> String? = { _, _ -> null },
    val onRemoveState: suspend (Long) -> String? = { null },
    val onSteerQueued: (String) -> Unit = {},
    /** 返回是否受理；未受理（提问已过期或重复提交）时输入栏保留文字。 */
    val onAnswerQuestion: (com.promenar.nexara.domain.ask.PendingQuestion, com.promenar.nexara.domain.ask.AskUserAnswer) -> Boolean = { _, _ -> false },
    val onSendSteering: (String, List<android.net.Uri>) -> Unit = { _, _ -> },
    val onStopAndSend: (String, List<android.net.Uri>) -> Unit = { _, _ -> },
    val onEditQueued: (String) -> Unit = {},
    val onMoveQueuedToFront: (String) -> Unit = {},
    val onRemoveQueued: (String) -> Unit = {},
    val onResumeQueue: () -> Unit = {},
    val onLockTouch: () -> Unit = {},
    val onUnlockTouch: () -> Unit = {},
    /** 点选斜杠命令候选。 */
    val onSlashCommandPicked: (com.promenar.nexara.ui.chat.command.SlashCommand) -> Unit = {},
    /** 斜杠命令候选弹出时调用一次，用于刷新技能列表。 */
    val onSlashPaletteShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreenContent(
    state: ChatScreenState,
    actions: ChatScreenActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    snackbarData: NexaraSnackbarData? = null,
    attachmentMenuExpandedState: MutableState<Boolean> = remember { mutableStateOf(false) },
    /** 宽窗口检视器“文件”“Git”页签的内容：由路由层承载资源管理器与 Git 变更的层级。 */
    inspectorPageContent: @Composable BoxScope.() -> Unit = {},
) {
    val uiState = state.uiState
    val inputText = state.inputText
    val tokenState = state.tokenState
    val ragPhases = state.ragPhases
    val compressionState = state.compressionState
    val selectedImageUris = state.selectedImageUris
    val draftDocuments = state.draftDocuments
    val listState = rememberLazyListState()
    val subagentApprovalGroups = remember(state.subagentApprovals) {
        state.subagentApprovals.groupBy { it.subagentIndex }.values.toList()
    }
    // 新到的子代理审批振动一次（与全局弹窗一致）；列表项滚出滚入不重复振动。
    val approvalHaptics = com.promenar.nexara.ui.common.LocalNexaraHaptics.current
    val newestSubagentApproval = state.subagentApprovals.maxByOrNull { it.requestedAt }?.id
    LaunchedEffect(newestSubagentApproval) {
        if (newestSubagentApproval != null) approvalHaptics.perform(com.promenar.nexara.ui.common.NexaraHapticEvent.ATTENTION)
    }
    var showModelHint by remember { mutableStateOf(false) }
    var attachmentMenuExpanded by attachmentMenuExpandedState
    var chatRootBounds by remember { mutableStateOf(Rect.Zero) }
    var composerTopInRoot by remember { mutableStateOf(0f) }
    var attachmentAnchorInRoot by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    // 只在第一个词仍在输入时弹出候选：文本以 / 开头且尚未出现空白。
    val slashQuery = com.promenar.nexara.ui.chat.command.SlashCommandCatalog.triggerQuery(inputText, inputText.length)
    val slashPaletteVisible = slashQuery != null
    val slashMatches = remember(slashQuery, state.slashCommands) {
        slashQuery?.let { com.promenar.nexara.ui.chat.command.SlashCommandCatalog.filter(state.slashCommands, it) }.orEmpty()
    }
    LaunchedEffect(slashPaletteVisible) {
        if (slashPaletteVisible) actions.onSlashPaletteShown()
    }

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
    val branchAccess = LocalBranchAccess.current
    val pendingBranchPlan = branchAccess?.let { access ->
        remember(access) { access.controls.observePendingPlan(access.sessionId) }.collectAsState(initial = null).value
    }
    var resolvingBranchPlan by remember { mutableStateOf<String?>(null) }
    val branchScope = rememberCoroutineScope()
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
    //  用户往回看 → 切断追踪 → 推到底、滑回尾部停下或点 FAB → 恢复追踪
    // ═══════════════════════════════════════════════════════════

    var autoFollowEnabled by remember { mutableStateOf(true) }

    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    autoFollowEnabled = ChatAutoFollowPolicy.afterUserDrag(autoFollowEnabled, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && ChatAutoFollowPolicy.resumesOnOverscroll(available.y)) {
                    autoFollowEnabled = true
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

    // 用户滑回尾部（含惯性）停下时恢复追踪；程序滚动只在追踪开启时发生，不会误触发。
    LaunchedEffect(followTargetIndex) {
        if (followTargetIndex < 0) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling || autoFollowEnabled) return@collect
            val layout = listState.layoutInfo
            val overlapPx = with(density) {
                chatComposerInsets(currentComposerHeightPx.toDp()).streamingOverlap.roundToPx()
            }
            val tailOverflow = layout.visibleItemsInfo.firstOrNull { it.index == followTargetIndex }
                ?.let { it.offset + it.size - (layout.viewportEndOffset - overlapPx) }
            if (ChatAutoFollowPolicy.resumesWhenSettled(
                    tailOverflowPx = tailOverflow,
                    canScrollForward = listState.canScrollForward,
                    tolerancePx = with(density) { ChatAutoFollowResumeTolerance.roundToPx() },
                )
            ) {
                autoFollowEnabled = true
            }
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

        // 宽窗口（折叠屏内屏、平板）：运行、文件、Git 与画布收进右侧浮动检视器；层级为空时检视器收起。
        val inspectorMode = com.promenar.nexara.ui.common.LocalNexaraInspectorLayout.current
        val inspectorRoot = state.inspectorPages.firstOrNull()?.takeIf { inspectorMode }
        val toggleInspectorPage: (ChatPage) -> Unit = { page ->
            actions.onSetInspectorPages(toggleChatInspector(state.inspectorPages, page))
        }
        var lastInspectorRoot by rememberSaveable(uiState.session?.id) { mutableStateOf<String?>(null) }
        SideEffect { inspectorRoot?.let { lastInspectorRoot = it.token } }
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
                    artifacts = com.promenar.nexara.ui.canvas.LocalCanvasSession.current?.let { canvasSession ->
                        chatTopBarArtifacts(inspectorMode, canvasSession.artifacts.size, canvasSession.openList)
                    },
                    git = state.gitSummary,
                    onOpenGit = if (inspectorMode) ({ toggleInspectorPage(ChatPage.Git) }) else actions.onOpenGit,
                    inspector = if (inspectorMode) ChatTopBarInspector(
                        open = inspectorRoot != null,
                        onToggle = {
                            actions.onSetInspectorPages(
                                if (inspectorRoot != null) emptyList()
                                else openChatPageRoot(defaultChatInspectorRoot(lastInspectorRoot?.let { restoreChatPages(it).firstOrNull() }, uiState.isGenerating || state.taskPanel != null)),
                            )
                        },
                    ) else null,
                    runMode = state.runMode,
                    onRunMode = actions.onOpenRunMode,
                    workspace = state.workspace,
                    onWorkspaceStatus = actions.onOpenWorkspaceStatus,
                    browser = chatTopBarBrowser(
                        inspectorMode = inspectorMode,
                        mode = state.browser?.mode ?: com.promenar.nexara.data.browser.BrowserMode.IDLE,
                        open = inspectorRoot == ChatPage.Browser,
                        onOpen = actions.onOpenBrowser,
                    ),
                    isBranch = uiState.session?.parentSessionId != null || uiState.session?.parentSessionDeleted == true,
                    branchOpenParent = branchAccess?.parentSessionId?.let { parent -> { branchAccess.onOpenSession(parent) } },
                )
                if (state.startupWritePaused) {
                    com.promenar.nexara.ui.startup.StartupWritePausedBanner(onOpen = actions.onOpenStartupIssues)
                }
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
            // 运行坞：计划、生成、审批、后台任务与状态面板合成一行，清单收进运行详情，统计挂在回复下方。
            var runSheetTab by remember(uiState.session?.id) { mutableStateOf<com.promenar.nexara.ui.chat.components.RunDetailTab?>(null) }
            var runSheetOpen by remember(uiState.session?.id) { mutableStateOf(false) }
            val agentActivity = com.promenar.nexara.ui.chat.components.rememberAgentActivityState(fastTick = runSheetOpen || inspectorRoot == ChatPage.Run)
            val activitySummary = agentActivity?.summary
            val boardTitle = state.stateBoard?.board?.title
            val pendingApprovals = (uiState.approvalRequest?.takeUnless { uiState.isApprovalSubmitting }?.calls?.size?.coerceAtLeast(1) ?: 0) +
                state.subagentApprovals.size
            val turnStartedAt = state.generationStartedAt?.takeIf { uiState.isGenerating }
            var dockNow by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(uiState.isGenerating) {
                while (uiState.isGenerating) { dockNow = System.currentTimeMillis(); delay(15_000) }
            }
            val dock = com.promenar.nexara.ui.chat.components.runDockState(
                plan = lastVisiblePlan.takeIf { taskPanel != null && planBarVisible },
                isGenerating = uiState.isGenerating,
                pendingApprovals = pendingApprovals,
                runningJobs = activitySummary?.runningJobs ?: 0,
                enabledSchedules = activitySummary?.enabledSchedules ?: 0,
                boardTitle = boardTitle,
                awaitingAnswer = state.pendingQuestion != null,
                elapsedMillis = turnStartedAt?.let { (dockNow - it).coerceAtLeast(0) },
            )
            var lastDock by remember(uiState.session?.id) { mutableStateOf(dock) }
            if (dock != null) lastDock = dock
            if (state.touchLocked) {
                val lockDock = dock ?: lastDock
                com.promenar.nexara.ui.chat.TouchLockDialog(
                    state = com.promenar.nexara.ui.chat.TouchLockUiState(
                        phase = com.promenar.nexara.ui.chat.touchLockPhase(uiState.isGenerating, pendingApprovals),
                        label = com.promenar.nexara.ui.chat.touchLockStepTitle(lockDock?.label).orEmpty(),
                        meta = lockDock?.let { com.promenar.nexara.ui.chat.components.runDockMetaText(it) }.orEmpty(),
                        progress = lockDock?.progress,
                    ),
                    unlock = state.touchLockUnlock,
                    onUnlock = actions.onUnlockTouch,
                )
            }
            val canvasSession = com.promenar.nexara.ui.canvas.LocalCanvasSession.current
            // 侧栏计划区与运行坞同一可见规则：隐藏或自动收起的计划不占侧栏。
            val sidePlan = taskPanel?.takeIf { planBarVisible && it.totalCount > 0 }
            val sideArtifacts = canvasSession?.artifacts.orEmpty()
            val sideCandidates = canvasSession?.candidates.orEmpty()
            val sideBoard = state.stateBoard?.takeIf { it.board != null }
            val hasRunContent = sidePlan != null || sideBoard != null || activitySummary?.visible == true || uiState.isGenerating
            val inspectorBadges = ChatInspectorBadges(
                running = uiState.isGenerating,
                pendingApprovals = pendingApprovals,
                hasRunContent = hasRunContent,
                gitAvailable = state.gitSummary != null,
                gitChanges = state.gitSummary?.changeCount ?: 0,
                canvases = sideArtifacts.size,
                browser = state.browser?.mode,
                browserEntry = true,
            )
            var inspectorWidened by rememberSaveable { mutableStateOf(false) }
            // 浏览器页签加宽时铺满会话区，只留左侧一条边距，再按一次恢复。
            val inspectorWidth = if (inspectorWidened && inspectorRoot == ChatPage.Browser) maxWidth - BrowserMaximizedInset
            else com.promenar.nexara.ui.common.nexaraInspectorWidth(maxWidth, inspectorWidened)
            // 让位后会话仍有足够宽度（平板横屏）时整体让位；否则（折叠屏内屏）浮在会话右缘之上，会话不动。
            val inspectorFloats = com.promenar.nexara.ui.common.nexaraInspectorPlacement(maxWidth, inspectorWidth) ==
                com.promenar.nexara.ui.common.NexaraInspectorPlacement.Float
            // 浮在会话之上时右缘与输入区浮岛对齐：输入区限宽居中，检视器右边距随之加上两侧留白。
            val inspectorEnd = 12.dp + if (inspectorFloats && inspectorWidth <= com.promenar.nexara.ui.common.NexaraInspectorWideWidth) {
                // 左侧至少给会话留 48dp 可见边缘。
                minOf((maxWidth - NexaraSpacing.ChatMaxWidth) / 2, maxWidth - inspectorWidth - 12.dp - 48.dp).coerceAtLeast(0.dp)
            } else 0.dp
            // 审批卡在会话列底部；检视器浮在会话之上时会盖住它，新审批到来时先收为边缘条（角标仍提示待批准）。
            val hasPendingApproval = pendingApprovals > 0
            LaunchedEffect(hasPendingApproval, inspectorFloats) {
                if (hasPendingApproval && inspectorFloats && inspectorRoot != null) actions.onSetInspectorPages(emptyList())
            }
            val inspectorReserved by animateDpAsState(
                targetValue = if (inspectorRoot != null && !inspectorFloats) inspectorWidth + com.promenar.nexara.ui.common.NexaraInspectorGutter else 0.dp,
                animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate),
                label = "chat_inspector_reserved",
            )
            val jumpToApproval: () -> Unit = {
                scope.launch {
                    // 审批卡与子代理审批组都排在底部留白之前，跳到其中第一项。
                    val pendingItems = (if (uiState.approvalRequest?.takeUnless { uiState.isApprovalSubmitting } != null) 1 else 0) +
                        (if (state.pendingQuestion != null) 1 else 0) + subagentApprovalGroups.size
                    val target = listState.layoutInfo.totalItemsCount - 1 - pendingItems
                    listState.animateScrollToItem(target.coerceAtLeast(0))
                }
            }
            Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = inspectorReserved)
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
            val focusManager = LocalFocusManager.current
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .widthIn(max = NexaraSpacing.ChatMaxWidth)
                    .fillMaxWidth()
                    .nestedScroll(userScrollConnection)
                    .testTag(renderStateTag)
                    // 轻点列表空白处清除焦点，正文选区随之释放；空的长按回调避免长按选字后抬手被当成轻点。
                    .pointerInput(focusManager) {
                        detectTapGestures(onLongPress = {}, onTap = { focusManager.clearFocus() })
                    },
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

                // 决定提交后批准的工具在审批流程内同步执行，请求要到执行结束才清除；
                // 期间收起卡片，由工具行与实时输出显示进度。提交未生效时请求仍在，卡片随即恢复。
                state.pendingQuestion?.let { question ->
                    item(key = "ask_question") {
                        ChatApprovalLiveRegion(Modifier.animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = ChatItemFadeOut)) {
                            AskUserCard(
                                request = question.request,
                                stateKey = question.toolCallId,
                                onSubmit = { answer -> actions.onAnswerQuestion(question, answer) },
                                onSkip = { actions.onAnswerQuestion(question, com.promenar.nexara.domain.ask.AskUserAnswer(skipped = true)) },
                            )
                        }
                    }
                }
                pendingBranchPlan?.let { pending ->
                    item(key = "branch_plan") {
                        ChatApprovalLiveRegion(Modifier.animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = ChatItemFadeOut)) {
                            BranchPlanCard(
                                pending = pending,
                                enabled = resolvingBranchPlan != pending.toolCallId,
                                onResolve = { answer ->
                                    val controls = branchAccess?.controls ?: return@BranchPlanCard
                                    resolvingBranchPlan = pending.toolCallId
                                    branchScope.launch {
                                        // 离开页面也要把选择落盘，否则这一轮会一直停在待处理。
                                        try {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { controls.resolvePlan(pending, answer) }
                                        } finally {
                                            resolvingBranchPlan = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                uiState.approvalRequest?.takeUnless { uiState.isApprovalSubmitting }?.let { request ->
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
                                        schedule = if (call.toolName != "schedule_create") null else {
                                            val context = LocalContext.current
                                            remember(call, request.assistantMessageId, uiState.messages) {
                                                ApprovalPreview.arguments(call, uiState.messages, request.assistantMessageId)?.let { args ->
                                                    scheduleApprovalPreview(
                                                        args = args,
                                                        nowMillis = System.currentTimeMillis(),
                                                        zoneId = java.time.ZoneId.systemDefault().id,
                                                        exactAlarmAvailable = com.promenar.nexara.distribution.DistributionCapabilities.exactAlarmAvailable,
                                                        canScheduleExact = com.promenar.nexara.background.schedule.canScheduleExact(context),
                                                    )
                                                }
                                            }
                                        },
                                        background = remember(call, request.assistantMessageId, uiState.messages) {
                                            ApprovalPreview.arguments(call, uiState.messages, request.assistantMessageId)?.let {
                                                backgroundRunPreview(it, com.promenar.nexara.distribution.DistributionCapabilities.backgroundKeepAliveAvailable)
                                            }
                                        },
                                    )
                                }.ifEmpty {
                                    listOf(ApprovalDisplayCall(
                                        name = request.toolName ?: stringResource(R.string.chat_approval_unknown_tool),
                                        argumentsSummary = request.args.orEmpty(),
                                        riskLabel = approvalRiskLabel(request.type.orEmpty()),
                                    ))
                                },
                                onApprove = { actions.onApprove(request) },
                                onApproveAll = { actions.onApproveAll(request) },
                                onDecline = { actions.onDecline(request) },
                            )
                        }
                    }
                }

                // 子代理在原地挂起等待；按子代理分组，每组一张分组卡。
                subagentApprovalGroups.forEach { group ->
                    item(key = "subagent_approval_${group.first().subagentIndex}_${group.first().id}") {
                        ChatApprovalLiveRegion(Modifier.animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = ChatItemFadeOut)) {
                            SubagentApprovalGroup(group, onResolve = actions.onResolveSubagentApproval)
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
                ChatComposerStatusBar(
                    taskPanel = taskPanel.takeUnless { planBarVisible },
                    postProcessTasks = state.postProcessTasks,
                    retry = state.generationRetry,
                    onRemovePostProcessTask = actions.onRemovePostProcessTask,
                    onTaskClick = { hiddenPlanSignature = null },
                )
                    // 三种发送方式共用：纯附件时补默认提示词。
                    val submitDraft: ((String, List<android.net.Uri>) -> Unit) -> Unit = { send ->
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
                            send(inputText.ifBlank { fallback }, selectedImageUris)
                        }
                    }
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
                                val question = state.pendingQuestion
                                // 待回答时输入栏的文字就是“其他回答”，不另起一轮。
                                if (question != null && inputText.isNotBlank()) {
                                    if (actions.onAnswerQuestion(question, com.promenar.nexara.domain.ask.AskUserAnswer(other = inputText.trim()))) {
                                        actions.onTextChange("")
                                    }
                                } else {
                                    submitDraft(actions.onSend)
                                }
                            },
                            answeringQuestion = state.pendingQuestion != null,
                            status = uiState.status,
                            isGenerating = uiState.isGenerating,
                            onStop = actions.onStop,
                            onSendSteering = { submitDraft(actions.onSendSteering) },
                            onStopAndSend = { submitDraft(actions.onStopAndSend) },
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
                                // 运行坞：计划、生成、审批、后台任务与状态面板合成一行，清单收进运行详情，统计挂在回复下方。
                                AnimatedVisibility(
                                    visible = dock != null && !inspectorMode,
                                    enter = fadeIn(tween(220, delayMillis = 60)) +
                                        expandVertically(TaskPanelSizeSpring, expandFrom = Alignment.Bottom),
                                    exit = fadeOut(tween(160)) +
                                        shrinkVertically(TaskPanelSizeSpring, shrinkTowards = Alignment.Bottom),
                                ) {
                                    lastDock?.let { shown ->
                                        com.promenar.nexara.ui.chat.components.RunDockRow(
                                            state = shown,
                                            onOpen = { tab ->
                                                hiddenPlanSignature = null
                                                runSheetTab = tab
                                                runSheetOpen = true
                                            },
                                            onJumpToApproval = jumpToApproval,
                                            onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating },
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
                                // 斜杠命令候选紧贴输入区，在排队托盘之下，二者同时出现时上下排列、互不遮挡。
                                var lastSlashMatches by remember { mutableStateOf(slashMatches) }
                                if (slashPaletteVisible) lastSlashMatches = slashMatches
                                AnimatedVisibility(
                                    visible = slashPaletteVisible,
                                    enter = fadeIn(tween(160)) +
                                        expandVertically(TaskPanelSizeSpring, expandFrom = Alignment.Bottom),
                                    exit = fadeOut(tween(120)) +
                                        shrinkVertically(TaskPanelSizeSpring, shrinkTowards = Alignment.Bottom),
                                ) {
                                    com.promenar.nexara.ui.chat.command.SlashCommandPalette(
                                        commands = lastSlashMatches,
                                        onPick = actions.onSlashCommandPicked,
                                    )
                                }
                            },
                            modelName = modelDisplayName,
                            modelId = uiState.session?.modelId,
                            tokenState = tokenState.copy(isGenerating = tokenState.isGenerating || uiState.isGenerating),
                            isCompressing = compressionState.isCompressing,
                            onModelClick = actions.onOpenModelPicker ?: actions.onOpenSettings,
                            onManualSummary = actions.onManualSummary,
                            reasoningControl = state.reasoningControl,
                            onReasoningChange = actions.onReasoningEffortChange,
                            executionMode = state.executionMode,
                            onExecutionModeChange = actions.onExecutionModeChange,
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
            if (inspectorMode) {
                val composerReserve = with(density) { composerHeightPx.toDp() }
                // 浮在会话之上时底边停在输入区上方，不挡输入与审批；让位时占满右侧高度。
                // 与会话列表的底部留白一样直接跟随输入区高度，不做动画，首帧量到高度前也不会先盖住输入区再滑开。
                val inspectorBottom = if (inspectorFloats) composerReserve + 8.dp else 12.dp
                val animatedInspectorWidth by animateDpAsState(
                    targetValue = inspectorWidth,
                    animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate),
                    label = "chat_inspector_width",
                )
                Box(Modifier.fillMaxSize().imePadding()) {
                    AnimatedVisibility(
                        visible = inspectorRoot != null,
                        enter = slideInHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) { it / 3 } +
                            fadeIn(tween(NexaraMotion.DurationShort)),
                        exit = slideOutHorizontally(tween(NexaraMotion.DurationShort, easing = NexaraMotion.EmphasizedAccelerate)) { it / 3 } +
                            fadeOut(tween(NexaraMotion.DurationShort)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = inspectorEnd, bottom = inspectorBottom)
                            .width(animatedInspectorWidth)
                            .fillMaxHeight(),
                    ) {
                        // 先于页面内容注册：页内详情、子目录的返回优先，没有可后退的层级时逐层弹出、最后收起。
                        BackHandler(enabled = inspectorRoot != null, onBack = actions.onInspectorBack)
                        val shownRoot = inspectorRoot ?: lastInspectorRoot?.let { restoreChatPages(it).firstOrNull() } ?: ChatPage.Run
                        ChatInspectorCard(
                            root = shownRoot,
                            badges = inspectorBadges,
                            widened = inspectorWidened,
                            onSelect = { page -> actions.onSetInspectorPages(openChatPageRoot(page)) },
                            onToggleWiden = { inspectorWidened = !inspectorWidened },
                            onCollapse = { actions.onSetInspectorPages(emptyList()) },
                            onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating },
                        ) {
                            // 跨组换页签整体横向过渡；同组（文件、Git、浏览器）由页面层自己过渡，页面层只组合一份。
                            AnimatedContent(
                                targetState = shownRoot,
                                contentKey = { it.inspectorGroup },
                                transitionSpec = { NexaraMotion.sharedAxisX(forward = inspectorTabForward(initialState, targetState)) },
                                label = "inspector_tab",
                                modifier = Modifier.fillMaxSize(),
                            ) { root ->
                            Box(Modifier.fillMaxSize()) {
                            when (root) {
                                ChatPage.Run -> ChatInspectorRunTab(
                                    pendingApprovals = pendingApprovals,
                                    onJumpToApproval = {
                                        if (inspectorFloats) actions.onSetInspectorPages(emptyList())
                                        jumpToApproval()
                                    },
                                    hasRunContent = hasRunContent,
                                ) {
                                    com.promenar.nexara.ui.chat.components.RunDetailContent(
                                        initialTab = null,
                                        plan = sidePlan,
                                        isGenerating = uiState.isGenerating,
                                        turnStartedAt = turnStartedAt,
                                        activity = agentActivity,
                                        stateBoard = sideBoard,
                                        onDismiss = {},
                                        onContinue = actions.onContinueTask,
                                        onComplete = actions.onCompleteTask,
                                        onHidePlan = { hiddenPlanSignature = sidePlan?.visibilitySignature() },
                                        onClearPlan = actions.onClearCompletedTask,
                                        onEditState = actions.onEditState,
                                        onRemoveState = actions.onRemoveState,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                ChatPage.Canvas -> if (sideArtifacts.isEmpty() && sideCandidates.isEmpty()) {
                                    ChatInspectorCanvasEmpty()
                                } else {
                                    com.promenar.nexara.ui.canvas.ArtifactsContent(
                                        artifacts = sideArtifacts,
                                        candidates = sideCandidates,
                                        onOpen = { artifact -> canvasSession?.openArtifact?.invoke(artifact) },
                                        onClaim = { candidate -> canvasSession?.claimCandidate?.invoke(candidate) },
                                        onIgnore = { candidate -> canvasSession?.ignoreCandidate?.invoke(candidate) },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                ChatPage.Explorer, ChatPage.Git, ChatPage.Browser -> inspectorPageContent()
                            }
                            }
                            }
                        }
                    }
                    // 收起后有内容时留一条边缘条；没有任何内容时完全隐藏，只留顶栏入口。
                    AnimatedVisibility(
                        visible = inspectorRoot == null && inspectorBadges.hasContent,
                        enter = fadeIn(tween(NexaraMotion.DurationShort, delayMillis = 90)) +
                            slideInHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) { it },
                        exit = fadeOut(tween(NexaraMotion.DurationShort)) +
                            slideOutHorizontally(tween(NexaraMotion.DurationShort, easing = NexaraMotion.EmphasizedAccelerate)) { it },
                        // 与卡片底栏同处底部：输入区横跨全宽，底边留在输入区上方以免挡住发送键。
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = composerReserve + 8.dp),
                    ) {
                        ChatInspectorStrip(
                            badges = inspectorBadges,
                            onOpen = { page -> actions.onSetInspectorPages(openChatPageRoot(page)) },
                            onExpand = {
                                actions.onSetInspectorPages(
                                    openChatPageRoot(defaultChatInspectorRoot(lastInspectorRoot?.let { restoreChatPages(it).firstOrNull() }, hasRunContent)),
                                )
                            },
                            onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating },
                        )
                    }
                }
            }
            }
            if (runSheetOpen) {
                val sheetPlan = taskPanel ?: lastVisiblePlan
                com.promenar.nexara.ui.chat.components.RunDetailSheet(
                    initialTab = runSheetTab,
                    plan = sheetPlan,
                    isGenerating = uiState.isGenerating,
                    turnStartedAt = turnStartedAt,
                    activity = agentActivity,
                    stateBoard = state.stateBoard?.takeIf { it.board != null },
                    onDismiss = { runSheetOpen = false },
                    onContinue = actions.onContinueTask,
                    onComplete = actions.onCompleteTask,
                    onHidePlan = {
                        runSheetOpen = false
                        hiddenPlanSignature = sheetPlan?.visibilitySignature()
                    },
                    onClearPlan = {
                        runSheetOpen = false
                        actions.onClearCompletedTask()
                    },
                    onEditState = actions.onEditState,
                    onRemoveState = actions.onRemoveState,
                )
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
        "schedule" -> R.string.schedule_approval_risk
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
    val anchor = LocalChatPopoverAnchors.current?.model
    val haptics = LocalNexaraHaptics.current
    Box(
        modifier = modifier.height(40.dp)
            .then(if (anchor != null) Modifier.nexaraPopoverAnchor(anchor) else Modifier)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.sheet_tab_model)) {
                haptics.perform(NexaraHapticEvent.SELECTION)
                onClick()
            }
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
    var showPanel by remember { mutableStateOf(false) }
    val anchor = rememberNexaraPopoverAnchor()
    val haptics = LocalNexaraHaptics.current

    val progress = (state.used.toFloat() / state.max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val percentage = (state.used.toDouble() / state.max.coerceAtLeast(1) * 100).toInt()
    val description = stringResource(R.string.chat_context_usage_percent, percentage)
    Row(
        modifier = Modifier.height(40.dp)
            .nexaraPopoverAnchor(anchor)
            .clickable(role = Role.Button) {
                if (!anchor.consumeRecentDismiss()) {
                    if (!showPanel) haptics.perform(NexaraHapticEvent.SELECTION)
                    showPanel = !showPanel
                }
            }
            .semantics { contentDescription = description }
            .testTag(UiTags.CHAT_TOKEN_INDICATOR)
            .widthIn(min = 40.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 只有圆环时与工具行其它按钮同为 40dp 宽，圆环居中。
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        ContextCircularIndicator(
            progress = progress,
            color = if (state.used > state.max * state.autoCompactionThreshold) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        // 窄窗口工具行放不下数字，只留圆环；读屏与弹出面板仍给出具体数值。
        if (com.promenar.nexara.ui.common.LocalNexaraInspectorLayout.current) {
            Text(
                text = stringResource(R.string.chat_context_percentage, percentage),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    // 贴着圆环向上弹出，从圆环处长出；不再用下拉菜单承载面板。
    NexaraPopover(
        show = showPanel,
        anchor = anchor,
        onDismissRequest = { showPanel = false },
        side = NexaraPopoverSide.Above,
        align = NexaraPopoverAlign.Auto,
        width = 320.dp,
        title = stringResource(R.string.chat_context_usage_title),
    ) {
        ContextUsagePanel(
            state = state,
            isCompressing = isCompressing,
            onManualSummary = {
                haptics.perform(NexaraHapticEvent.CONFIRM)
                onManualSummary()
                showPanel = false
            },
        )
    }
}

/** 上下文构成的一段：名称、Token 数与在构成条上的颜色。 */
private data class ContextSegment(val label: String, val tokens: Int, val color: Color)

/**
 * 上下文用量面板：左侧环形总览（带自动压缩阈值刻度），右侧写清已用、上限与剩余；
 * 下方一条分色构成条与图例列出系统指令、历史摘要、对话与知识增强，最后是立即压缩。
 */
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
    val scheme = MaterialTheme.colorScheme
    val progressColor = if (progress > threshold) scheme.tertiary else scheme.primary
    val segments = listOf(
        ContextSegment(stringResource(R.string.chat_context_label_system), state.systemTokens, scheme.secondary),
        ContextSegment(stringResource(R.string.chat_context_label_summary), state.summaryTokens, scheme.tertiary),
        ContextSegment(stringResource(R.string.chat_context_label_active), state.activeTokens, scheme.primary),
        ContextSegment(stringResource(R.string.chat_context_label_rag), state.ragTokens, MaterialTheme.nexaraDomainColors.success),
    )
    val compressionUnavailableReason = when {
        isCompressing -> stringResource(R.string.chat_context_compress_unavailable_running)
        state.isGenerating -> stringResource(R.string.chat_context_compress_unavailable_generating)
        else -> null
    }
    Column(
        modifier = modifier.padding(NexaraSpacing.Large).testTag("context_usage_panel"),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium)) {
            ContextUsageRing(
                progress = progress,
                threshold = threshold,
                color = progressColor,
                label = stringResource(R.string.chat_context_percentage, (progress * 100).toInt()),
                modifier = Modifier.size(64.dp).semantics {
                    contentDescription = thresholdLabel
                    stateDescription = usageLabel
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.chat_context_usage_title),
                    style = NexaraTypography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.chat_context_usage_total,
                        ChatViewModel.formatTokenCount(state.used),
                        ChatViewModel.formatTokenCount(state.max),
                    ),
                    style = NexaraTypography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.chat_context_remaining,
                        ChatViewModel.formatTokenCount((state.max - state.used).coerceAtLeast(0)),
                    ),
                    style = NexaraTypography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(text = thresholdLabel, style = NexaraTypography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        ContextCompositionBar(segments = segments, max = state.max)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            segments.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Large)) {
                    pair.forEach { segment -> ContextLegendItem(segment, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        // 只在确有工具结果被折叠时说明；保留轮数是固定配置，在会话设置里调整。
        if (state.foldedToolResults > 0) {
            Text(
                text = stringResource(R.string.chat_context_folded, state.foldedToolResults),
                style = NexaraTypography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onManualSummary,
            enabled = compressionUnavailableReason == null,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .testTag("context_usage_compact_now"),
        ) {
            Icon(Icons.Rounded.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(NexaraSpacing.Small))
            Text(stringResource(R.string.chat_context_btn_compress))
        }
        if (compressionUnavailableReason != null) {
            Text(
                text = compressionUnavailableReason,
                style = NexaraTypography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 环形总览：底环、用量弧与一道自动压缩阈值刻度，中间写百分比。 */
@Composable
private fun ContextUsageRing(progress: Float, threshold: Float, color: Color, label: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val marker = MaterialTheme.colorScheme.onSurface
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(color, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val angle = Math.toRadians((-90.0 + 360.0 * threshold))
            val radius = size.minDimension / 2
            val outer = Offset(center.x + (radius * kotlin.math.cos(angle)).toFloat(), center.y + (radius * kotlin.math.sin(angle)).toFloat())
            val inner = Offset(center.x + ((radius - stroke) * kotlin.math.cos(angle)).toFloat(), center.y + ((radius - stroke) * kotlin.math.sin(angle)).toFloat())
            drawLine(marker, inner, outer, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(label, style = NexaraTypography.labelLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 分色构成条：各段按占上限的比例排开，未用部分是底色。 */
@Composable
private fun ContextCompositionBar(segments: List<ContextSegment>, max: Int) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)) {
        drawRect(track)
        var x = 0f
        val total = max.coerceAtLeast(1).toFloat()
        segments.forEach { segment ->
            val width = size.width * (segment.tokens / total).coerceIn(0f, 1f)
            if (width > 0f) {
                drawRect(segment.color, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(width.coerceAtMost(size.width - x), size.height))
                x += width
            }
        }
    }
}

@Composable
private fun ContextLegendItem(segment: ContextSegment, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(segment.color))
        Text(segment.label, style = NexaraTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            ChatViewModel.formatTokenCount(segment.tokens),
            style = NexaraTypography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    /** 窄窗口选项菜单首项的产物入口；宽窗口由检视器页签承载，传 null。 */
    artifacts: ChatTopBarArtifacts? = null,
    git: com.promenar.nexara.ui.git.GitEntrySummary.Repository? = null,
    onOpenGit: () -> Unit = {},
    runMode: com.promenar.nexara.ui.chat.runmode.RunModeBadge? = null,
    onRunMode: () -> Unit = {},
    /** 状态行中运行模式之后的会话工作区；为 null 时不显示。 */
    workspace: com.promenar.nexara.ui.workspace.SessionWorkspaceUi? = null,
    onWorkspaceStatus: () -> Unit = {},
    /** 宽窗口：顶栏只有检视器开合与菜单，文件、画布与浏览器在侧栏页签；为 null 时（窄窗口）显示文件入口。 */
    inspector: ChatTopBarInspector? = null,
    /** 本会话已打开 Agent 浏览器时的地球入口；为 null 时不显示。 */
    browser: ChatTopBarBrowser? = null,
    /** 分支会话：标题前显示分支标记；主会话仍在时点按回到主会话。 */
    branchOpenParent: (() -> Unit)? = null,
    isBranch: Boolean = false,
) {
    val haptics = LocalNexaraHaptics.current
    var showMenu by remember { mutableStateOf(false) }
    // 运行模式面板贴着点按的入口弹出：标识或菜单按钮。
    val runModeAnchor = LocalChatPopoverAnchors.current?.runMode
    val runModeChipAnchor = rememberNexaraPopoverAnchor()
    val menuAnchor = rememberNexaraPopoverAnchor()
    
    val dividerAlpha by animateFloatAsState(
        targetValue = if (showDivider) 1f else 0f,
        animationSpec = tween(150),
        label = "chat_top_divider",
    )
    Box {
    TopAppBar(
        expandedHeight = if (git != null || runMode != null || workspace != null) 64.dp else 56.dp,
        windowInsets = windowInsets,
        title = {
            // 标题独占首行；运行模式、工作区与 Git 合并为其下的状态行，标题再长也不挤动模式标识。
            val statusLine = git != null || runMode != null || workspace != null
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBranch) {
                    val branchLabel = stringResource(R.string.branch_label)
                    Icon(
                        Icons.Rounded.AccountTree,
                        contentDescription = branchLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .heightIn(min = if (statusLine) 32.dp else 48.dp)
                            .then(
                                if (branchOpenParent != null) Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.session_message_open_parent),
                                    onClick = branchOpenParent,
                                ) else Modifier,
                            )
                            .padding(end = 6.dp)
                            .size(18.dp)
                            .testTag("chat_top_branch"),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .heightIn(min = if (statusLine) 32.dp else 48.dp)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_menu_rename), onClick = onRename)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .testTag("chat_top_title"),
                )
              }
                if (statusLine) BoxWithConstraints {
                    val style = NexaraTypography.labelMedium.copy(fontFeatureSettings = "tnum")
                    val chipStyle = NexaraTypography.labelMedium
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val runLabel = runMode?.let { stringResource(it.mode.labelRes()) }
                    val workspaceName = workspace?.let { com.promenar.nexara.ui.git.shortenMiddle(workspaceStatusName(it), 18) }
                    val gitLine = git?.let { com.promenar.nexara.ui.git.gitSummaryLine(it, compactChanges = true) }
                    val gitSuffix = git?.let { com.promenar.nexara.ui.git.gitSummarySuffix(it, compactChanges = true) }.orEmpty()
                    val gitBranch = gitLine?.removeSuffix(gitSuffix)?.let { com.promenar.nexara.ui.git.shortenMiddle(it, 22) }
                    val gitCounts = gitSuffix.removePrefix(" $NexaraMetaSeparator ")
                    val available = constraints.maxWidth.toFloat()
                    // 状态行逐级收缩：先收 Git 分支名，再把 Git 收成图标，最后运行模式只留图标。
                    val fit = remember(available, runLabel, workspaceName, gitLine, style, density) {
                        fun text(value: String, textStyle: androidx.compose.ui.text.TextStyle = style) =
                            measurer.measure(value, textStyle, maxLines = 1, softWrap = false).size.width.toFloat()
                        fun dp(value: Float) = with(density) { value.dp.toPx() }
                        val gitIcon = if (gitLine != null) dp(13f) else 0f
                        val gitText = dp(3f) + gitIcon
                        statusLineFit(
                            available = available,
                            widths = StatusLineWidths(
                                runFull = runLabel?.let { dp(17f) + text(it, chipStyle) } ?: 0f,
                                runIcon = if (runLabel != null) dp(14f) else 0f,
                                workspace = workspaceName?.let { dp(16f) + text(it) } ?: 0f,
                                gitFull = if (gitLine != null) gitText + text(gitBranch.orEmpty()) + text(gitSuffix) else 0f,
                                gitSuffix = if (gitLine != null) gitText + text(gitCounts) else 0f,
                                gitIcon = gitIcon,
                                separator = text(" $NexaraMetaSeparator "),
                            ),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 运行模式标识只在侧载版出现；模式、工作区、分支各是独立的点按目标。
                        if (runMode != null) {
                            com.promenar.nexara.ui.chat.runmode.RunModeChip(
                                runMode.mode,
                                runMode.unavailable,
                                onClick = {
                                    if (runModeAnchor?.consumeRecentDismiss() != true) {
                                        runModeAnchor?.pointAt(runModeChipAnchor)
                                        onRunMode()
                                    }
                                },
                                showLabel = fit != StatusLineFit.RUN_ICON,
                                modifier = Modifier.nexaraPopoverAnchor(runModeChipAnchor),
                            )
                        }
                        if (runMode != null && (workspace != null || git != null)) {
                            Text(" $NexaraMetaSeparator ", style = style, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        }
                        if (workspace != null && workspaceName != null) {
                            ChatTopBarWorkspace(
                                workspace = workspace,
                                name = workspaceName,
                                style = style,
                                onClick = onWorkspaceStatus,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (workspace != null && git != null) {
                            Text(" $NexaraMetaSeparator ", style = style, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        }
                        // 分支 · 改动数 · ↑领先，点开 Git 面板；收缩时依次去掉分支名、只留图标，读屏始终读完整摘要。
                        if (git != null && gitLine != null) {
                            val description = stringResource(R.string.git_top_bar_cd, gitLine)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier
                                    .then(if (workspace == null) Modifier.weight(1f, fill = false) else Modifier)
                                    .heightIn(min = 24.dp)
                                    .clickable(role = Role.Button, onClick = onOpenGit)
                                    .clearAndSetSemantics { contentDescription = description }
                                    .testTag(UiTags.CHAT_GIT_LINE),
                            ) {
                                Icon(
                                    Icons.Rounded.AccountTree,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                                when (fit) {
                                    StatusLineFit.FULL -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 长分支名先从中间缩短，仍放不下时只截分支名，改动数与领先落后始终完整。
                                        Text(
                                            text = gitBranch.orEmpty(),
                                            style = style,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Text(text = gitSuffix, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                    }
                                    StatusLineFit.GIT_SUFFIX -> if (gitCounts.isNotEmpty()) {
                                        Text(text = gitCounts, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                    }
                                    StatusLineFit.GIT_ICON, StatusLineFit.RUN_ICON -> Unit
                                }
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            LocalChatNavigationIcon.current?.invoke() ?: NexaraBackButton(onClick = onBack)
        },
        actions = {
            // 宽窗口的文件、产物与浏览器都是检视器侧栏的页签，顶栏只留侧栏开合与菜单；
            // 窄窗口顶栏只留选项菜单，产物、浏览器与文件是菜单首组。
            if (inspector != null) {
                ChatTopBarAction(
                    selected = inspector.open,
                    onClick = { haptics.toggle(!inspector.open); inspector.onToggle() },
                    modifier = Modifier.testTag(ChatInspectorTags.TOGGLE),
                ) {
                    Icon(Icons.Outlined.ViewSidebar, stringResource(R.string.chat_inspector_toggle), modifier = Modifier.size(22.dp))
                }
            }
            Box(Modifier.nexaraPopoverAnchor(menuAnchor)) {
                // 浏览器收进菜单后，Agent 正在使用或需要你处理的圆点挂到菜单按钮上。
                val browserDot = browser?.mode?.let { browserStatusDot(it) }
                val browserStatus = browser?.mode?.let { browserStatusDescription(it) }
                val optionsDescription = listOfNotNull(stringResource(R.string.chat_cd_options), browserStatus).joinToString(", ")
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag(UiTags.CHAT_OPTIONS),
                ) {
                    Box {
                        Icon(
                            Icons.Rounded.MoreVert,
                            optionsDescription,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        if (browserDot != null) {
                            Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(browserDot, CircleShape))
                        }
                    }
                }
                NexaraDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    minWidth = 190.dp,
                ) {
                    artifacts?.let { entry ->
                        NexaraMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Dashboard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = { Text(stringResource(R.string.canvas_title), style = NexaraTypography.labelLarge) },
                            trailingIcon = if (entry.count > 0) {
                                { Text(entry.count.toString(), style = NexaraTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else null,
                            modifier = Modifier.testTag(UiTags.CANVAS_ENTRY),
                            onClick = {
                                showMenu = false
                                entry.onClick()
                            },
                        )
                    }
                    browser?.let { entry ->
                        NexaraMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = { Text(stringResource(R.string.browser_title), style = NexaraTypography.labelLarge) },
                            trailingIcon = browserDot?.let { dot ->
                                { Box(Modifier.size(8.dp).background(dot, CircleShape)) }
                            },
                            modifier = Modifier
                                .testTag(com.promenar.nexara.ui.browser.BrowserTags.TOP_BAR_ENTRY)
                                .then(if (browserStatus != null) Modifier.semantics { stateDescription = browserStatus } else Modifier),
                            onClick = {
                                showMenu = false
                                entry.onClick()
                            },
                        )
                    }
                    if (inspector == null) {
                        NexaraMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            text = { Text(stringResource(R.string.chat_cd_workspace), style = NexaraTypography.labelLarge) },
                            modifier = Modifier.testTag(UiTags.CHAT_MENU_WORKSPACE),
                            onClick = {
                                showMenu = false
                                onWorkspace()
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                    NexaraMenuItem(
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
                    NexaraMenuItem(
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
                    NexaraMenuItem(
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
                    NexaraMenuItem(
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
                    NexaraMenuItem(
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

/** 窄窗口选项菜单中的产物入口。 */
@Immutable
data class ChatTopBarArtifacts(val count: Int, val onClick: () -> Unit)

/** 宽窗口由检视器页签承载产物；窄窗口菜单始终有产物入口，有产物时显示数量。 */
internal fun chatTopBarArtifacts(inspectorMode: Boolean, count: Int, onOpen: () -> Unit): ChatTopBarArtifacts? =
    if (inspectorMode) null else ChatTopBarArtifacts(count.coerceAtLeast(0), onOpen)

/** 宽窗口顶栏的检视器入口状态。 */
@Immutable
data class ChatTopBarInspector(
    val open: Boolean,
    val onToggle: () -> Unit,
)

/** 顶栏的 Agent 浏览器入口：Agent 操作时挂主色圆点，需要用户处理时挂橙色圆点。 */
@Immutable
data class ChatTopBarBrowser(
    val mode: com.promenar.nexara.data.browser.BrowserMode,
    val open: Boolean,
    val onClick: () -> Unit,
)

/** 宽窗口的检视器侧栏已有常驻浏览器页签，收起时边缘条也带状态点，顶栏地球入口只在窄窗口出现。 */
internal fun chatTopBarBrowser(
    inspectorMode: Boolean,
    mode: com.promenar.nexara.data.browser.BrowserMode,
    open: Boolean,
    onOpen: () -> Unit,
): ChatTopBarBrowser? = if (inspectorMode) null else ChatTopBarBrowser(mode = mode, open = open, onClick = onOpen)

private val BrowserMaximizedInset = 24.dp

/** Agent 正在使用浏览器挂主色圆点，需要用户处理挂橙色圆点；空闲时没有圆点。 */
@Composable
private fun browserStatusDot(mode: com.promenar.nexara.data.browser.BrowserMode): Color? = when (mode) {
    com.promenar.nexara.data.browser.BrowserMode.AGENT -> MaterialTheme.colorScheme.primary
    com.promenar.nexara.data.browser.BrowserMode.HANDOFF -> MaterialTheme.colorScheme.tertiary
    else -> null
}

@Composable
private fun browserStatusDescription(mode: com.promenar.nexara.data.browser.BrowserMode): String? = when (mode) {
    com.promenar.nexara.data.browser.BrowserMode.AGENT -> stringResource(R.string.chat_inspector_browser_agent)
    com.promenar.nexara.data.browser.BrowserMode.HANDOFF -> stringResource(R.string.chat_inspector_browser_needs_you)
    else -> null
}

/** 顶栏状态行中工作区的显示名称。 */
@Composable
private fun workspaceStatusName(workspace: com.promenar.nexara.ui.workspace.SessionWorkspaceUi): String = when (workspace) {
    com.promenar.nexara.ui.workspace.SessionWorkspaceUi.Loading -> "…"
    com.promenar.nexara.ui.workspace.SessionWorkspaceUi.Private -> com.promenar.nexara.ui.workspace.privateWorkspaceLabel()
    is com.promenar.nexara.ui.workspace.SessionWorkspaceUi.Directory -> workspace.name
    is com.promenar.nexara.ui.workspace.SessionWorkspaceUi.Unavailable -> workspace.name ?: stringResource(R.string.workspace_missing_name)
}

/** 顶栏状态行中的工作区：文件夹图标加名称，不可用时换成警示色；名称取状态行剩余宽度，放不下时从末尾省略。 */
@Composable
private fun ChatTopBarWorkspace(
    workspace: com.promenar.nexara.ui.workspace.SessionWorkspaceUi,
    name: String,
    style: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = workspace is com.promenar.nexara.ui.workspace.SessionWorkspaceUi.Unavailable
    val color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.chat_top_workspace_cd, workspaceStatusName(workspace))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .heightIn(min = 24.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .testTag(UiTags.CHAT_WORKSPACE_LINE),
    ) {
        Icon(
            if (unavailable) Icons.Rounded.Warning else Icons.Outlined.Folder,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Text(text = name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 顶栏图标按钮；对应的检视器页签展开时以浅色底标出。 */
@Composable
private fun ChatTopBarAction(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = icon,
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }

    NexaraConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_dialog_rename_title),
        message = "",
        confirmText = stringResource(R.string.common_btn_confirm),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = { onConfirm(text) },
        onCancel = onDismiss,
        confirmEnabled = text.isNotBlank(),
        content = {
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
    /** 生成中把草稿排到队首，当前步骤结束后发出（长按菜单）。 */
    onSendSteering: () -> Unit = {},
    /** 停止本轮并立即发出草稿（长按菜单）。 */
    onStopAndSend: () -> Unit = {},
    /** 有待回答的提问：输入栏写的是其他回答，浮岛描边转为琥珀色。 */
    answeringQuestion: Boolean = false,
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
    /** 工具行上的执行模式盾牌；会话关闭工具时为 null，不显示。 */
    executionMode: com.promenar.nexara.ui.common.ExecutionMode? = null,
    onExecutionModeChange: (com.promenar.nexara.ui.common.ExecutionMode) -> Unit = {},
    attachmentContent: @Composable () -> Unit = {},
    /** 浮岛顶部区域（计划条），与输入区共用底色和圆角。 */
    topContent: @Composable ColumnScope.() -> Unit = {},
) {
    val haptics = LocalNexaraHaptics.current
    val shownPlaceholder = when {
        answeringQuestion -> stringResource(R.string.ask_card_input_placeholder)
        isGenerating -> stringResource(R.string.chat_input_placeholder_generating)
        else -> placeholder
    }
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
    // 生成中写好了下一条：主按钮变为排队发送，外圈转动表示本轮仍在进行；长按展开插队、停止并发送与只停止。
    val hasDraft = text.isNotBlank() || hasImages || hasDocuments
    val queueing = isGenerating && hasDraft && status in QueueableStatuses
    Column(horizontalAlignment = Alignment.End) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .composerIslandGlow(glow, material.topHighlight)
            .testTag(UiTags.CHAT_INPUT_BAR_SURFACE),
        shape = RoundedCornerShape(ComposerIslandCorner),
        color = material.container,
        border = if (answeringQuestion) BorderStroke(1.5.dp, MaterialTheme.nexaraDomainColors.warning) else material.border,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        // 输入框自带上下 12dp 留白并保证 48dp 点击高度；有附件时附件行与容器顶边另留 12dp。
        val hasAttachments = hasImages || hasDocuments || isImportingDocument
        // 尺寸动画会按矩形裁剪，放在容器内部；放在外层会把圆角投影切成直角。
        Column(Modifier.animateContentSize()) {
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
                    onClick = {
                        if (!attachmentMenuExpanded) haptics.perform(NexaraHapticEvent.SELECTION)
                        onAttachmentMenuExpandedChange(!attachmentMenuExpanded)
                    },
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
                // 附件按钮向左借了 12dp，盾牌同样左移，两者间距与其余控件一致。
                executionMode?.let { mode ->
                    ExecutionModeButton(mode, onExecutionModeChange, Modifier.offset(x = (-12).dp))
                }
                BoxWithConstraints(Modifier.weight(1f).then(if (executionMode != null) Modifier.offset(x = (-12).dp) else Modifier)) {
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
                GenerationStatusButton(
                    status = if (queueing) GenerationStatus.IDLE else effectiveStatus,
                    onSend = { if (isModelSelected) onSend() else onModelHint() },
                    onStop = onStop,
                    onDismissError = onDismissError,
                    enabled = hasDraft && !isImportingDocument,
                    isModelSelected = isModelSelected,
                    idleDescription = if (answeringQuestion) stringResource(R.string.ask_card_send_other_cd) else null,
                    queueActions = if (queueing && isModelSelected) {
                        QueueSendActions(onQueue = onSend, onSteer = onSendSteering, onStopAndSend = onStopAndSend, onStop = onStop)
                    } else {
                        null
                    },
                )
            }
        }
        }
    }
    AnimatedVisibility(
        visible = queueing,
        enter = expandVertically(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) +
            fadeIn(tween(NexaraMotion.DurationMedium)),
        exit = shrinkVertically(tween(NexaraMotion.DurationShort)) + fadeOut(tween(NexaraMotion.DurationShort)),
    ) {
        Text(
            text = stringResource(R.string.chat_queue_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, end = 12.dp).testTag(UiTags.CHAT_QUEUE_HINT),
        )
    }
    }
}

/** 排队时主按钮的四种发送方式；轻点即 [onQueue]，其余在长按菜单与无障碍操作里。 */
private class QueueSendActions(
    val onQueue: () -> Unit,
    val onSteer: () -> Unit,
    val onStopAndSend: () -> Unit,
    val onStop: () -> Unit,
)

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
    /** 生成中已有草稿时的排队操作；非空时按钮外圈转动，长按弹出发送方式菜单。 */
    queueActions: QueueSendActions? = null,
    /** 空闲时发送键的读屏描述；为 null 时为普通“发送”。 */
    idleDescription: String? = null,
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
        GenerationStatus.IDLE -> when {
            queueActions != null -> stringResource(R.string.chat_queue_send_cd)
            else -> idleDescription ?: stringResource(R.string.chat_cd_send)
        }
        GenerationStatus.UPLOADING,
        GenerationStatus.THINKING,
        GenerationStatus.RECEIVING -> stringResource(R.string.chat_cd_stop)
        GenerationStatus.COMPLETED,
        GenerationStatus.ERROR -> statusDescription
    }

    val haptics = com.promenar.nexara.ui.common.LocalNexaraHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    if (queueActions == null) menuOpen = false
    val queueLabels = queueActions?.let {
        listOf(
            stringResource(R.string.chat_queue_menu_steer) to it.onSteer,
            stringResource(R.string.chat_queue_menu_now) to it.onStopAndSend,
            stringResource(R.string.chat_queue_menu_stop) to it.onStop,
        )
    }
    val moreLabel = stringResource(R.string.chat_queue_send_more)
    val ringTrack = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
    // 排队时外圈转动：本轮仍在进行，草稿会在它之后发出。
    AnimatedVisibility(
        visible = queueActions != null,
        enter = fadeIn(tween(NexaraMotion.DurationMedium)) + scaleIn(tween(NexaraMotion.DurationMedium), initialScale = 0.85f),
        exit = fadeOut(tween(NexaraMotion.DurationShort)),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.requiredSize(46.dp).testTag(UiTags.CHAT_QUEUE_RING),
            color = MaterialTheme.colorScheme.primary,
            trackColor = ringTrack,
            strokeWidth = 2.dp,
        )
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(containerColor)
            .combinedClickable(
                enabled = status == GenerationStatus.IDLE || status == GenerationStatus.RECEIVING ||
                    status == GenerationStatus.THINKING || status == GenerationStatus.UPLOADING ||
                    status == GenerationStatus.ERROR,
                role = Role.Button,
                onLongClickLabel = if (queueActions != null) moreLabel else null,
                onLongClick = if (queueActions != null) {
                    { menuOpen = true }
                } else {
                    null
                },
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
            )
            .semantics {
                contentDescription = actionDescription
                stateDescription = statusDescription
                liveRegion = if (status == GenerationStatus.ERROR) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
                // 读屏用户不必长按：菜单里的三种方式直接作为自定义操作提供。
                queueLabels?.let { actions ->
                    customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
                }
            }
            .testTag(UiTags.CHAT_GENERATION_ACTION),
        contentAlignment = Alignment.Center,
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
    queueActions?.let { actions ->
        NexaraDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, minWidth = 220.dp) {
            QueueMenuItem(R.string.chat_queue_menu_queue, UiTags.CHAT_QUEUE_MENU_QUEUE, { menuOpen = false; actions.onQueue() }) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            QueueMenuItem(R.string.chat_queue_menu_steer, UiTags.CHAT_QUEUE_MENU_STEER, { menuOpen = false; actions.onSteer() }) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            QueueMenuItem(R.string.chat_queue_menu_now, UiTags.CHAT_QUEUE_MENU_NOW, { menuOpen = false; actions.onStopAndSend() }) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            NexaraMenuDivider()
            QueueMenuItem(R.string.chat_queue_menu_stop, UiTags.CHAT_GENERATION_STOP, { menuOpen = false; actions.onStop() }, destructive = true) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.error))
            }
        }
    }
    }
}

@Composable
private fun QueueMenuItem(
    label: Int,
    tag: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    icon: @Composable () -> Unit,
) {
    NexaraMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        leadingIcon = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } },
        destructive = destructive,
        modifier = Modifier.testTag(tag),
    )
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

package com.promenar.nexara.ui.chat

import android.app.Activity
import android.view.WindowManager
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.findModelSpec
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraCustomShapes
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.common.EditorMode
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.promenar.nexara.ui.chat.manager.skills.GeneratedImageData
import kotlinx.serialization.json.Json

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(context.applicationContext as android.app.Application))
    val uiState by chatViewModel.uiState.collectAsState()
    val inputText by chatViewModel.inputText.collectAsState()
    val tokenState by chatViewModel.tokenIndicatorState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarData by remember { mutableStateOf<com.promenar.nexara.ui.common.NexaraSnackbarData?>(null) }

    val listState = rememberLazyListState()
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showModelSettingsSheet by remember { mutableStateOf(false) }
    var showSessionPromptEditor by remember { mutableStateOf(false) }
    var showTruncateDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingTruncateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showModelHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var selectedImageUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> selectedImageUris = uris }

    LaunchedEffect(showModelHint) {
        if (showModelHint) {
            delay(2500)
            showModelHint = false
        }
    }

    val sessionTitle = uiState.session?.title ?: stringResource(R.string.chat_title_new)
    val agentName = uiState.agentName

    val pipelineGroups = remember(uiState.messages) { buildPipelineGroups(uiState.messages) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val isUserScrolledAway by remember(pipelineGroups.size) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf false

            val totalItemsCount = layoutInfo.totalItemsCount

            // 最后一项（bottom_spacer）不可见 → 用户已离开底部
            if (visibleItems.none { it.index == totalItemsCount - 1 }) return@derivedStateOf true

            // 底部判定：spacer 底部超出视口底部即代表用户已滚离底部
            val spacerItem = visibleItems.first { it.index == totalItemsCount - 1 }
            val spacerBottom = spacerItem.offset + spacerItem.size
            val viewportBottom = layoutInfo.viewportEndOffset
            // 阈值 = contentPadding bottom(48dp) + 12dp 缓冲区
            val threshold = with(density) { 60.dp.toPx() }
            spacerBottom > viewportBottom + threshold
        }
    }

    val activity = LocalContext.current as? Activity

    // 离开会话界面时持久化未发送的输入文字
    DisposableEffect(sessionId) {
        onDispose { chatViewModel.saveCurrentDraft() }
    }

    LaunchedEffect(uiState.isGenerating) {
        if (uiState.isGenerating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(sessionId) {
        chatViewModel.loadSession(sessionId)
    }

    // ═══════════════════════════════════════════════════════════
    //  智能视角追踪 — Pin to Bottom
    //  新消息发送 → 滚到底部开启追踪 → 流式输出时跟随最新行 →
    //  用户触摸介入 → 切断追踪 → FAB 点击恢复追踪
    // ═══════════════════════════════════════════════════════════

    var autoFollowEnabled by remember { mutableStateOf(true) }

    // 用户手势介入 → 切断追踪
    LaunchedEffect(isUserScrolledAway) {
        if (isUserScrolledAway) autoFollowEnabled = false
    }

    // 新用户消息 → 恢复追踪 + 滚到底部
    val latestUserMsgId = uiState.messages.filter { it.role == MessageRole.USER }.lastOrNull()?.id ?: ""
    LaunchedEffect(latestUserMsgId) {
        if (latestUserMsgId.isNotEmpty()) {
            autoFollowEnabled = true
            val groups = buildPipelineGroups(uiState.messages)
            listState.animateScrollToItem(groups.size) // 滚到最底部
        }
    }

    // 生成中自动跟随：周期检查（~5Hz），仅用户还在底部时才轻推
    LaunchedEffect(uiState.isGenerating, autoFollowEnabled) {
        if (uiState.isGenerating && autoFollowEnabled) {
            while (isActive) {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems > 0) {
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                    // 仅当：用户可以继续向下滚动 + 最后一项不完全在视口内 时才推
                    val canScroll = listState.canScrollForward
                    val lastItemInView = lastVisible != null &&
                        (lastVisible.offset + lastVisible.size) < layoutInfo.viewportEndOffset
                    if (canScroll && !lastItemInView) {
                        listState.scrollToItem(totalItems - 1)
                    }
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    // IME 键盘避让
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && uiState.messages.isNotEmpty()) {
            val groups = buildPipelineGroups(uiState.messages)
            val lastIdx = groups.size - 1
            if (lastIdx >= 0) {
                listState.animateScrollToItem(lastIdx)
            }
        }
    }

    fun isDestructive(message: Message): Boolean {
        val index = uiState.messages.indexOfFirst { it.id == message.id }
        if (index == -1) return false
        return if (message.role == MessageRole.USER) {
            // User message: Destructive if there are messages beyond its immediate AI response
            index < uiState.messages.size - 2
        } else {
            // AI message: Destructive if there are any messages after it
            index < uiState.messages.size - 1
        }
    }

    fun showUndoSnackbar(msg: String) {
        scope.launch {
            snackbarData = com.promenar.nexara.ui.common.NexaraSnackbarData(
                message = msg,
                type = com.promenar.nexara.ui.common.SnackbarType.INFO,
                actionLabel = context.getString(R.string.chat_btn_undo)
            )
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            val fontSize = uiState.session?.options?.fontSize ?: 13
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            ChatTopBar(
                title = sessionTitle,
                subtitle = if (uiState.isGenerating) stringResource(R.string.chat_status_thinking) else agentName.ifBlank { sessionTitle },
                onBack = onNavigateBack,
                onWorkspace = { showWorkspaceSheet = true },
                onSettings = { showModelSettingsSheet = true },
                onSessionPrompt = { showSessionPromptEditor = true },
                onClearHistory = { showClearDialog = true },
                onRename = { showRenameDialog = true },
                onDeleteSession = { showDeleteDialog = true }
            )
        },
        snackbarHost = {
            com.promenar.nexara.ui.common.NexaraSnackbarHost(
                hostState = snackbarHostState,
                snackbarData = snackbarData,
                onAction = {
                    chatViewModel.undoLastDeletion()
                    snackbarHostState.currentSnackbarData?.dismiss()
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                    // pipelineGroups 已在外部通过 remember 计算，此处直接引用
                    items(pipelineGroups.size, key = { pipelineGroups[it].messages.first().id }) { idx ->
                        val group = pipelineGroups[idx]
                        val isGeneratingGroup = idx == pipelineGroups.lastIndex && uiState.isGenerating

                        if (group.isUser) {
                            val msg = group.messages.first()
                            var showActionSheet by remember { mutableStateOf(false) }
                            
                            UserMessageBubble(
                                message = msg,
                                fontSize = uiState.session?.options?.fontSize ?: 13
                            )

                            // Long-click gesture handled via MessageActionSheet (tap version)
                            // For now, keep action sheet accessible via simple mechanism
                        } else {
                            PipelineBubble(
                                group = group,
                                isGenerating = isGeneratingGroup,
                                status = uiState.status,
                                streamingContent = uiState.streamingContent,
                                fontSize = uiState.session?.options?.fontSize ?: 13,
                                onContentChange = { newContent ->
                                    group.assistantMessages.lastOrNull()?.let { lastMsg ->
                                        chatViewModel.updateMessageContentOnly(lastMsg.id, newContent)
                                    }
                                }
                            )
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
                modifier = Modifier.fillMaxSize().padding(bottom = 160.dp)
            ) {
                ChatSkeleton(modifier = Modifier.fillMaxSize())
            }

            if (showTruncateDialog) {
                Dialog(onDismissRequest = { showTruncateDialog = false }) {
                    NexaraConfirmDialog(
                        title = stringResource(R.string.chat_confirm_truncate_title),
                        message = stringResource(R.string.chat_confirm_truncate_message),
                        confirmText = stringResource(R.string.common_btn_confirm),
                        onConfirm = {
                            pendingTruncateAction?.invoke()
                            showTruncateDialog = false
                            pendingTruncateAction = null
                        },
                        onCancel = {
                            showTruncateDialog = false
                            pendingTruncateAction = null
                        },
                        isDestructive = true
                    )
                }
            }

                // ── 宽幅低矮版 MD3 风格浮岛 (Optimized Solid MD3 Island) ──
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 4.dp) // 极窄外边距，显著加宽
                        .padding(bottom = 8.dp)
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
                            onModelClick = { showModelSettingsSheet = true },
                            onManualSummary = { chatViewModel.summarizeHistory() }
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
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = { selectedImageUris = selectedImageUris.toMutableList().apply { removeAt(index) } },
                                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                                        ) {
                                            Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            ChatInputBar(
                                text = inputText,
                                placeholder = if (agentName.isNotBlank()) stringResource(R.string.chat_input_placeholder, agentName) else stringResource(R.string.chat_input_placeholder_default),
                                onTextChange = { chatViewModel.updateInputText(it) },
                                onSend = {
                                    if (inputText.isNotBlank() || selectedImageUris.isNotEmpty()) {
                                        val textToSend = inputText.ifBlank { "Describe this image" }
                                        chatViewModel.sendMessage(textToSend, selectedImageUris)
                                        selectedImageUris = emptyList()
                                    }
                                },
                                status = uiState.status,
                                onStop = { chatViewModel.stopGeneration() },
                                isModelSelected = uiState.session?.modelId?.isNotBlank() == true,
                                onModelHint = { showModelHint = true },
                                onPickImage = { imagePickerLauncher.launch("image/*") },
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
                    visible = isUserScrolledAway,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            autoFollowEnabled = true
                            scope.launch {
                                val groups = buildPipelineGroups(uiState.messages)
                                listState.animateScrollToItem(groups.size)
                            }
                        },
                        containerColor = NexaraColors.SurfaceHigh,
                        contentColor = NexaraColors.Primary,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

    if (showClearDialog) {
        Dialog(onDismissRequest = { showClearDialog = false }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.chat_dialog_clear_history_title),
                message = stringResource(R.string.chat_dialog_clear_history_msg),
                confirmText = stringResource(R.string.common_btn_confirm),
                onConfirm = {
                    chatViewModel.clearHistory()
                    showClearDialog = false
                },
                onCancel = { showClearDialog = false }
            )
        }
    }

    if (showDeleteDialog) {
        Dialog(onDismissRequest = { showDeleteDialog = false }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.chat_dialog_delete_session_title),
                message = stringResource(R.string.chat_dialog_delete_session_msg),
                confirmText = stringResource(R.string.shared_btn_delete),
                onConfirm = {
                    chatViewModel.deleteSession()
                    showDeleteDialog = false
                    onNavigateBack()
                },
                onCancel = { showDeleteDialog = false }
            )
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = sessionTitle,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                chatViewModel.renameSession(newName)
                showRenameDialog = false
            }
        )
    }

    ResourceExplorerSheet(
        show = showWorkspaceSheet,
        onDismiss = { showWorkspaceSheet = false },
        sessionId = sessionId
    )

    SessionSettingsSheet(
        show = showModelSettingsSheet,
        onDismiss = { showModelSettingsSheet = false },
        sessionId = sessionId
    )

    UnifiedPromptEditor(
        show = showSessionPromptEditor,
        onDismiss = { showSessionPromptEditor = false },
        onSave = { text -> chatViewModel.updateCustomPrompt(text); showSessionPromptEditor = false },
        initialText = uiState.session?.customPrompt ?: "",
        title = "Session Prompt",
        placeholder = "Add session-specific instructions...",
        mode = EditorMode.DIALOG
    )
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

@Composable
private fun ChatInputTopBar(
    modelName: String,
    tokenState: ChatViewModel.TokenIndicatorState,
    onModelClick: () -> Unit,
    onManualSummary: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Model Indicator
        NexaraGlassCard(
            onClick = onModelClick,
            shape = RoundedCornerShape(50),
            modifier = Modifier
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

        Spacer(modifier = Modifier.weight(1f))
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
            modifier = Modifier
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
                    Text(subtitle, style = NexaraTypography.labelSmall, color = NexaraColors.OnSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, null, tint = NexaraColors.OnSurface)
            }
        },
        actions = {
            IconButton(onClick = onWorkspace) {
                Icon(Icons.Rounded.Tune, null, tint = NexaraColors.OnSurface)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, null, tint = NexaraColors.OnSurface)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(NexaraColors.SurfaceContainer)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_session_settings), style = NexaraTypography.labelMedium) },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showMenu = false
                            onSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Session Prompt", style = NexaraTypography.labelMedium) },
                        leadingIcon = { Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showMenu = false
                            onSessionPrompt()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = NexaraColors.OutlineVariant.copy(alpha = 0.3f)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_rename), style = NexaraTypography.labelMedium) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_delete_session), style = NexaraTypography.labelMedium, color = NexaraColors.Error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp), tint = NexaraColors.Error) },
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
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
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
fun ChatBubble(
    message: Message,
    isGenerating: Boolean = false,
    streamingContent: String = "",
    fontSize: Int = 13,
    onApprove: () -> Unit = {},
    onDecline: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onContentChange: ((String) -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val timestamp = remember(message.createdAt) { timeFormat.format(java.util.Date(message.createdAt)) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongClick() })
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = NexaraCustomShapes.ChatBubbleUser,
                    color = NexaraColors.SurfaceHigh,
                    border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant),
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Column {
                        if (!message.userImages.isNullOrEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                message.userImages!!.forEach { dataUrl ->
                                    coil3.compose.AsyncImage(
                                        model = dataUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                }
                            }
                        }
                        Text(
                            text = message.content,
                            style = NexaraTypography.bodyMedium.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.5).sp
                            ),
                            color = NexaraColors.OnBackground,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                Text(
                    text = timestamp,
                    style = NexaraTypography.labelSmall.copy(
                        color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    ),
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Global Timeline Axis ──
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .padding(top = 10.dp)
                        .background(NexaraColors.OutlineVariant.copy(alpha = 0.4f), CircleShape)
                )

                Column(modifier = Modifier.weight(1f)) {
                // ── 思维链 / 推理展示 ──
                if (!message.reasoning.isNullOrBlank()) {
                    ThinkingBlock(
                        reasoning = message.reasoning!!,
                        isGenerating = isGenerating,
                        fontSize = fontSize
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── 工具执行流水线时间线 ──
                if (!message.executionSteps.isNullOrEmpty()) {
                    ToolExecutionTimeline(
                        steps = message.executionSteps!!,
                        isExecuting = isGenerating
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── 审批卡片 ──
                if (!message.pendingApprovalToolIds.isNullOrEmpty()) {
                    val pendingId = message.pendingApprovalToolIds!!.firstOrNull()
                    val pendingToolName = if (pendingId != null) {
                        message.toolCalls?.find { it.id == pendingId }?.name ?: pendingId
                    } else "unknown"
                    ApprovalCard(
                        toolName = pendingToolName,
                        description = stringResource(R.string.chat_approval_desc_tool),
                        onApprove = onApprove,
                        onDecline = onDecline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── RAG 检索指示器 ──
                if (message.role == MessageRole.ASSISTANT && message.content.isEmpty()) {
                    RagOmniIndicator(
                        progress = message.ragProgress,
                        metadata = message.ragMetadata,
                        references = message.ragReferences,
                        kgPaths = message.kgPaths,
                        isLoading = isGenerating && message.content.isEmpty()
                    )
                } else if (message.role == MessageRole.ASSISTANT && (!message.ragReferences.isNullOrEmpty() || !message.kgPaths.isNullOrEmpty())) {
                    RagOmniIndicator(
                        progress = message.ragProgress,
                        metadata = message.ragMetadata,
                        references = message.ragReferences,
                        kgPaths = message.kgPaths,
                        isLoading = false
                    )
                }

                // ── 流式工具调用选择指示器 ──
                if (isGenerating && !message.toolCalls.isNullOrEmpty()) {
                    val toolNames = message.toolCalls!!.joinToString(", ") { it.name }
                    SummaryIndicator(
                        text = "${stringResource(R.string.chat_tool_selecting)} $toolNames"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── 消息内容：区分 TOOL 结果与普通消息 ──
                if (message.role == MessageRole.TOOL) {
                    var toolExpanded by remember { mutableStateOf(true) }
                    NexaraGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toolExpanded = !toolExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Terminal,
                                    null,
                                    tint = NexaraColors.Tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${stringResource(R.string.chat_tool_result)}: ${message.name ?: "unknown"}",
                                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = NexaraColors.Tertiary
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    if (toolExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    null,
                                    tint = NexaraColors.OnSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            AnimatedVisibility(
                                visible = toolExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                MarkdownText(
                                    markdown = message.content,
                                    isStreaming = false,
                                    fontSize = fontSize,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                } else {
                    val displayContent = if (isGenerating && streamingContent.isNotEmpty()) streamingContent else message.content
                    MarkdownText(
                        markdown = displayContent,
                        isStreaming = isGenerating,
                        fontSize = fontSize,
                        onContentChange = onContentChange,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // ── 生成图片展示 ──
                val generatedImages = remember(message.images) {
                    parseGeneratedImages(message.images)
                }
                if (generatedImages.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        generatedImages.forEach { imageData ->
                            if (imageData.localPath != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(NexaraColors.SurfaceContainer)
                                ) {
                                    coil3.compose.AsyncImage(
                                        model = imageData.localPath,
                                        contentDescription = imageData.revisedPrompt ?: "Generated image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                                if (!imageData.revisedPrompt.isNullOrBlank()) {
                                    Text(
                                        text = imageData.revisedPrompt!!,
                                        style = NexaraTypography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f)
                                        ),
                                        modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 元信息行 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    val metaStyle = NexaraTypography.labelSmall.copy(
                        color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    if (!message.modelId.isNullOrBlank()) {
                        val friendlyModelName = remember(message.modelId) {
                            findModelSpec(message.modelId!!)?.note ?: message.modelId!!
                        }
                        Text(
                            text = friendlyModelName,
                            style = metaStyle,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = timestamp,
                        style = metaStyle
                    )
                }

                // ── 错误信息 ──
                if (message.isError && message.errorMessage != null) {
                    Text(
                        text = message.errorMessage!!,
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                        color = NexaraColors.Error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
}

/**
 * 解析 Message.images JSON 字段为 GeneratedImageData 列表。
 * 用于在 ChatBubble 中渲染生成的图片。
 */
private fun parseGeneratedImages(imagesJson: String?): List<GeneratedImageData> {
    if (imagesJson.isNullOrBlank()) return emptyList()
    return try {
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString<List<GeneratedImageData>>(imagesJson)
    } catch (_: Exception) {
        emptyList()
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
                modifier = Modifier.size(36.dp),
                enabled = !isGenerating
            ) {
                Icon(
                    Icons.Rounded.AddPhotoAlternate,
                    null,
                    tint = if (hasImages) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    message: Message,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onResend: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf(message.content) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaraColors.SurfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(color = NexaraColors.OutlineVariant) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            if (isEditing) {
                Text(
                    stringResource(R.string.chat_action_edit),
                    style = NexaraTypography.titleMedium,
                    color = NexaraColors.OnSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                BasicTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 300.dp)
                        .background(NexaraColors.SurfaceVariant, NexaraShapes.medium)
                        .padding(12.dp),
                    textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                    cursorBrush = SolidColor(NexaraColors.Primary),
                    decorationBox = { innerTextField ->
                        innerTextField()
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { isEditing = false }) {
                        Text(stringResource(R.string.common_decline), color = NexaraColors.OnSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { onEdit(editContent) },
                        colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Primary)
                    ) {
                        Text(stringResource(R.string.common_approve))
                    }
                }
            } else {
                ActionMenuItem(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.chat_action_copy),
                    onClick = onCopy
                )
                ActionMenuItem(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.chat_action_edit),
                    onClick = { isEditing = true }
                )
                if (message.role == MessageRole.USER) {
                    ActionMenuItem(
                        icon = Icons.Rounded.Refresh,
                        label = stringResource(R.string.chat_action_resend),
                        onClick = onResend
                    )
                } else {
                    ActionMenuItem(
                        icon = Icons.Rounded.Refresh,
                        label = stringResource(R.string.chat_action_regenerate),
                        onClick = onResend
                    )
                }
                ActionMenuItem(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.chat_action_delete),
                    color = NexaraColors.Error,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun ActionMenuItem(
    icon: ImageVector,
    label: String,
    color: Color = NexaraColors.OnSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, style = NexaraTypography.bodyLarge, color = color)
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

    IconButton(
        onClick = {
            if (status == GenerationStatus.IDLE) onSend()
            else if (status != GenerationStatus.COMPLETED && status != GenerationStatus.ERROR) onStop()
        },
        modifier = Modifier
            .size(40.dp)
            .offset(x = if (status == GenerationStatus.ERROR) shakeOffset.dp else 0.dp)
            .clip(CircleShape)
            .background(containerColor),
        enabled = (status == GenerationStatus.IDLE || status == GenerationStatus.RECEIVING || status == GenerationStatus.THINKING || status == GenerationStatus.UPLOADING)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
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

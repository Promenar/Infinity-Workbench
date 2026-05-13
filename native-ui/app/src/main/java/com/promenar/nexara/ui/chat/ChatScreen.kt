package com.promenar.nexara.ui.chat

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraCustomShapes
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    var showTruncateDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingTruncateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()

    val sessionTitle = uiState.session?.title ?: stringResource(R.string.chat_title_new)
    val agentName = uiState.agentName

    val isUserScrolledAway by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible < total - 1
        }
    }

    val activity = LocalContext.current as? Activity
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

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty() && !isUserScrolledAway) {
            listState.animateScrollToItem(uiState.messages.size - 1)
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
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        var showActionSheet by remember { mutableStateOf(false) }
                        
                        ChatBubble(
                            message = message,
                            isGenerating = uiState.isGenerating && message.id == uiState.messages.lastOrNull()?.id,
                            streamingContent = uiState.streamingContent,
                            fontSize = uiState.session?.options?.fontSize ?: 13,
                            onLongClick = { showActionSheet = true },
                            onApprove = { chatViewModel.approveRequest() },
                            onDecline = { chatViewModel.rejectRequest() },
                            onContentChange = { newContent ->
                                chatViewModel.updateMessageContentOnly(message.id, newContent)
                            }
                        )

                        if (showActionSheet) {
                            MessageActionSheet(
                                message = message,
                                onDismiss = { showActionSheet = false },
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Nexara", message.content)
                                    clipboard.setPrimaryClip(clip)
                                    showActionSheet = false
                                },
                                onEdit = { newContent ->
                                    val action = { 
                                        chatViewModel.editMessage(message.id, newContent)
                                        if (isDestructive(message)) {
                                            showUndoSnackbar(context.getString(R.string.chat_undo_truncate))
                                        }
                                    }
                                    if (isDestructive(message)) {
                                        pendingTruncateAction = action
                                        showTruncateDialog = true
                                    } else {
                                        action()
                                    }
                                    showActionSheet = false
                                },
                                onDelete = {
                                    chatViewModel.deleteMessage(message.id)
                                    showUndoSnackbar(context.getString(R.string.chat_undo_delete))
                                    showActionSheet = false
                                },
                                onResend = {
                                    val action = { 
                                        chatViewModel.resendMessage(message.id)
                                        if (isDestructive(message)) {
                                            showUndoSnackbar(context.getString(R.string.chat_undo_truncate))
                                        }
                                    }
                                    if (isDestructive(message)) {
                                        pendingTruncateAction = action
                                        showTruncateDialog = true
                                    } else {
                                        action()
                                    }
                                    showActionSheet = false
                                }
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.isLoading && uiState.messages.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ChatSkeleton(modifier = Modifier.weight(1f).fillMaxWidth())
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChatInputTopBar(
                        modelName = uiState.session?.modelId ?: "",
                        tokenState = tokenState,
                        onModelClick = { showModelSettingsSheet = true },
                        onManualSummary = { chatViewModel.summarizeHistory() }
                    )

                    ChatInputBar(
                        text = inputText,
                        placeholder = if (agentName.isNotBlank()) stringResource(R.string.chat_input_placeholder, agentName) else stringResource(R.string.chat_input_placeholder_default),
                        onTextChange = { chatViewModel.updateInputText(it) },
                        onSend = {
                            if (inputText.isNotBlank()) {
                                chatViewModel.sendMessage(inputText)
                            }
                        },
                        status = uiState.status,
                        onStop = { chatViewModel.stopGeneration() }
                    )
                }
            }

            AnimatedVisibility(
                visible = isUserScrolledAway,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(uiState.messages.size - 1) } },
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

    WorkspaceSheet(
        show = showWorkspaceSheet,
        onDismiss = { showWorkspaceSheet = false },
        sessionId = sessionId
    )

    SessionSettingsSheet(
        show = showModelSettingsSheet,
        onDismiss = { showModelSettingsSheet = false },
        sessionId = sessionId
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
            DropdownMenu(
                expanded = showTooltip,
                onDismissRequest = { showTooltip = false },
                modifier = Modifier.background(NexaraColors.SurfaceContainer).width(200.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.chat_context_usage_title), style = NexaraTypography.titleSmall, color = NexaraColors.Primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    TokenDetailRow(stringResource(R.string.chat_context_label_system), state.systemTokens)
                    TokenDetailRow(stringResource(R.string.chat_context_label_summary), state.summaryTokens)
                    TokenDetailRow(stringResource(R.string.chat_context_label_active), state.activeTokens)
                    TokenDetailRow(stringResource(R.string.chat_context_label_rag), state.ragTokens)
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = NexaraColors.OutlineVariant)
                    
                    Button(
                        onClick = {
                            onManualSummary()
                            showTooltip = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Primary)
                    ) {
                        Text(stringResource(R.string.chat_context_btn_compress), style = NexaraTypography.labelMedium)
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
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!message.reasoning.isNullOrBlank()) {
                    ThinkingBlock(
                        reasoning = message.reasoning!!,
                        isGenerating = isGenerating,
                        fontSize = fontSize
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (message.role == MessageRole.ASSISTANT && message.content.isEmpty()) {
                    // Show RAG progress if enabled and we have progress/references
                    RagOmniIndicator(
                        progress = message.ragProgress,
                        metadata = message.ragMetadata,
                        references = message.ragReferences,
                        isLoading = isGenerating && message.content.isEmpty()
                    )
                } else if (message.role == MessageRole.ASSISTANT && message.ragReferences != null && message.ragReferences!!.isNotEmpty()) {
                    // Show references even if content has started
                    RagOmniIndicator(
                        progress = message.ragProgress,
                        metadata = message.ragMetadata,
                        references = message.ragReferences,
                        isLoading = false
                    )
                }

                val displayContent = if (isGenerating && streamingContent.isNotEmpty()) streamingContent else message.content
                MarkdownText(
                    markdown = displayContent,
                    isStreaming = isGenerating,
                    fontSize = fontSize,
                    onContentChange = onContentChange,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    val metaStyle = NexaraTypography.labelSmall.copy(
                        color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    if (!message.modelId.isNullOrBlank()) {
                        Text(
                            text = message.modelId!!,
                            style = metaStyle,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = timestamp,
                        style = metaStyle
                    )
                }

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


@Composable
fun ChatInputBar(
    text: String,
    placeholder: String = "",
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    status: GenerationStatus = GenerationStatus.IDLE,
    onStop: () -> Unit = {}
) {
    val isGenerating = status != GenerationStatus.IDLE
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = NexaraShapes.extraLarge as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                onSend = onSend,
                onStop = onStop,
                enabled = text.isNotBlank()
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
    enabled: Boolean
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
            GenerationStatus.IDLE -> if (enabled) NexaraColors.Primary else NexaraColors.SurfaceHighest
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
            GenerationStatus.IDLE -> if (enabled) NexaraColors.OnPrimary else NexaraColors.OnSurfaceVariant
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

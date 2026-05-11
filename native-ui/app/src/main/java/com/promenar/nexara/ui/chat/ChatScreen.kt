package com.promenar.nexara.ui.chat

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.ui.common.MarkdownText
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
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSpaSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(context.applicationContext as android.app.Application))
    val uiState by chatViewModel.uiState.collectAsState()
    val inputText by chatViewModel.inputText.collectAsState()
    val tokenState by chatViewModel.tokenIndicatorState.collectAsState()

    val listState = rememberLazyListState()
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showModelSettingsSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sessionTitle = uiState.session?.title ?: stringResource(R.string.chat_title_new)
    val agentName = uiState.session?.agentId?.let { aid ->
        aid.substringAfter("agent_").replaceFirstChar { it.uppercase() }
    } ?: ""

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

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            ChatTopBar(
                title = sessionTitle,
                subtitle = if (uiState.isGenerating) stringResource(R.string.chat_status_thinking) else agentName,
                onBack = onNavigateBack,
                onSettings = { showWorkspaceSheet = true },
                onMenuClick = { showMenu = true }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                            onLongClick = { showActionSheet = true },
                            onApprove = { chatViewModel.approveRequest() },
                            onDecline = { chatViewModel.rejectRequest() }
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
                                    chatViewModel.editMessage(message.id, newContent)
                                    showActionSheet = false
                                },
                                onDelete = {
                                    chatViewModel.deleteMessage(message.id)
                                    showActionSheet = false
                                },
                                onResend = {
                                    chatViewModel.resendMessage(message.id)
                                    showActionSheet = false
                                }
                            )
                        }
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
                        isGenerating = uiState.isGenerating,
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

            // TopBar Dropdown Menu
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 16.dp)) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(NexaraColors.SurfaceContainer)
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear History", style = NexaraTypography.labelMedium) },
                        leadingIcon = { Icon(Icons.Rounded.ClearAll, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            // TODO: Implement clearHistory() in ChatViewModel
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", style = NexaraTypography.labelMedium) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            // TODO: Show rename dialog
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Session", style = NexaraTypography.labelMedium, color = NexaraColors.Error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp), tint = NexaraColors.Error) },
                        onClick = {
                            // TODO: Implement deleteSession() in ChatViewModel
                            onNavigateBack()
                            showMenu = false
                        }
                    )
                }
            }
        }
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
                    Text("Context Usage", style = NexaraTypography.titleSmall, color = NexaraColors.Primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    TokenDetailRow("System", state.systemTokens)
                    TokenDetailRow("Summary", state.summaryTokens)
                    TokenDetailRow("Active", state.activeTokens)
                    TokenDetailRow("RAG", state.ragTokens)
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = NexaraColors.OutlineVariant)
                    
                    Button(
                        onClick = {
                            onManualSummary()
                            showTooltip = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Primary)
                    ) {
                        Text("Compress History", style = NexaraTypography.labelMedium)
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
    onSettings: () -> Unit,
    onMenuClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = NexaraTypography.titleMedium, color = NexaraColors.OnSurface)
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
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Tune, null, tint = NexaraColors.OnSurface)
            }
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Rounded.MoreVert, null, tint = NexaraColors.OnSurface)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
fun ChatBubble(
    message: Message,
    isGenerating: Boolean = false,
    onApprove: () -> Unit = {},
    onDecline: () -> Unit = {},
    onLongClick: () -> Unit = {}
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
                        style = NexaraTypography.bodyMedium,
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
                        isGenerating = isGenerating
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                MarkdownText(
                    markdown = message.content,
                    isStreaming = isGenerating,
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
    isGenerating: Boolean = false,
    onStop: () -> Unit = {}
) {
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

            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NexaraColors.Error)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.chat_cd_stop),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (text.isNotBlank()) NexaraColors.Primary else NexaraColors.SurfaceHighest)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.chat_cd_send),
                        tint = if (text.isNotBlank()) NexaraColors.OnPrimary else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
                if (message.role == MessageRole.USER) {
                    ActionMenuItem(
                        icon = Icons.Rounded.Edit,
                        label = stringResource(R.string.chat_action_edit),
                        onClick = { isEditing = true }
                    )
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

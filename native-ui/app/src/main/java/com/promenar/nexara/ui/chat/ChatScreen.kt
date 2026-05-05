package com.promenar.nexara.ui.chat

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
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

    val listState = rememberLazyListState()
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
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

    if (showSettingsSheet) {
        SessionSettingsSheet(
            show = showSettingsSheet,
            onDismiss = { showSettingsSheet = false },
            sessionId = sessionId
        )
    }

    if (showWorkspaceSheet) {
        WorkspaceSheet(
            show = showWorkspaceSheet,
            onDismiss = { showWorkspaceSheet = false },
            sessionId = sessionId
        )
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            sessionTitle,
                            style = NexaraTypography.headlineMedium.copy(fontSize = 18.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = NexaraColors.OnBackground
                        )
                        if (agentName.isNotBlank()) {
                            Text(
                                agentName,
                                style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_cd_back), tint = NexaraColors.OnBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { showWorkspaceSheet = true }) {
                        Icon(Icons.Rounded.WorkspacePremium, contentDescription = stringResource(R.string.chat_cd_workspace), tint = NexaraColors.OnSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_cd_options), tint = NexaraColors.OnBackground)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = NexaraColors.SurfaceContainer
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_session_settings), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurface) },
                                onClick = { showMenu = false; onNavigateToSettings() },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_token_stats), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurface) },
                                onClick = { showMenu = false; showSettingsSheet = true },
                                leadingIcon = { Icon(Icons.Rounded.Token, null, tint = NexaraColors.Tertiary, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_super_assistant), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurface) },
                                onClick = { showMenu = false; onNavigateToSpaSettings() },
                                leadingIcon = { Icon(Icons.Rounded.SmartToy, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_export), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurface) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Download, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnBackground,
                    navigationIconContentColor = NexaraColors.OnBackground,
                    actionIconContentColor = NexaraColors.OnBackground
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    containerColor = NexaraColors.ErrorContainer
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiState.error!!, color = NexaraColors.OnErrorContainer, modifier = Modifier.weight(1f))
                        IconButton(onClick = { chatViewModel.clearError() }) {
                            Icon(Icons.Rounded.Close, null, tint = NexaraColors.OnErrorContainer, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 140.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(uiState.messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        isGenerating = uiState.isGenerating && msg.id == uiState.messages.lastOrNull()?.id && msg.role == MessageRole.ASSISTANT
                    )
                }
            }

            AnimatedVisibility(
                visible = isUserScrolledAway,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 152.dp),
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (uiState.messages.isNotEmpty()) {
                                listState.animateScrollToItem(uiState.messages.size - 1)
                            }
                        }
                    },
                    containerColor = NexaraColors.SurfaceHigh,
                    contentColor = NexaraColors.Primary,
                    modifier = Modifier.size(40.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.chat_cd_scroll_bottom), modifier = Modifier.size(22.dp))
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatInputTopBar(
                    modelName = uiState.session?.modelId ?: "gpt-4o",
                    onModelClick = { showSettingsSheet = true },
                    onToolClick = { showSettingsSheet = true },
                    onWorkspaceClick = { showWorkspaceSheet = true }
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
    }
}

@Composable
private fun ChatInputTopBar(
    modelName: String,
    onModelClick: () -> Unit,
    onToolClick: () -> Unit,
    onWorkspaceClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                Text(modelName, style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface)
            }
        }

        NexaraGlassCard(
            onClick = onToolClick,
            shape = RoundedCornerShape(50),
            modifier = Modifier
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Rounded.Construction, null, tint = NexaraColors.Tertiary, modifier = Modifier.size(14.dp))
            }
        }

        NexaraGlassCard(
            onClick = onWorkspaceClick,
            shape = RoundedCornerShape(50),
            modifier = Modifier
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Rounded.Image, null, tint = NexaraColors.StatusSuccess, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (true) {
            NexaraGlassCard(
                onClick = { },
                shape = RoundedCornerShape(50),
                modifier = Modifier
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, null, tint = NexaraColors.Primary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: Message,
    isGenerating: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(530),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.role == MessageRole.USER) Arrangement.End else Arrangement.Start
    ) {
        if (message.role == MessageRole.USER) {
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
        } else {
            Column(modifier = Modifier.widthIn(max = 320.dp)) {
                if (message.content.isNotBlank() || isGenerating) {
                    Text(
                        text = message.content,
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnBackground,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (isGenerating && message.content.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .width(12.dp)
                            .height(16.dp)
                            .alpha(cursorAlpha)
                            .background(NexaraColors.Primary, RoundedCornerShape(2.dp))
                    )
                } else if (isGenerating) {
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp, top = 4.dp)
                            .width(12.dp)
                            .height(16.dp)
                            .alpha(cursorAlpha)
                            .background(NexaraColors.Primary, RoundedCornerShape(2.dp))
                    )
                }

                if (message.isError && message.errorMessage != null) {
                    Text(
                        text = message.errorMessage!!,
                        style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                        color = NexaraColors.Error,
                        modifier = Modifier.padding(top = 4.dp)
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

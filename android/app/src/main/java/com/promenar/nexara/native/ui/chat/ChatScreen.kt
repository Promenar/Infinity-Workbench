package com.promenar.nexara.native.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.nexara.native.ui.common.NexaraGlassCard
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraCustomShapes
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

// Temporary Mock Data
data class ChatMessage(val isUser: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    
    val mockMessages = listOf(
        ChatMessage(true, "Show me how to implement a floating glassmorphic input in Compose."),
        ChatMessage(false, "Certainly. A floating glassmorphic input typically relies on a Box overlaid at the bottom of your Scaffold, utilizing background blur and subtle borders. Here is an example implementation:")
    )

    // Using Scaffold to automatically manage the TopAppBar and the layout structure.
    // However, because we want the Input to float over the content and the TopBar to blur,
    // we need to handle the content overlapping manually with Box.
    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            // Glassmorphic TopBar
            TopAppBar(
                title = { 
                    Text("Super Assistant", style = NexaraTypography.headlineMedium) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
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
            // Chat List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp, // safe-margin
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 100.dp // Padding for the floating input
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(mockMessages) { msg ->
                    ChatBubble(message = msg)
                }
            }

            // Floating Input Area
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Apply ime padding so the input rises with the keyboard!
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(horizontal = 20.dp, vertical = 16.dp) // safe-margin and rhythm
            ) {
                ChatInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = { 
                        if(inputText.isNotBlank()) inputText = "" 
                    }
                )
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    // Spacer requirement: 8px between same sender, 16px between different. (Handled by LazyColumn spacedBy for now)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (message.isUser) {
            // User Bubble: Zinc-800/900 background, 0.5px border, Right aligned, pointy tail
            Surface(
                shape = NexaraCustomShapes.ChatBubbleUser,
                color = NexaraColors.SurfaceHigh, // Zinc-800ish equivalent
                border = androidx.compose.foundation.BorderStroke(0.5.dp, NexaraColors.OutlineVariant),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.text,
                    style = NexaraTypography.bodyMedium, // The 15px/25px Gold Standard
                    color = NexaraColors.OnBackground,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            // Assistant Bubble: Transparent background, structured typography
            Box(
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    text = message.text,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnBackground,
                    modifier = Modifier.padding(vertical = 8.dp) // No horizontal padding needed as per spec, aligns with edge
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    // 24px Pill shape, Glassmorphic
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
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            text = "Message Super Assistant...",
                            style = NexaraTypography.bodyMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )

            // Send Button: Solid Primary Color (No glass)
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (text.isNotBlank()) NexaraColors.Primary else NexaraColors.SurfaceHighest)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) NexaraColors.OnPrimary else NexaraColors.OnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

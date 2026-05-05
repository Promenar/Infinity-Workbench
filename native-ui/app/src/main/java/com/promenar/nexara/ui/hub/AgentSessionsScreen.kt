package com.promenar.nexara.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.SwipeableItem
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentSessionsScreen(
    agentId: String,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onNavigateToAgentEdit: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SessionListViewModel = viewModel(factory = SessionListViewModel.factory(context.applicationContext as android.app.Application))
    val sessions by viewModel.sessions.collectAsState()
    val agentName by viewModel.agentName.collectAsState()
    val agentColor by viewModel.agentColor.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(agentId) {
        viewModel.loadSessions(agentId)
    }

    val parsedAgentColor = try {
        Color(android.graphics.Color.parseColor(agentColor))
    } catch (e: Exception) {
        NexaraColors.Primary
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.createSession(agentId) { sessionId ->
                        onNavigateToChat(sessionId)
                    }
                },
                containerColor = parsedAgentColor,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.sessions_cd_new), modifier = Modifier.size(28.dp))
            }
        }
    ) { paddingValues ->
        if (sessions.isEmpty() && searchQuery.isEmpty()) {
            EmptySessionsState(
                onCreateSession = {
                    viewModel.createSession(agentId) { sessionId ->
                        onNavigateToChat(sessionId)
                    }
                },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp,
                    top = 8.dp, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AgentSessionHeader(
                        agentName = agentName,
                        sessionCount = sessions.size,
                        onBack = onNavigateBack,
                        onSettings = onNavigateToAgentEdit
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                stickyHeader {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.CanvasBackground.copy(alpha = 0.9f))
                            .padding(bottom = 12.dp)
                    ) {
                        NexaraSearchBar(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.searchSessions(it)
                            },
                            placeholder = stringResource(R.string.sessions_search_placeholder)
                        )
                    }
                }

                itemsIndexed(sessions, key = { _, s -> s.id }) { _, session ->
                    val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                    val timeString = formatter.format(Date(session.updatedAt))

                    SwipeableItem(
                        onPin = { viewModel.pinSession(session.id) },
                        onDelete = { viewModel.deleteSession(session.id) },
                        isPinned = session.isPinned
                    ) {
                        SessionCard(
                            title = session.title,
                            time = timeString,
                            preview = session.lastMessage,
                            isPinned = session.isPinned,
                            onClick = {
                                viewModel.selectSession(session.id)
                                onNavigateToChat(session.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSessionHeader(
    agentName: String,
    sessionCount: Int,
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(NexaraColors.GlassSurface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_cd_back),
                tint = NexaraColors.OnSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agentName,
                style = NexaraTypography.headlineLarge,
                color = NexaraColors.OnSurface
            )
            Text(
                text = stringResource(R.string.sessions_count_format, sessionCount),
                style = NexaraTypography.labelMedium.copy(
                    color = NexaraColors.OnSurfaceVariant
                )
            )
        }

        IconButton(onClick = onSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.sessions_cd_settings),
                tint = NexaraColors.OnSurface
            )
        }
    }
}

@Composable
private fun SessionCard(
    title: String,
    time: String,
    preview: String?,
    isPinned: Boolean = false,
    onClick: () -> Unit
) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = title,
                    style = NexaraTypography.headlineMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = time,
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
            }

            if (!preview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = preview,
                    style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }

            if (isPinned) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(NexaraColors.GlassSurface)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sessions_tag_pinned),
                            style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                            color = NexaraColors.Primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySessionsState(
    onCreateSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "💬", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.sessions_empty_title),
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sessions_empty_subtitle),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(
                onClick = onCreateSession,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = NexaraColors.Primary.copy(alpha = 0.15f),
                    contentColor = NexaraColors.Primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.sessions_btn_new))
            }
        }
    }
}

package com.promenar.nexara.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

enum class AppTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    CHAT(R.string.nav_tab_chat, Icons.Rounded.ChatBubble),
    LIBRARY(R.string.nav_tab_library, Icons.Rounded.LocalLibrary),
    SETTINGS(R.string.nav_tab_settings, Icons.Rounded.Settings)
}

internal fun shouldUseNavigationRail(width: androidx.compose.ui.unit.Dp): Boolean = width >= 600.dp

internal fun constrainedBodyWidth(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    minOf(available, 960.dp)

@Composable
fun MainTabScaffold(
    onNavigateToSecondary: (String) -> Unit,
    onNavigateToSessionList: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CHAT) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        AdaptiveNavigationSurface(
            expanded = shouldUseNavigationRail(maxWidth),
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
        ) {
            when (selectedTab) {
                AppTab.CHAT -> com.promenar.nexara.ui.hub.AgentHubScreen(
                    onNavigateToSessionList = onNavigateToSessionList,
                    onNavigateToAgentEdit = onNavigateToAgentEdit
                )
                AppTab.LIBRARY -> com.promenar.nexara.ui.rag.RagHomeScreen(
                    onNavigateToFolder = { folderId, folderName ->
                        onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.ragFolder(folderId, folderName))
                    },
                    onNavigateToConfig = { onNavigateToSecondary("rag_global_config") },
                    onNavigateToGraph = { onNavigateToSecondary("knowledge_graph") },
                    onNavigateToDocEditor = { rootUuid, docId ->
                        onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.docEditor(rootUuid, docId))
                    }
                )
                AppTab.SETTINGS -> com.promenar.nexara.ui.hub.UserSettingsHomeScreen(onNavigateToSecondary = onNavigateToSecondary)
            }
        }
    }
}

@Composable
internal fun AdaptiveNavigationSurface(
    expanded: Boolean,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    content: @Composable () -> Unit,
) {
    if (expanded) {
        Row(modifier = Modifier.fillMaxSize().background(NexaraColors.CanvasBackground)) {
            NavigationRail(
                modifier = Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("main_navigation_rail"),
                containerColor = NexaraColors.CanvasBackground,
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                AppTab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.titleRes)) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }
            AdaptiveBody(modifier = Modifier.weight(1f), content = content)
        }
    } else {
        Scaffold(
            containerColor = NexaraColors.CanvasBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NexaraBottomNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                )
            },
        ) { paddingValues ->
            AdaptiveBody(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                content = content,
            )
        }
    }
}

@Composable
private fun AdaptiveBody(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxHeight()
                .fillMaxWidth()
        ) { content() }
    }
}

@Composable
private fun NexaraBottomNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("main_bottom_navigation")
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(NexaraColors.CanvasBackground.copy(alpha = 0.8f))
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(NexaraColors.GlassBorder)
                .align(Alignment.TopCenter)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                TabItem(
                    tab = tab,
                    isSelected = isSelected,
                    onClick = { onTabSelected(tab) }
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "TabScale"
    )

    val contentColor = if (isSelected) NexaraColors.Primary else NexaraColors.Outline
    val glowColor = if (isSelected) NexaraColors.Primary.copy(alpha = 0.5f) else Color.Transparent

    Column(
        modifier = Modifier
            .scale(scale)
            .minimumInteractiveComponentSize()
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(24.dp)
                .drawBehind {
                    if (isSelected) {
                        drawIntoCanvas { canvas ->
                            val paint = Paint().apply {
                                color = glowColor
                            }
                            val frameworkPaint = paint.asFrameworkPaint()
                            frameworkPaint.setShadowLayer(
                                8.dp.toPx(),
                                0f, 0f,
                                android.graphics.Color.argb(128, 192, 193, 255)
                            )
                            canvas.drawCircle(center, center.x * 0.8f, paint)
                        }
                    }
                }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(tab.titleRes),
            style = NexaraTypography.labelMedium.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp
            ),
            color = contentColor
        )
    }
}

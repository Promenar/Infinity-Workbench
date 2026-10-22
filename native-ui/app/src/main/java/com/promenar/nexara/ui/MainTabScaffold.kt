package com.promenar.nexara.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraHapticEvent
import com.promenar.nexara.R

enum class AppTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    CHAT(R.string.nav_tab_chat, Icons.Rounded.ChatBubble),
    LIBRARY(R.string.nav_tab_library, Icons.Rounded.LocalLibrary),
    SETTINGS(R.string.nav_tab_settings, Icons.Rounded.Settings)
}

internal fun shouldUseNavigationRail(width: androidx.compose.ui.unit.Dp): Boolean = width >= 600.dp

internal fun constrainedBodyWidth(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    minOf(available, 960.dp)

internal fun bottomNavigationTabWeight(selected: Boolean): Float = if (selected) 1.45f else 1f

@Composable
fun MainTabScaffold(
    onNavigateToSecondary: (String) -> Unit,
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
            AnimatedContent(
                targetState = selectedTab,
                // 同级标签之间先淡出再淡入，避免两页内容叠在一起。
                transitionSpec = { NexaraMotion.fadeThrough() },
                label = "main-tab-content",
            ) { tab -> when (tab) {
                AppTab.CHAT -> com.promenar.nexara.ui.hub.AgentHubScreen(
                    onNavigateToChat = onNavigateToChat,
                    onNavigateToAgentEdit = onNavigateToAgentEdit
                )
                AppTab.LIBRARY -> com.promenar.nexara.ui.rag.RagHomeScreen(
                    onNavigateToFolder = { folderId, _ ->
                        onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.ragFolder(folderId))
                    },
                    onNavigateToConfig = {
                        onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.DEFAULT_MODELS)
                    },
                    onNavigateToGraph = { onNavigateToSecondary("knowledge_graph") },
                    onNavigateToDocEditor = { rootUuid, docId ->
                        onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.docEditor(rootUuid, docId))
                    }
                )
                AppTab.SETTINGS -> com.promenar.nexara.ui.hub.UserSettingsHomeScreen(onNavigateToSecondary = onNavigateToSecondary)
            } }
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
        Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavigationRail(
                modifier = Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("main_navigation_rail"),
                containerColor = MaterialTheme.colorScheme.surface,
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
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .semantics { role = Role.Tab }
                            .testTag(navigationTabTag(tab)),
                    )
                }
            }
            AdaptiveBody(modifier = Modifier.weight(1f), content = content)
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                FloatingNavigationDock(
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
        modifier = modifier
            .fillMaxSize()
            .testTag("main_navigation_content"),
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
private fun FloatingNavigationDock(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .testTag("main_bottom_navigation"),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
            shadowElevation = 6.dp,
        ) {
            BettboxNavigationContent(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
            )
        }
    }
}

@Composable
private fun BettboxNavigationContent(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    val haptics = LocalNexaraHaptics.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab.entries.forEach { tab ->
            val label = stringResource(tab.titleRes)
            val selected = selectedTab == tab
            val weight by animateFloatAsState(
                targetValue = bottomNavigationTabWeight(selected),
                animationSpec = tween(durationMillis = 250),
                label = "bottom-navigation-weight-${tab.name}",
            )
            val containerColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f)
                },
                animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.Standard),
                label = "bottom-navigation-container-${tab.name}",
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.Standard),
                label = "bottom-navigation-content-${tab.name}",
            )
            Surface(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .selectable(
                        selected = selected,
                        onClick = {
                            if (selectedTab != tab) haptics.perform(NexaraHapticEvent.SELECTION)
                            onTabSelected(tab)
                        },
                        role = Role.Tab,
                    )
                    .semantics { contentDescription = label }
                    .testTag(navigationTabTag(tab)),
                shape = CircleShape,
                color = containerColor,
                contentColor = contentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    // 选中标签的文字随胶囊变宽一起展开，取消选中时先淡出再收起。
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) +
                            fadeIn(tween(NexaraMotion.DurationMedium - NexaraMotion.DurationFadeOut, delayMillis = NexaraMotion.DurationFadeOut)),
                        exit = shrinkHorizontally(tween(NexaraMotion.DurationShort, easing = NexaraMotion.EmphasizedAccelerate)) +
                            fadeOut(tween(NexaraMotion.DurationFadeOut)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun navigationTabTag(tab: AppTab): String =
    "main_navigation_tab_${tab.name.lowercase()}"

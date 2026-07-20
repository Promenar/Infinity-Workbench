package com.promenar.nexara.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.promenar.nexara.R
import kotlin.math.PI
import kotlin.math.sin

enum class AppTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    CHAT(R.string.nav_tab_chat, Icons.Rounded.ChatBubble),
    LIBRARY(R.string.nav_tab_library, Icons.Rounded.LocalLibrary),
    SETTINGS(R.string.nav_tab_settings, Icons.Rounded.Settings)
}

internal fun shouldUseNavigationRail(width: androidx.compose.ui.unit.Dp): Boolean = width >= 600.dp

internal fun constrainedBodyWidth(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    minOf(available, 960.dp)

internal fun fluidNavigationTargetIndex(tab: AppTab): Int =
    AppTab.entries.indexOf(tab).coerceAtLeast(0)

internal data class FluidNavigationScale(val x: Float, val y: Float)

internal fun fluidNavigationIndicatorScale(progress: Float): FluidNavigationScale {
    val boundedProgress = progress.coerceIn(0f, 1f)
    val deformation = sin(PI.toFloat() * boundedProgress)
    return FluidNavigationScale(
        x = 1f + (0.16f * deformation),
        y = 1f - (0.10f * deformation),
    )
}

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
                    onNavigateToFolder = { folderId, _ ->
                        onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.ragFolder(folderId))
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
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 5.dp,
            shadowElevation = 4.dp,
        ) {
            FluidNavigationContent(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
            )
        }
    }
}

@Composable
private fun FluidNavigationContent(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    val targetIndex = fluidNavigationTargetIndex(selectedTab)
    val animatedIndex = animateFloatAsState(
        targetValue = targetIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "fluidNavigationPosition",
    )
    val deformationProgress = remember { Animatable(1f) }
    var hasRenderedSelection by remember { mutableStateOf(false) }

    LaunchedEffect(targetIndex) {
        if (hasRenderedSelection) {
            if (deformationProgress.value == 1f) {
                deformationProgress.snapTo(0f)
            }
            deformationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
            )
        } else {
            hasRenderedSelection = true
            deformationProgress.snapTo(1f)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
    ) {
        val slotWidth = maxWidth / AppTab.entries.size
        val indicatorWidth = (slotWidth + 20.dp).coerceAtMost(maxWidth)
        val density = LocalDensity.current
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val indicatorWidthPx = with(density) { indicatorWidth.toPx() }
        val maxOffsetPx = with(density) { (maxWidth - indicatorWidth).toPx() }
        val indicatorTopPx = with(density) { 8.dp.roundToPx() }
        val indicatorColor = lerp(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.primary,
            0.22f,
        )
        val transformOrigin = when (targetIndex) {
            0 -> TransformOrigin(0f, 0.5f)
            AppTab.entries.lastIndex -> TransformOrigin(1f, 0.5f)
            else -> TransformOrigin.Center
        }

        Box(
            modifier = Modifier
                .offset {
                    val rawOffset = slotWidthPx * animatedIndex.value +
                        (slotWidthPx - indicatorWidthPx) / 2f
                    IntOffset(
                        x = rawOffset.coerceIn(0f, maxOffsetPx).toInt(),
                        y = indicatorTopPx,
                    )
                }
                .width(indicatorWidth)
                .height(48.dp)
                .zIndex(1f)
                .graphicsLayer {
                    val indicatorScale = fluidNavigationIndicatorScale(deformationProgress.value)
                    scaleX = indicatorScale.x
                    scaleY = indicatorScale.y
                    this.transformOrigin = transformOrigin
                }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(indicatorColor)
                .clearAndSetSemantics {
                    semanticsTestTag = "main_navigation_selected_indicator"
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = selectedTab.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(selectedTab.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            AppTab.entries.forEach { tab ->
                val label = stringResource(tab.titleRes)
                val selected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .minimumInteractiveComponentSize()
                        .selectable(
                            selected = selected,
                            onClick = { onTabSelected(tab) },
                            role = Role.Tab,
                        )
                        .semantics { contentDescription = label }
                        .testTag(navigationTabTag(tab)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!selected) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun navigationTabTag(tab: AppTab): String =
    "main_navigation_tab_${tab.name.lowercase()}"

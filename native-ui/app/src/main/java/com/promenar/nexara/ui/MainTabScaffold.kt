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
import com.promenar.nexara.ui.theme.onSelectionIndicator
import com.promenar.nexara.ui.theme.selectionIndicator
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
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * 主标签页的状态：当前标签、宽屏设置右列的层级与内屏首页会话列。由导航图持有，窗口变宽时导航图据此把整页设置子页、整页会话收回右列。
 */
@Stable
class MainTabsState(
    selectedTab: AppTab,
    settingsStack: List<String>,
    settingsLastRoot: String,
    hubStack: List<String> = emptyList(),
    hubExpanded: Boolean = false,
) {
    var selectedTab by mutableStateOf(selectedTab)

    /** 宽屏设置右列的页面层级（具体路由，首项为根）；单列时为空。 */
    var settingsStack by mutableStateOf(settingsStack)

    /** 右列上次显示的根页面：重新分两列时停在这里。 */
    var settingsLastRoot by mutableStateOf(settingsLastRoot)

    /** 内屏首页会话列的页面层级（具体路由，首项为会话）；没有会话或不分三列时为空。 */
    var hubStack by mutableStateOf(hubStack)
        private set

    /** 首页会话列是否展开：导航栏推出屏幕、列表收成图标条，会话占满其余宽度。 */
    var hubExpanded by mutableStateOf(hubExpanded)
        private set

    /** 展开时图标条是否展开成窄列表。 */
    var hubDrawerOpen by mutableStateOf(false)

    /** 会话列当前的会话。 */
    val hubSessionId: String? get() = com.promenar.nexara.navigation.hubPaneSessionId(hubStack.firstOrNull())

    /** 切换页签：展开状态只属于对话页，切到其它页签先还原三列。 */
    fun selectTab(tab: AppTab) {
        if (tab != AppTab.CHAT) collapseHub()
        selectedTab = tab
    }

    /** 会话顶栏的展开按钮：三列与展开互换；会话列没有会话时保持三列。 */
    fun toggleHubExpanded() {
        hubDrawerOpen = false
        hubExpanded = !hubExpanded && hubSessionId != null
    }

    fun collapseHub() {
        hubExpanded = false
        hubDrawerOpen = false
    }

    /** 系统返回：先收起窄列表，再还原三列；都不需要时返回 false，交给首页原有的返回处理。 */
    fun handleHubBack(): Boolean = when {
        hubDrawerOpen -> {
            hubDrawerOpen = false
            true
        }
        hubExpanded -> {
            hubExpanded = false
            true
        }
        else -> false
    }

    /** 会话列层级变化（占位页不计入）：没有会话时不保留展开。 */
    fun updateHubStack(routes: List<String>) {
        hubStack = routes.filterNot { it == com.promenar.nexara.navigation.HUB_PANE_EMPTY }
        if (hubSessionId == null) collapseHub()
    }

    /** 窗口变宽：整页会话收进会话列，以三列显示。 */
    fun restoreHubStack(routes: List<String>) {
        hubStack = routes
        collapseHub()
    }

    /** 窗口变窄：交出会话列层级改为整页，展开状态随之结束。 */
    fun takeHubStack(): List<String> {
        val pages = hubStack
        hubStack = emptyList()
        collapseHub()
        return pages
    }

    companion object {
        val Saver: Saver<MainTabsState, Any> = listSaver(
            save = {
                listOf(it.selectedTab.name, it.settingsLastRoot, ArrayList(it.settingsStack), ArrayList(it.hubStack), it.hubExpanded)
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                MainTabsState(
                    selectedTab = AppTab.valueOf(it[0] as String),
                    settingsStack = it[2] as List<String>,
                    settingsLastRoot = it[1] as String,
                    hubStack = it[3] as List<String>,
                    hubExpanded = it[4] as Boolean,
                )
            },
        )
    }
}

@Composable
fun rememberMainTabsState(): MainTabsState = rememberSaveable(saver = MainTabsState.Saver) {
    MainTabsState(AppTab.CHAT, emptyList(), com.promenar.nexara.navigation.NavDestinations.THEME_CONFIG)
}

@Composable
fun MainTabScaffold(
    onNavigateToSecondary: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit,
    onNavigateToChat: (String) -> Unit,
    state: MainTabsState = rememberMainTabsState(),
    /** 设置子页的注册；给出且窗口足够宽时设置页分两列。 */
    settingsPaneGraph: (androidx.navigation.NavGraphBuilder.(com.promenar.nexara.navigation.SettingsNavigator) -> Unit)? = null,
    /** 首页会话列的页面注册；给出且窗口足够宽（折叠屏内屏）时对话页分三列。 */
    hubPaneGraph: (androidx.navigation.NavGraphBuilder.(com.promenar.nexara.navigation.HubPaneNavigator) -> Unit)? = null,
) {
    val selectedTab = state.selectedTab
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val settingsTwoPane = settingsPaneGraph != null &&
        com.promenar.nexara.ui.settings.settingsTwoPaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    // 窗口由宽变窄：右列层级改为主导航上的整页，返回仍逐层回到设置目录；单列显示目录时清空右列层级。
    // 开合屏会重建 Activity，上一刻是否双列须跨重建保存，否则重建后看不出是从双列变来的。
    var wasTwoPane by rememberSaveable { mutableStateOf(settingsTwoPane) }
    LaunchedEffect(settingsTwoPane, selectedTab) {
        if (!settingsTwoPane && selectedTab == AppTab.SETTINGS && state.settingsStack.isNotEmpty()) {
            val pages = state.settingsStack
            state.settingsStack = emptyList()
            if (wasTwoPane) pages.forEach(onNavigateToSecondary)
        }
        wasTwoPane = settingsTwoPane
    }

    val hubThreePane = hubPaneGraph != null &&
        com.promenar.nexara.ui.hub.hubThreePaneAvailable(configuration.screenWidthDp, configuration.screenHeightDp)
    // 窗口由宽变窄（合上折叠屏）：会话列的会话改为主导航上的整页；展开后的整页在窗口变宽时由导航图收回会话列。
    var wasHubThreePane by rememberSaveable { mutableStateOf(hubThreePane) }
    LaunchedEffect(hubThreePane) {
        if (!hubThreePane && wasHubThreePane && selectedTab == AppTab.CHAT && state.hubStack.isNotEmpty()) {
            state.takeHubStack().forEach(onNavigateToSecondary)
        }
        wasHubThreePane = hubThreePane
    }
    val hubThreePaneShown = hubThreePane && selectedTab == AppTab.CHAT
    // 展开进度：0 为三列，1 为展开。导航栏与首页各列都只按它平移、淡入淡出。
    val hubExpansion = remember { androidx.compose.animation.core.Animatable(if (state.hubExpanded) 1f else 0f) }
    LaunchedEffect(state.hubExpanded, hubThreePaneShown) {
        val target = if (state.hubExpanded && hubThreePaneShown) 1f else 0f
        if (hubThreePaneShown) {
            hubExpansion.animateTo(target, tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate))
        } else {
            hubExpansion.snapTo(target)
        }
    }
    val railWidth = com.promenar.nexara.ui.hub.HubNavigationRailWidth + WindowInsets.safeDrawing
        .only(WindowInsetsSides.Start)
        .asPaddingValues()
        .calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    val hubHost = if (hubThreePane && hubPaneGraph != null) {
        remember(state, hubExpansion, railWidth, hubPaneGraph) {
            com.promenar.nexara.ui.hub.HubThreePaneHost(state, hubExpansion, railWidth, hubPaneGraph)
        }
    } else null
    val density = androidx.compose.ui.platform.LocalDensity.current
    val railShift: (() -> Float)? = if (hubThreePaneShown) {
        val shiftPx = with(density) {
            com.promenar.nexara.ui.hub.hubPaneGeometry(railWidth, railWidth, expanded = false).shift.toPx()
        }
        ({ -shiftPx * hubExpansion.value })
    } else null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        AdaptiveNavigationSurface(
            expanded = shouldUseNavigationRail(maxWidth),
            selectedTab = selectedTab,
            onTabSelected = state::selectTab,
            railShift = railShift,
        ) {
            AnimatedContent(
                targetState = selectedTab,
                // 同级标签之间先淡出再淡入，避免两页内容叠在一起。
                transitionSpec = { NexaraMotion.fadeThrough() },
                label = "main-tab-content",
            ) { tab -> when (tab) {
                AppTab.CHAT -> com.promenar.nexara.ui.hub.AgentHubScreen(
                    onNavigateToChat = onNavigateToChat,
                    onNavigateToAgentEdit = onNavigateToAgentEdit,
                    onOpenLinuxEnvironment = { onNavigateToSecondary(com.promenar.nexara.navigation.NavDestinations.LINUX_ENVIRONMENT) },
                    threePane = hubHost,
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
                AppTab.SETTINGS -> if (settingsTwoPane) {
                    com.promenar.nexara.ui.settings.SettingsTwoPane(
                        state = state,
                        onNavigateToSecondary = onNavigateToSecondary,
                        paneGraph = settingsPaneGraph,
                    )
                } else {
                    com.promenar.nexara.ui.hub.UserSettingsHomeScreen(onNavigateToSecondary = onNavigateToSecondary)
                }
            } }
        }
    }
}

/**
 * [railShift] 不为 null 时（内屏首页三列）内容铺满整个窗口，导航栏叠在内容左侧并按它给出的像素平移，
 * 由内容自己为导航栏留出位置；展开会话时导航栏随整排推出屏幕。
 */
@Composable
internal fun AdaptiveNavigationSurface(
    expanded: Boolean,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    railShift: (() -> Float)? = null,
    content: @Composable () -> Unit,
) {
    if (expanded) {
        val fullBleed = railShift != null
        androidx.compose.ui.layout.Layout(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            content = {
            NavigationRail(
                modifier = Modifier
                    .fillMaxHeight()
                    .graphicsLayer { translationX = railShift?.invoke() ?: 0f }
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
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSelectionIndicator,
                            selectedTextColor = MaterialTheme.colorScheme.onSelectionIndicator,
                            indicatorColor = MaterialTheme.colorScheme.selectionIndicator(MaterialTheme.colorScheme.surface),
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .semantics { role = Role.Tab }
                            .testTag(navigationTabTag(tab)),
                    )
                }
            }
            AdaptiveBody(capWidth = !fullBleed, content = content)
            },
        ) { measurables, constraints ->
            val rail = measurables[0].measure(constraints.copy(minWidth = 0))
            val bodyStart = if (fullBleed) 0 else rail.width
            val bodyWidth = (constraints.maxWidth - bodyStart).coerceAtLeast(0)
            val body = measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed(bodyWidth, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                body.place(bodyStart, 0)
                rail.place(0, 0, zIndex = 1f)
            }
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
    capWidth: Boolean = true,
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
                .then(if (capWidth) Modifier.widthIn(max = 960.dp) else Modifier)
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
                    MaterialTheme.colorScheme.selectionIndicator()
                } else {
                    MaterialTheme.colorScheme.selectionIndicator().copy(alpha = 0f)
                },
                animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.Standard),
                label = "bottom-navigation-container-${tab.name}",
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onSelectionIndicator
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

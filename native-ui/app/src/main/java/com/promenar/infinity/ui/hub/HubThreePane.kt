package com.promenar.infinity.ui.hub

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.rememberNavController
import com.promenar.infinity.R
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.navigation.HUB_PANE_EMPTY
import com.promenar.infinity.navigation.HubPaneNavigator
import com.promenar.infinity.navigation.InfinityPageNavHost
import com.promenar.infinity.navigation.SettingsPaneEntriesSaver
import com.promenar.infinity.navigation.SettingsPaneEntry
import com.promenar.infinity.navigation.composablePage
import com.promenar.infinity.navigation.concreteRoute
import com.promenar.infinity.navigation.isPaneRootEntry
import com.promenar.infinity.navigation.trackSettingsPaneStack
import com.promenar.infinity.ui.MainTabsState
import com.promenar.infinity.ui.chat.LocalChatNavigationIcon
import com.promenar.infinity.ui.common.AgentAvatar
import com.promenar.infinity.ui.common.agentAvatarColor
import com.promenar.infinity.ui.common.agentIconVector
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.flow.first
import com.promenar.infinity.ui.theme.selectionIndicator

/** 三列时会话助手列表的宽度。 */
internal val HubListPaneWidth = 296.dp

/** 展开后列表收成的图标条宽度。 */
internal val HubStripWidth = 72.dp

/** 图标条展开成的窄列表宽度。 */
internal val HubDrawerWidth = 256.dp

/** 导航栏（NavigationRail）本身的宽度，不含左侧安全区。 */
internal val HubNavigationRailWidth = 80.dp

private const val HubThreePaneMinBodyWidthDp = 700
private const val NavigationRailWidthDp = 80
private const val HubThreePaneMinHeightDp = 480

/** 窗口去掉导航栏后至少 700dp（折叠屏内屏约 814dp、平板）时，对话页分三列；更窄时仍是列表与整页会话。 */
internal fun hubThreePaneAvailable(widthDp: Int, heightDp: Int): Boolean =
    widthDp - NavigationRailWidthDp >= HubThreePaneMinBodyWidthDp && heightDp >= HubThreePaneMinHeightDp

/**
 * 三列（导航栏｜列表｜会话）与展开（图标条｜会话）两态的各列位置，均从窗口左缘量起。
 * 展开时整排左移 [shift]：导航栏推出屏幕，列表移出后由图标条接替，会话左缘停在图标条右侧。
 */
internal data class HubPaneGeometry(
    val listStart: Dp,
    /** 三列时会话列的左缘。 */
    val threePaneDetailStart: Dp,
    /** 会话列按目标形态布局的左缘与宽度：动画全程不变，只做平移。 */
    val detailStart: Dp,
    val detailWidth: Dp,
    val shift: Dp,
) {
    /** 展开进度 [progress]（0 为三列，1 为展开）时会话列相对布局位置的平移。 */
    fun detailTranslation(progress: Float): Dp = threePaneDetailStart - shift * progress - detailStart
}

internal fun hubPaneGeometry(windowWidth: Dp, railWidth: Dp, expanded: Boolean): HubPaneGeometry {
    val threePaneDetailStart = railWidth + HubListPaneWidth
    val detailStart = if (expanded) HubStripWidth else threePaneDetailStart
    return HubPaneGeometry(
        listStart = railWidth,
        threePaneDetailStart = threePaneDetailStart,
        detailStart = detailStart,
        detailWidth = (windowWidth - detailStart).coerceAtLeast(0.dp),
        shift = threePaneDetailStart - HubStripWidth,
    )
}

/** 首页三列的宿主参数：主标签状态、展开进度（导航栏与首页共用）与会话列的页面注册。 */
@Stable
class HubThreePaneHost internal constructor(
    val state: MainTabsState,
    val expansion: Animatable<Float, AnimationVector1D>,
    val railWidth: Dp,
    val paneGraph: NavGraphBuilder.(HubPaneNavigator) -> Unit,
)

/** 图标条上的一个会话。 */
internal data class HubStripEntry(val session: Session, val agent: AgentDisplayItem)

/** 图标条列出与首页“置顶”“最近”相同的会话；当前会话不在其中时排在最前。 */
internal fun hubStripEntries(
    agents: List<AgentDisplayItem>,
    sessionsByAgent: Map<String, List<Session>>,
    selectedSessionId: String?,
): List<HubStripEntry> {
    val byId = agents.associateBy { it.agent.id }
    val sections = buildHubSections(agents, sessionsByAgent, "")
    val listed = (sections.pinnedSessions + sections.sessions).map { it.session }
    val selected = selectedSessionId?.takeIf { id -> listed.none { it.id == id } }
        ?.let { id -> sessionsByAgent.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } } }
    return (listOfNotNull(selected) + listed).mapNotNull { session ->
        byId[session.agentId]?.let { HubStripEntry(session, it) }
    }
}

/**
 * 内屏首页三列：功能导航（由主标签页绘制）｜会话助手列表｜会话列。
 * 会话列有自己的导航与返回栈，每个会话一个条目，ViewModel 随条目隔离；层级记在 [HubThreePaneHost.state]，开合屏时据此迁移。
 * 展开时会话列从一开始就按最终宽度布局，左侧各列与会话列只做平移和淡入淡出，不逐帧改宽度。
 */
@Composable
internal fun HubThreePane(
    host: HubThreePaneHost,
    strip: List<HubStripEntry>,
    activity: Map<String, HubSessionActivity>,
    empty: @Composable (openSession: (String) -> Unit) -> Unit,
    list: @Composable (openSession: (String) -> Unit, closeSessions: (Set<String>) -> Unit) -> Unit,
) {
    val state = host.state
    val paneNav = rememberNavController()
    var paneEntries by rememberSaveable(stateSaver = SettingsPaneEntriesSaver) { mutableStateOf(emptyList<SettingsPaneEntry>()) }
    val navigator = remember(paneNav) { HubPaneNavigator(paneNav, paneRoot = { paneEntries.firstOrNull()?.route }) }
    val openSession: (String) -> Unit = remember(navigator) {
        { sessionId ->
            state.hubDrawerOpen = false
            navigator.openSession(sessionId)
        }
    }
    // 删除了会话列正在显示的会话（或其助手）：会话列回到新建入口。
    val closeSessions: (Set<String>) -> Unit = remember(navigator) {
        { sessionIds -> if (state.hubSessionId in sessionIds) navigator.clear() }
    }
    // 首次组合时的层级（开合屏迁移）：会话列的图在三列布局的子组合里才建立，等图建立后再压入，这一次不做切换动画；
    // 压入完成前的目的地变化（起始占位页）不回写主标签状态。
    val initialStack = remember { state.hubStack }
    val initialEntryIds = remember { mutableSetOf<String>() }
    var initializing by remember { mutableStateOf(initialStack.isNotEmpty()) }
    DisposableEffect(paneNav) {
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            val entry = controller.currentBackStackEntry ?: return@OnDestinationChangedListener
            val route = entry.concreteRoute() ?: return@OnDestinationChangedListener
            if (initializing) initialEntryIds += entry.id
            paneEntries = trackSettingsPaneStack(paneEntries, SettingsPaneEntry(entry.id, route), controller.previousBackStackEntry?.id)
            if (!initializing) state.updateHubStack(paneEntries.map { it.route })
        }
        paneNav.addOnDestinationChangedListener(listener)
        onDispose { paneNav.removeOnDestinationChangedListener(listener) }
    }
    LaunchedEffect(paneNav) {
        if (!initializing) return@LaunchedEffect
        val first = paneNav.currentBackStackEntryFlow.first()
        // 重建后会话列导航已恢复原层级时不再重复压入。
        if (paneNav.previousBackStackEntry == null && first.destination.route == HUB_PANE_EMPTY) {
            initialStack.forEachIndexed { index, route ->
                paneNav.navigate(route) { if (index == 0) popUpTo(paneNav.graph.id) { inclusive = true } }
            }
        }
        initializing = false
        state.updateHubStack(paneEntries.map { it.route })
    }
    val isPaneRoot: (NavBackStackEntry) -> Boolean = { entry -> isPaneRootEntry(paneEntries, entry.id) }
    val currentEmpty by rememberUpdatedState(empty)
    val paneGraph = host.paneGraph
    val graph: NavGraphBuilder.() -> Unit = remember(navigator, paneGraph) {
        {
            composablePage(HUB_PANE_EMPTY) { currentEmpty(openSession) }
            paneGraph(navigator)
        }
    }

    val expansion = host.expansion
    val drawer = remember { Animatable(if (state.hubDrawerOpen) 1f else 0f) }
    val drawerOpen = state.hubDrawerOpen && state.hubExpanded
    LaunchedEffect(drawerOpen) {
        drawer.animateTo(if (drawerOpen) 1f else 0f, tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
    }
    // 只在窄列表出现与完全收起时重组，动画帧只走绘制阶段。
    val drawerShown by remember { derivedStateOf { drawer.value > 0f || drawer.targetValue > 0f } }
    // 收起动画全程仍按展开形态布局，会话列向右滑出屏幕边缘，到 0 才换回三列宽度；否则右侧会先露出空白。
    val expansionVisible by remember(expansion) { derivedStateOf { expansion.value > 0f } }
    val wideLayout = state.hubExpanded || expansionVisible

    // 展开状态的系统返回先还原三列；会话列内部（检视器、子代理页）的返回在其后注册，优先处理。
    BackHandler(enabled = state.hubExpanded) { state.handleHubBack() }

    HubThreePaneLayout(
        railWidth = host.railWidth,
        expanded = wideLayout,
        expansion = { expansion.value },
        stripShown = wideLayout,
        drawerShown = drawerShown,
        drawerProgress = { drawer.value },
        onDismissDrawer = { state.hubDrawerOpen = false },
        list = { list(openSession, closeSessions) },
        strip = {
            HubSessionStrip(
                entries = strip,
                activity = activity,
                selectedSessionId = state.hubSessionId,
                enabled = state.hubExpanded,
                onOpenList = { state.hubDrawerOpen = true },
                onSelect = openSession,
            )
        },
    ) {
        val expanded = state.hubExpanded
        val navigationIcon: @Composable () -> Unit = { HubExpandButton(expanded = expanded, onClick = state::toggleHubExpanded) }
        CompositionLocalProvider(LocalChatNavigationIcon provides navigationIcon) {
            // 会话之上的子代理页用视差推入（以会话列宽度为准并裁剪在列内）；换会话时新会话盖在旧会话上叠化；
            // 迁移进来的初始层级直接出现，开合屏不触发换页动画。
            InfinityPageNavHost(
                navController = paneNav,
                startDestination = HUB_PANE_EMPTY,
                modifier = Modifier.fillMaxSize(),
                isSameLevel = isPaneRoot,
                isInstant = { entry -> entry.id in initialEntryIds },
                builder = graph,
            )
        }
    }
    // 窄列表浮在最上层，返回先收起它。
    if (drawerOpen) BackHandler { state.handleHubBack() }
}

/**
 * 三列与展开两态的摆放：列表、图标条与会话列。会话列按目标形态的位置与宽度布局，
 * [expansion]（0 为三列，1 为展开）与 [drawerProgress] 只在绘制阶段读取，动画帧不重新测量。
 */
@Composable
internal fun HubThreePaneLayout(
    railWidth: Dp,
    expanded: Boolean,
    expansion: () -> Float,
    stripShown: Boolean,
    drawerShown: Boolean,
    drawerProgress: () -> Float,
    onDismissDrawer: () -> Unit,
    list: @Composable () -> Unit,
    strip: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag(HubThreePaneTags.ROOT)) {
        val geometry = hubPaneGeometry(maxWidth, railWidth, expanded = expanded)
        val density = LocalDensity.current
        val shiftPx = with(density) { geometry.shift.toPx() }
        val drawerWidthPx = with(density) { HubDrawerWidth.toPx() }
        val background = MaterialTheme.colorScheme.background
        val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

        // 列表：三列时在导航栏右侧；展开时随整排左移淡出；窄列表打开时同一份列表移到左缘浮在会话之上。
        Box(
            Modifier
                .offset(x = if (drawerShown) 0.dp else geometry.listStart)
                .width(if (drawerShown) HubDrawerWidth else HubListPaneWidth)
                .fillMaxHeight()
                .zIndex(if (drawerShown) 4f else 0f)
                .graphicsLayer {
                    if (drawerShown) {
                        val progress = drawerProgress()
                        translationX = -(1f - progress) * drawerWidthPx
                        alpha = 1f
                        shadowElevation = 8.dp.toPx() * progress
                    } else {
                        val progress = expansion()
                        translationX = -shiftPx * progress
                        alpha = 1f - progress
                        shadowElevation = 0f
                    }
                }
                .background(background)
                .testTag(HubThreePaneTags.LIST),
        ) { list() }

        if (stripShown) {
            Box(
                Modifier
                    .width(HubStripWidth)
                    .fillMaxHeight()
                    .zIndex(1f)
                    .graphicsLayer { alpha = expansion() }
                    .testTag(HubThreePaneTags.STRIP),
            ) { strip() }
        }

        Box(
            Modifier
                .offset(x = geometry.detailStart)
                .width(geometry.detailWidth)
                .fillMaxHeight()
                .zIndex(2f)
                .graphicsLayer { translationX = geometry.detailTranslation(expansion()).toPx() }
                .background(background)
                .drawWithContent {
                    drawContent()
                    drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                }
                .testTag(HubThreePaneTags.DETAIL),
        ) { detail() }

        if (drawerShown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .graphicsLayer { alpha = drawerProgress() }
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissDrawer)
                    .testTag(HubThreePaneTags.SCRIM),
            )
        }
    }
}

/** 会话顶栏左键：三列时“←|”展开会话，展开后“|→”还原三列。 */
@Composable
internal fun HubExpandButton(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(HubThreePaneTags.EXPAND)) {
        Icon(
            imageVector = Icons.Rounded.Start,
            contentDescription = stringResource(if (expanded) R.string.hub_pane_restore else R.string.hub_pane_expand),
            tint = MaterialTheme.colorScheme.onSurface,
            // “|→”镜像即“←|”。
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = if (expanded) 1f else -1f },
        )
    }
}

/** 展开后的图标条：顶部按钮展开成窄列表，其下每个会话一个助手头像，角标标出生成中、待批准、待回答与排队中。 */
@Composable
internal fun HubSessionStrip(
    entries: List<HubStripEntry>,
    activity: Map<String, HubSessionActivity>,
    selectedSessionId: String?,
    enabled: Boolean,
    onOpenList: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val container = MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxSize()
            .background(container)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        IconButton(
            onClick = onOpenList,
            enabled = enabled,
            modifier = Modifier.size(48.dp).testTag(HubThreePaneTags.STRIP_OPEN_LIST),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ViewSidebar,
                contentDescription = stringResource(R.string.hub_strip_open_list),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // 侧栏图标的窄栏画在左侧，与图标条所在一致。
                modifier = Modifier.graphicsLayer { scaleX = -1f },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(entries, key = { it.session.id }) { entry ->
                HubStripItem(
                    entry = entry,
                    activity = activity[entry.session.id],
                    selected = entry.session.id == selectedSessionId,
                    enabled = enabled,
                    badgeBorder = container,
                    onClick = { onSelect(entry.session.id) },
                )
            }
        }
    }
}

@Composable
private fun HubStripItem(
    entry: HubStripEntry,
    activity: HubSessionActivity?,
    selected: Boolean,
    enabled: Boolean,
    badgeBorder: Color,
    onClick: () -> Unit,
) {
    val title = entry.session.title.ifBlank { stringResource(R.string.hub_session_untitled) }
    val activityLabel = activity?.let { stringResource(hubSessionActivityLabel(it)) }
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.selectionIndicator() else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "$title，${entry.agent.title}"
                this.selected = selected
                activityLabel?.let { stateDescription = it }
            }
            .testTag(HubThreePaneTags.stripSession(entry.session.id)),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            AgentAvatar(
                icon = agentIconVector(entry.agent.agent.icon),
                customImageUri = entry.agent.agent.avatarPath,
                backgroundColor = agentAvatarColor(entry.agent.agent.color),
                size = 36.dp,
            )
            if (activity != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(14.dp)
                        .border(2.dp, badgeBorder, CircleShape)
                        .padding(2.dp)
                        .background(hubSessionActivityColor(activity), CircleShape)
                        .testTag(HubThreePaneTags.stripBadge(entry.session.id, activity)),
                )
            }
        }
    }
}

/** 会话状态的文字：与列表行右侧状态一致。 */
internal fun hubSessionActivityLabel(activity: HubSessionActivity): Int = when (activity) {
    HubSessionActivity.GENERATING -> R.string.chat_cd_generating_response
    HubSessionActivity.AWAITING_APPROVAL -> R.string.hub_session_awaiting_approval
    HubSessionActivity.AWAITING_ANSWER -> R.string.ask_card_status_waiting
    HubSessionActivity.QUEUED -> R.string.ask_card_status_queued
}

/** 角标颜色：与列表行状态文字同色，生成中用主色。 */
@Composable
private fun hubSessionActivityColor(activity: HubSessionActivity): Color = when (activity) {
    HubSessionActivity.GENERATING -> MaterialTheme.colorScheme.primary
    HubSessionActivity.AWAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
    HubSessionActivity.AWAITING_ANSWER -> MaterialTheme.infinityDomainColors.warning
    HubSessionActivity.QUEUED -> MaterialTheme.colorScheme.outline
}

/** 会话列没有会话时：提示从左侧选择，或直接新建对话。 */
@Composable
internal fun HubPaneEmpty(
    agents: List<AgentDisplayItem>,
    onCreateSession: (String) -> Unit,
    onRequestAdd: () -> Unit,
) {
    Box(Modifier.fillMaxSize().testTag(HubThreePaneTags.EMPTY), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.hub_pane_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                FilledTonalButton(
                    onClick = { if (agents.isEmpty()) onRequestAdd() else menuOpen = true },
                    modifier = Modifier.testTag(HubThreePaneTags.EMPTY_NEW_CHAT),
                ) { Text(stringResource(R.string.hub_new_chat)) }
                HubNewChatMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    agents = agents,
                    onCreateSession = onCreateSession,
                    onRequestAdd = onRequestAdd,
                )
            }
        }
    }
}

object HubThreePaneTags {
    const val ROOT = "hub_three_pane"
    const val LIST = "hub_three_pane_list"
    const val STRIP = "hub_three_pane_strip"
    const val DETAIL = "hub_three_pane_detail"
    const val SCRIM = "hub_three_pane_scrim"
    const val EXPAND = "hub_three_pane_expand"
    const val EMPTY = "hub_three_pane_empty"
    const val EMPTY_NEW_CHAT = "hub_three_pane_empty_new_chat"
    const val STRIP_OPEN_LIST = "hub_three_pane_strip_open_list"
    fun stripSession(sessionId: String) = "hub_strip_session:$sessionId"
    internal fun stripBadge(sessionId: String, activity: HubSessionActivity) = "hub_strip_badge:$sessionId:${activity.name.lowercase()}"
}

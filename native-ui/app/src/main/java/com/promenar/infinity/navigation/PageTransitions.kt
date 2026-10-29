package com.promenar.infinity.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.promenar.infinity.ui.theme.InfinityMotion

/** 一次换页的种类：迁移初始层级时直接出现；栏内换根页面为同级叠化；其余为视差推入的前进与返回。 */
internal enum class PageChange { INSTANT, SAME_LEVEL, PUSH, POP }

/** 换页中页面所处的层：上层带左缘阴影，下层随进度压暗；叠化与无动画时都不画。 */
internal enum class PageLayer { NONE, TOP, UNDER }

/** 返回一律是视差返回；前进时 [instant] 优先于 [sameLevel]。 */
internal fun pageChange(pop: Boolean, instant: Boolean, sameLevel: Boolean): PageChange = when {
    pop -> PageChange.POP
    instant -> PageChange.INSTANT
    sameLevel -> PageChange.SAME_LEVEL
    else -> PageChange.PUSH
}

/** 换页双方的层（目标页在前、离开页在后）：前进时新页在上；返回时被弹出的页在上，下层页从压暗中恢复。 */
internal fun PageChange.layers(): Pair<PageLayer, PageLayer> = when (this) {
    PageChange.PUSH -> PageLayer.TOP to PageLayer.UNDER
    PageChange.POP -> PageLayer.UNDER to PageLayer.TOP
    PageChange.INSTANT, PageChange.SAME_LEVEL -> PageLayer.NONE to PageLayer.NONE
}

/**
 * 压暗进度的曲线跟哪个方向的位移：只有下层页需要压暗，离开时是被推入盖住（前进），进入时是从下层回来（返回）；
 * 其余页面不压暗，返回 null（立即到位，不拉长整个换页）。
 */
internal fun pageDimFollowsPop(layer: PageLayer, entering: Boolean): Boolean? =
    if (layer == PageLayer.UNDER) entering else null

/**
 * 一个 NavHost 内各条目当前所处的层：转场回调决定动效时登记，目的地外框在组合与绘制时读取。
 * 同一条目先后作为上层和下层时以最近一次换页为准；条目离开组合时清除。
 */
@Stable
internal class PageLayers {
    private val layers = mutableStateMapOf<String, PageLayer>()

    fun layerOf(entryId: String): PageLayer = layers[entryId] ?: PageLayer.NONE

    fun record(targetId: String, initialId: String, change: PageChange) {
        val (target, initial) = change.layers()
        set(targetId, target)
        set(initialId, initial)
    }

    fun clear(entryId: String) {
        layers.remove(entryId)
    }

    // 转场回调在组合期间执行，同值不重复写入，避免无谓的重组。
    private fun set(entryId: String, layer: PageLayer) {
        if (layers[entryId] != layer) layers[entryId] = layer
    }
}

private val LocalPageLayers = staticCompositionLocalOf<PageLayers?> { null }

/**
 * 页面级 NavHost：层级换页用视差推入（见 [InfinityMotion.pageEnter]），并裁剪到自身边界，
 * 栏内的位移、阴影与压暗不会盖到相邻列表栏或分隔线上。
 * [isSameLevel] 判定前进的目标页是否与当前页同级（栏内换根页面），是则改为叠化；
 * [isInstant] 判定的目标页（开合屏迁移的初始层级）直接出现。
 * 目的地须用 [composablePage] 注册，才有不透明底色、左缘阴影与压暗。
 */
@Composable
internal fun InfinityPageNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    isSameLevel: (NavBackStackEntry) -> Boolean = { false },
    isInstant: (NavBackStackEntry) -> Boolean = { false },
    builder: NavGraphBuilder.() -> Unit,
) {
    val layers = remember(navController) { PageLayers() }
    val forward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> PageChange = {
        pageChange(pop = false, instant = isInstant(targetState), sameLevel = isSameLevel(targetState))
            .also { layers.record(targetState.id, initialState.id, it) }
    }
    val backward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> PageChange = {
        PageChange.POP.also { layers.record(targetState.id, initialState.id, it) }
    }
    CompositionLocalProvider(LocalPageLayers provides layers) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier.clipToBounds(),
            enterTransition = { pageEnterFor(forward()) },
            exitTransition = { pageExitFor(forward()) },
            popEnterTransition = { pageEnterFor(backward()) },
            popExitTransition = { pageExitFor(backward()) },
            builder = builder,
        )
    }
}

private fun pageEnterFor(change: PageChange): EnterTransition = when (change) {
    PageChange.INSTANT -> EnterTransition.None
    PageChange.SAME_LEVEL -> InfinityMotion.paneRootEnter
    PageChange.PUSH -> InfinityMotion.pageEnter(pop = false)
    PageChange.POP -> InfinityMotion.pageEnter(pop = true)
}

private fun pageExitFor(change: PageChange): ExitTransition = when (change) {
    PageChange.INSTANT -> ExitTransition.None
    PageChange.SAME_LEVEL -> InfinityMotion.paneRootExit
    PageChange.PUSH -> InfinityMotion.pageExit(pop = false)
    PageChange.POP -> InfinityMotion.pageExit(pop = true)
}

/** 注册页面级目的地：与 [composable] 相同，另包一层 [PageFrame]。 */
internal fun NavGraphBuilder.composablePage(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments, deepLinks) { entry ->
        val scope = this
        PageFrame(scope, entry.id) { scope.content(entry) }
    }
}

/**
 * 目的地外框：铺一层不透明背景，换页时两页叠在一起也不会透出下层；
 * 作为上层时在左缘外侧画一道阴影，作为下层时按位移进度叠一层黑色压暗。
 * 阴影只在换页进行中绘制，压暗在静止时为 0；系统移除动画时换页立即结束，两者都不残留。
 */
@Composable
private fun PageFrame(scope: AnimatedContentScope, entryId: String, content: @Composable () -> Unit) {
    val layers = LocalPageLayers.current
    if (layers != null) {
        DisposableEffect(layers, entryId) { onDispose { layers.clear(entryId) } }
    }
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val maxDim = if (dark) InfinityMotion.PageUnderDimDark else InfinityMotion.PageUnderDimLight
    val shadowAlpha = if (dark) InfinityMotion.PageEdgeShadowAlphaDark else InfinityMotion.PageEdgeShadowAlphaLight
    val transition = scope.transition
    val dim = transition.animateFloat(
        transitionSpec = {
            val follows = layers?.let { pageDimFollowsPop(it.layerOf(entryId), entering = targetState == EnterExitState.Visible) }
            if (follows == null) snap() else InfinityMotion.pageSpec(follows)
        },
        label = "page-under-dim",
    ) { state ->
        if (state == EnterExitState.Visible || layers?.layerOf(entryId) != PageLayer.UNDER) 0f else 1f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val layer = layers?.layerOf(entryId) ?: PageLayer.NONE
                if (layer == PageLayer.TOP && transition.currentState != transition.targetState) {
                    val width = InfinityMotion.PageEdgeShadowWidthDp.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = shadowAlpha)),
                            startX = -width,
                            endX = 0f,
                        ),
                        topLeft = Offset(-width, 0f),
                        size = Size(width, size.height),
                    )
                }
                drawContent()
                val amount = if (layer == PageLayer.UNDER) dim.value else 0f
                if (amount > 0f) drawRect(Color.Black.copy(alpha = maxDim * amount))
            }
            .background(background),
        contentAlignment = Alignment.TopStart,
        propagateMinConstraints = true,
    ) { content() }
}

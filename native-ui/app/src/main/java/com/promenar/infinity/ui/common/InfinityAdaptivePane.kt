package com.promenar.infinity.ui.common

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.ui.theme.InfinityMotion

/** 二级内容的承载方式：窄窗口为整页覆盖主内容的二级页面，宽窗口为主内容自己的浮动检视器。 */
enum class InfinityPanePresentation { Page, Inspector }

/** 二级内容据此决定根层是否需要自己的顶栏：检视器的页签已写明所在页，根层不再重复标题与返回。 */
val LocalInfinityPanePresentation = staticCompositionLocalOf { InfinityPanePresentation.Page }

/** 宿主判定的窗口形态：为 true 时宿主不再整页覆盖，二级内容交给主内容的浮动检视器承载。 */
val LocalInfinityInspectorLayout = staticCompositionLocalOf { false }

/** 折叠屏内屏（约 690–840dp）与平板都在此之上；横屏手机高度不足，仍按窄窗口处理。 */
internal val InfinityInspectorMinWindowWidth = 600.dp
internal val InfinityInspectorMinWindowHeight = 480.dp
internal val InfinityInspectorWidth = 360.dp
internal val InfinityInspectorWideWidth = 560.dp

/** 检视器距窗口右缘与距让位后主内容的间距之和。 */
internal val InfinityInspectorGutter = 24.dp

/** 让位后主内容至少保留的宽度；达不到时检视器浮在主内容之上，主内容不动。 */
internal val InfinityInspectorMinMainWidth = 480.dp

enum class InfinityInspectorPlacement { Shift, Float }

fun infinityInspectorAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth >= InfinityInspectorMinWindowWidth && windowHeight >= InfinityInspectorMinWindowHeight

/** 加宽时最多 560dp，并给左侧留出至少 48dp 的会话可见边缘。 */
fun infinityInspectorWidth(windowWidth: Dp, widened: Boolean): Dp =
    if (!widened) InfinityInspectorWidth else minOf(InfinityInspectorWideWidth, windowWidth - 48.dp).coerceAtLeast(InfinityInspectorWidth)

fun infinityInspectorPlacement(windowWidth: Dp, inspectorWidth: Dp): InfinityInspectorPlacement =
    if (windowWidth - inspectorWidth - InfinityInspectorGutter >= InfinityInspectorMinMainWidth) InfinityInspectorPlacement.Shift
    else InfinityInspectorPlacement.Float

object InfinityPaneTags {
    const val PAGE = "infinity_secondary_page"
    const val INSPECTOR = "infinity_inspector"
    const val INSPECTOR_STRIP = "infinity_inspector_strip"
    fun level(key: String) = "infinity_pane_level_$key"
}

/**
 * 主内容与二级内容的自适应宿主。
 * - 窄窗口：二级内容作为二级页面整页覆盖主内容；[coverSuspended] 为 true 时（如生成中需要用户处理的审批）暂时让出，层级保留；
 * - 宽窗口（[infinityInspectorAvailable]）：宿主不覆盖，经 [LocalInfinityInspectorLayout] 通知主内容，由主内容的浮动检视器承载同一份层级。
 *
 * 主内容始终在同一位置组合，折叠屏开合改变窗口宽度时不会重建主内容的状态。
 * 系统返回先交给最上层内容自身（详情页、子目录等），没有可后退的层级时调用 [onBack]。
 * 层级用 [InfinityPaneLevel] 叠放：下层保持组合、不接收返回，上层弹出后下层原样恢复。
 */
@Composable
fun InfinityAdaptivePaneHost(
    paneVisible: Boolean,
    onBack: () -> Unit,
    pane: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    coverSuspended: Boolean = false,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inspector = infinityInspectorAvailable(maxWidth, maxHeight)
        val shown = !inspector && paneVisible && !coverSuspended
        CompositionLocalProvider(LocalInfinityInspectorLayout provides inspector) {
            // 整页覆盖时主内容不接收返回，避免其中后来出现的返回处理抢在二级页面之前。
            InfinityBackScope(active = !shown) { content() }
        }
        AnimatedVisibility(
            visible = shown,
            enter = InfinityMotion.sharedAxisXEnter(forward = true),
            exit = InfinityMotion.sharedAxisXExit(forward = false),
            modifier = Modifier.fillMaxSize(),
        ) {
            BackHandler(onBack = onBack)
            CompositionLocalProvider(LocalInfinityPanePresentation provides InfinityPanePresentation.Page) {
                Box(Modifier.fillMaxSize().testTag(InfinityPaneTags.PAGE), content = pane)
            }
        }
    }
}

/**
 * 二级内容中的一层。层级按组合顺序叠放，后组合的在上；[top] 为 false 的层被上层遮住，不接收返回。
 * 默认推入时自右侧滑入，弹出时向右滑出；[enter]/[exit] 可按场景替换（如检视器换页签）。随宿主一起首次出现时不另做动画。
 */
@Composable
fun BoxScope.InfinityPaneLevel(
    key: String,
    visible: Boolean,
    top: Boolean,
    enter: EnterTransition = InfinityMotion.sharedAxisXEnter(forward = true),
    exit: ExitTransition = InfinityMotion.sharedAxisXExit(forward = false),
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = Modifier.matchParentSize(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().testTag(InfinityPaneTags.level(key)),
        ) {
            InfinityBackScope(active = top, content = content)
        }
    }
}

/**
 * [active] 为 false 时，内容中的 BackHandler 改挂到一个不接收系统返回的调度器上；
 * 重新激活时按当前时刻重新注册，排在已有的返回处理之前。切换只换提供的值，不改变组合结构，内容状态保留。
 */
@Composable
fun InfinityBackScope(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalOnBackPressedDispatcherOwner.current
    if (parent == null) {
        content()
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val inert = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides if (active) parent else inert, content = content)
}

package com.promenar.nexara.ui.common

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
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
import com.promenar.nexara.ui.theme.NexaraMotion

/** 二级内容的承载方式：窄窗口为整页覆盖主内容的二级页面，宽窗口为主内容自己的浮动检视器。 */
enum class NexaraPanePresentation { Page, Inspector }

/** 二级内容据此决定根层是否需要自己的顶栏：检视器的页签已写明所在页，根层不再重复标题与返回。 */
val LocalNexaraPanePresentation = staticCompositionLocalOf { NexaraPanePresentation.Page }

/** 宿主判定的窗口形态：为 true 时宿主不再整页覆盖，二级内容交给主内容的浮动检视器承载。 */
val LocalNexaraInspectorLayout = staticCompositionLocalOf { false }

/** 折叠屏内屏（约 690–840dp）与平板都在此之上；横屏手机高度不足，仍按窄窗口处理。 */
internal val NexaraInspectorMinWindowWidth = 600.dp
internal val NexaraInspectorMinWindowHeight = 480.dp
internal val NexaraInspectorWidth = 360.dp
internal val NexaraInspectorWideWidth = 560.dp

/** 检视器距窗口右缘与距让位后主内容的间距之和。 */
internal val NexaraInspectorGutter = 24.dp

/** 让位后主内容至少保留的宽度；达不到时检视器浮在主内容之上，主内容不动。 */
internal val NexaraInspectorMinMainWidth = 480.dp

enum class NexaraInspectorPlacement { Shift, Float }

fun nexaraInspectorAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth >= NexaraInspectorMinWindowWidth && windowHeight >= NexaraInspectorMinWindowHeight

/** 加宽时最多 560dp，并给左侧留出至少 48dp 的会话可见边缘。 */
fun nexaraInspectorWidth(windowWidth: Dp, widened: Boolean): Dp =
    if (!widened) NexaraInspectorWidth else minOf(NexaraInspectorWideWidth, windowWidth - 48.dp).coerceAtLeast(NexaraInspectorWidth)

fun nexaraInspectorPlacement(windowWidth: Dp, inspectorWidth: Dp): NexaraInspectorPlacement =
    if (windowWidth - inspectorWidth - NexaraInspectorGutter >= NexaraInspectorMinMainWidth) NexaraInspectorPlacement.Shift
    else NexaraInspectorPlacement.Float

object NexaraPaneTags {
    const val PAGE = "nexara_secondary_page"
    const val INSPECTOR = "nexara_inspector"
    const val INSPECTOR_STRIP = "nexara_inspector_strip"
    fun level(key: String) = "nexara_pane_level_$key"
}

/**
 * 主内容与二级内容的自适应宿主。
 * - 窄窗口：二级内容作为二级页面整页覆盖主内容；[coverSuspended] 为 true 时（如生成中需要用户处理的审批）暂时让出，层级保留；
 * - 宽窗口（[nexaraInspectorAvailable]）：宿主不覆盖，经 [LocalNexaraInspectorLayout] 通知主内容，由主内容的浮动检视器承载同一份层级。
 *
 * 主内容始终在同一位置组合，折叠屏开合改变窗口宽度时不会重建主内容的状态。
 * 系统返回先交给最上层内容自身（详情页、子目录等），没有可后退的层级时调用 [onBack]。
 * 层级用 [NexaraPaneLevel] 叠放：下层保持组合、不接收返回，上层弹出后下层原样恢复。
 */
@Composable
fun NexaraAdaptivePaneHost(
    paneVisible: Boolean,
    onBack: () -> Unit,
    pane: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    coverSuspended: Boolean = false,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inspector = nexaraInspectorAvailable(maxWidth, maxHeight)
        val shown = !inspector && paneVisible && !coverSuspended
        CompositionLocalProvider(LocalNexaraInspectorLayout provides inspector) {
            // 整页覆盖时主内容不接收返回，避免其中后来出现的返回处理抢在二级页面之前。
            NexaraBackScope(active = !shown) { content() }
        }
        AnimatedVisibility(
            visible = shown,
            enter = NexaraMotion.sharedAxisXEnter(forward = true),
            exit = NexaraMotion.sharedAxisXExit(forward = false),
            modifier = Modifier.fillMaxSize(),
        ) {
            BackHandler(onBack = onBack)
            CompositionLocalProvider(LocalNexaraPanePresentation provides NexaraPanePresentation.Page) {
                Box(Modifier.fillMaxSize().testTag(NexaraPaneTags.PAGE), content = pane)
            }
        }
    }
}

/**
 * 二级内容中的一层。层级按组合顺序叠放，后组合的在上；[top] 为 false 的层被上层遮住，不接收返回。
 * 推入时自右侧滑入，弹出时向右滑出；随宿主一起首次出现时不另做动画。
 */
@Composable
fun BoxScope.NexaraPaneLevel(
    key: String,
    visible: Boolean,
    top: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = NexaraMotion.sharedAxisXEnter(forward = true),
        exit = NexaraMotion.sharedAxisXExit(forward = false),
        modifier = Modifier.matchParentSize(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().testTag(NexaraPaneTags.level(key)),
        ) {
            NexaraBackScope(active = top, content = content)
        }
    }
}

/**
 * [active] 为 false 时，内容中的 BackHandler 改挂到一个不接收系统返回的调度器上；
 * 重新激活时按当前时刻重新注册，排在已有的返回处理之前。切换只换提供的值，不改变组合结构，内容状态保留。
 */
@Composable
fun NexaraBackScope(active: Boolean, content: @Composable () -> Unit) {
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

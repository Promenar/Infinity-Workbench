package com.promenar.nexara.ui.common

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.ui.theme.NexaraMotion

/** 二级内容的承载方式：窄窗口为整页覆盖主内容的二级页面，宽窗口为停靠在主内容右侧的侧滑面板。 */
enum class NexaraPanePresentation { Page, Pane }

/** 二级内容据此决定根层导航图标：二级页面为返回箭头，侧滑面板为关闭。 */
val LocalNexaraPanePresentation = staticCompositionLocalOf { NexaraPanePresentation.Page }

internal val NexaraSidePaneWidth = 360.dp
internal val NexaraSidePaneMinMainWidth = 360.dp

/** 窗口宽度达到侧栏宽、分隔线与主内容最小宽之和时，二级内容以侧滑面板停靠。 */
internal val NexaraSidePaneMinWindowWidth = NexaraSidePaneWidth + 1.dp + NexaraSidePaneMinMainWidth

object NexaraPaneTags {
    const val PAGE = "nexara_secondary_page"
    const val PANE = "nexara_side_pane"
    fun level(key: String) = "nexara_pane_level_$key"
}

/**
 * 主内容与二级内容的自适应宿主。
 * - 窗口宽度不小于 [NexaraSidePaneMinWindowWidth] 时，二级内容作为侧滑面板停靠在右侧，主内容让出宽度、仍可操作；
 * - 否则作为二级页面整页覆盖主内容；[coverSuspended] 为 true 时（如生成中需要用户处理的审批）暂时让出，层级保留。
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
        val docked = maxWidth >= NexaraSidePaneMinWindowWidth
        val shown = paneVisible && (docked || !coverSuspended)
        val reserved by animateDpAsState(
            targetValue = if (docked && shown) NexaraSidePaneWidth + 1.dp else 0.dp,
            animationSpec = tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate),
            label = "nexara_pane_reserved",
        )
        Box(Modifier.fillMaxSize().padding(end = reserved)) {
            // 整页覆盖时主内容不接收返回，避免其中后来出现的返回处理抢在二级页面之前。
            NexaraBackScope(active = docked || !shown) { content() }
        }
        AnimatedVisibility(
            visible = shown,
            enter = if (docked) {
                slideInHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate)) { it } +
                    fadeIn(tween(NexaraMotion.DurationShort))
            } else NexaraMotion.sharedAxisXEnter(forward = true),
            exit = if (docked) {
                slideOutHorizontally(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedAccelerate)) { it } +
                    fadeOut(tween(NexaraMotion.DurationMedium))
            } else NexaraMotion.sharedAxisXExit(forward = false),
            modifier = if (docked) {
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(NexaraSidePaneWidth + 1.dp)
            } else Modifier.fillMaxSize(),
        ) {
            BackHandler(onBack = onBack)
            CompositionLocalProvider(
                LocalNexaraPanePresentation provides if (docked) NexaraPanePresentation.Pane else NexaraPanePresentation.Page,
            ) {
                if (docked) {
                    Row(Modifier.fillMaxSize().testTag(NexaraPaneTags.PANE)) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Box(Modifier.fillMaxSize(), content = pane)
                    }
                } else {
                    Box(Modifier.fillMaxSize().testTag(NexaraPaneTags.PAGE), content = pane)
                }
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

package com.promenar.nexara.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntSize

/**
 * 全站动效基准，参照 Material 3 的时长与缓动分档：
 * - 层级导航（进入子目录、面板内下一页）用共享 X 轴：前进从右侧滑入，返回从左侧滑入，滑动距离只取一小段并叠加淡入淡出；
 * - 同级内容替换（分段标签、加载/空/内容状态）用交叉淡入；
 * - 局部显隐（提示、进度、展开区）用纵向展开收起加淡入淡出。
 * 系统“移除动画”开启时 Compose 会把时长缩放到 0，这里不另做判断。
 */
object NexaraMotion {
    const val DurationShort = 150
    const val DurationMedium = 300
    /** 共享轴切换中旧内容先淡出的时长，新内容在其后淡入。 */
    const val DurationFadeOut = 90

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = FastOutSlowInEasing

    /** 共享轴滑动距离占内容宽度的比例，约等于手机上的 30–40dp。 */
    private const val SHARED_AXIS_SLIDE_FRACTION = 10

    private fun <T> fadeInSpec(): FiniteAnimationSpec<T> =
        tween(DurationMedium - DurationFadeOut, delayMillis = DurationFadeOut, easing = LinearOutSlowInEasing)

    private fun <T> fadeOutSpec(): FiniteAnimationSpec<T> = tween(DurationFadeOut, easing = FastOutLinearInEasing)

    /** 层级导航的进入动画；[forward] 为 true 表示进入更深一层。 */
    fun sharedAxisXEnter(forward: Boolean): EnterTransition =
        slideInHorizontally(tween(DurationMedium, easing = EmphasizedDecelerate)) { width ->
            (if (forward) 1 else -1) * width / SHARED_AXIS_SLIDE_FRACTION
        } + fadeIn(fadeInSpec())

    fun sharedAxisXExit(forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(DurationMedium, easing = EmphasizedDecelerate)) { width ->
            (if (forward) -1 else 1) * width / SHARED_AXIS_SLIDE_FRACTION
        } + fadeOut(fadeOutSpec())

    /** 层级导航：新旧内容同时横向错位并交叉淡入，容器高度平滑过渡。 */
    fun sharedAxisX(forward: Boolean): ContentTransform =
        ContentTransform(sharedAxisXEnter(forward), sharedAxisXExit(forward), sizeTransform = sizeTransform())

    /** 标题、计数等短文本的纵向替换：数值或层级增加时自下而上，减少时自上而下。 */
    fun sharedAxisY(forward: Boolean): ContentTransform = ContentTransform(
        targetContentEnter = slideInVertically(tween(DurationMedium, easing = EmphasizedDecelerate)) { height ->
            (if (forward) 1 else -1) * height / 2
        } + fadeIn(fadeInSpec()),
        initialContentExit = slideOutVertically(tween(DurationMedium, easing = EmphasizedDecelerate)) { height ->
            (if (forward) -1 else 1) * height / 2
        } + fadeOut(fadeOutSpec()),
        sizeTransform = sizeTransform(),
    )

    /** 同级内容替换：旧内容先淡出、新内容随后淡入，避免两层文字叠在一起。 */
    fun fadeThrough(): ContentTransform =
        ContentTransform(fadeIn(fadeInSpec()), fadeOut(fadeOutSpec()), sizeTransform = sizeTransform())

    /** 局部显隐的进入与退出。 */
    val expandIn: EnterTransition =
        expandVertically(tween(DurationMedium, easing = EmphasizedDecelerate)) +
            fadeIn(tween(DurationMedium, easing = LinearOutSlowInEasing))
    val shrinkOut: ExitTransition =
        shrinkVertically(tween(DurationShort + DurationFadeOut, easing = EmphasizedAccelerate)) +
            fadeOut(tween(DurationShort, easing = FastOutLinearInEasing))

    private fun sizeTransform(): SizeTransform =
        SizeTransform(clip = false) { _: IntSize, _: IntSize -> tween(DurationMedium, easing = EmphasizedDecelerate) }
}

/**
 * 按层级深度选择方向的共享 X 轴切换；深度相同（如刷新、同级跳转）时退化为交叉淡入。
 * 用于 AnimatedContent 的 transitionSpec。
 */
fun <S> AnimatedContentTransitionScope<S>.hierarchicalTransition(depth: (S) -> Int): ContentTransform {
    val from = depth(initialState)
    val to = depth(targetState)
    return if (from == to) NexaraMotion.fadeThrough() else NexaraMotion.sharedAxisX(forward = to > from)
}

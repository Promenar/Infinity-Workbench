package com.promenar.nexara.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlin.math.roundToInt

/** 菜单可用的竖向范围（窗口坐标，像素）、横向边距与和锚点的间隔。 */
internal data class NexaraMenuLimits(
    val marginPx: Int,
    val topLimitPx: Int,
    val bottomLimitPx: Int,
    val gapPx: Int,
)

/** 菜单左上角位置与缩放起点（0 为左/上，1 为右/下）。 */
internal data class NexaraMenuPlacement(val offset: IntOffset, val originX: Float, val originY: Float)

/**
 * 菜单位置：给出 [touch]（长按点，窗口坐标）时从按住的位置长出，默认向右下，放不下时向左或向上；
 * 否则贴着 [anchor] 放在下方、放不下时翻到上方，横向按锚点所在半边对齐，总朝屏幕中部展开。
 * 两侧都放不下时贴着空间较大的一侧，始终留在安全区内。
 */
internal fun nexaraMenuPlacement(
    anchor: IntRect,
    touch: IntOffset?,
    menu: IntSize,
    window: IntSize,
    limits: NexaraMenuLimits,
    ltr: Boolean,
): NexaraMenuPlacement {
    val minX = limits.marginPx
    val maxX = (window.width - menu.width - limits.marginPx).coerceAtLeast(minX)
    val growRight: Boolean
    val preferredX: Int
    if (touch != null) {
        val fitsRight = touch.x + menu.width <= window.width - limits.marginPx
        val fitsLeft = touch.x - menu.width >= limits.marginPx
        growRight = if (ltr) fitsRight || !fitsLeft else !fitsLeft && fitsRight
        preferredX = if (growRight) touch.x else touch.x - menu.width
    } else {
        val inLeftHalf = anchor.center.x * 2 < window.width
        growRight = inLeftHalf
        preferredX = if (growRight) anchor.left else anchor.right - menu.width
    }
    val x = preferredX.coerceIn(minX, maxX)

    val top = touch?.y ?: (anchor.top - limits.gapPx)
    val bottom = touch?.y ?: (anchor.bottom + limits.gapPx)
    val spaceBelow = limits.bottomLimitPx - bottom
    val spaceAbove = top - limits.topLimitPx
    val below = when {
        menu.height <= spaceBelow -> true
        menu.height <= spaceAbove -> false
        else -> spaceBelow >= spaceAbove
    }
    val rawY = if (below) bottom else top - menu.height
    val maxY = (limits.bottomLimitPx - menu.height).coerceAtLeast(limits.topLimitPx)
    val y = rawY.coerceIn(limits.topLimitPx, maxY)
    return NexaraMenuPlacement(IntOffset(x, y), originX = if (growRight) 0f else 1f, originY = if (below) 0f else 1f)
}

/**
 * 长按菜单的按住位置：给触发长按的元素加 [Modifier.nexaraMenuTouch]，每次按下记下手指的窗口坐标，
 * 交给 [NexaraDropdownMenu] 的 `touch` 后菜单从按住的位置长出。无障碍操作等没有按下的打开方式仍贴着锚点。
 */
@Stable
class NexaraMenuTouch {
    internal var windowPoint by mutableStateOf<IntOffset?>(null)
    internal var coordinates: LayoutCoordinates? = null

    /** 菜单关闭后清掉，下次由键盘或无障碍打开时不沿用旧位置。 */
    fun clear() {
        windowPoint = null
    }
}

@Composable
fun rememberNexaraMenuTouch(): NexaraMenuTouch = remember { NexaraMenuTouch() }

fun Modifier.nexaraMenuTouch(touch: NexaraMenuTouch): Modifier = this
    .onGloballyPositioned { touch.coordinates = it }
    .pointerInput(touch) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val window = touch.coordinates?.takeIf { it.isAttached }?.localToWindow(down.position) ?: return@awaitEachGesture
            touch.windowPoint = IntOffset(window.x.roundToInt(), window.y.roundToInt())
        }
    }

/** 菜单打开期间触发它的行铺一层浅色高亮，看得出菜单属于哪一行；不缩放，避免在分组卡片里顶出边界。 */
@Composable
fun Modifier.nexaraMenuHost(active: Boolean): Modifier {
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(NexaraMotion.DurationShort),
        label = "menu_host_tint",
    )
    return this.background(tint)
}

/**
 * Nexara 全站统一悬浮菜单：
 * - 16dp 圆角，surfaceContainerHigh 底色，0.5dp outlineVariant 描边与浮层阴影；
 * - 内衬 6dp，菜单项用 [NexaraMenuItem]（10dp 圆角按压高亮），分组用 [NexaraMenuDivider]；
 * - 放在触发控件所在的 Box 内，贴着控件弹出；长按触发时传 [touch]，从按住的位置长出；
 * - 从靠近锚点的角缩放淡入，关闭时先播放退场再移除。
 * [offset] 在算好的位置上再平移（与 Material 下拉菜单的偏移含义一致）。
 */
@Composable
fun NexaraDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    minWidth: Dp = 160.dp,
    properties: PopupProperties = PopupProperties(focusable = true),
    touch: NexaraMenuTouch? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (!visibleState.currentState && !visibleState.targetState && visibleState.isIdle) return

    val density = LocalDensity.current
    val bounds = rememberNexaraPopoverBounds()
    val limits = NexaraMenuLimits(
        marginPx = bounds.marginPx,
        topLimitPx = bounds.topLimitPx,
        bottomLimitPx = bounds.bottomLimitPx,
        gapPx = with(density) { NexaraSpacing.XSmall.roundToPx() },
    )
    val shift = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    // 只在打开时取按住点，菜单开着时再按别处不会让它跳走。
    val touchPoint = remember(expanded) { touch?.windowPoint?.takeIf { expanded } }
    val provider = remember(limits, shift, touchPoint) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val placement = nexaraMenuPlacement(
                    anchor = anchorBounds,
                    touch = touchPoint,
                    menu = popupContentSize,
                    window = windowSize,
                    limits = limits,
                    ltr = layoutDirection == LayoutDirection.Ltr,
                )
                origin = TransformOrigin(placement.originX, placement.originY)
                // 从按住点长出时偏移已无意义，只对贴锚点的菜单生效。
                return placement.offset + if (touchPoint == null) shift else IntOffset.Zero
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {
            touch?.clear()
            onDismissRequest()
        },
        properties = properties,
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(MenuEnterMillis)) +
                scaleIn(tween(MenuEnterMillis, easing = NexaraMotion.EmphasizedDecelerate), initialScale = 0.86f, transformOrigin = origin),
            exit = fadeOut(tween(MenuExitMillis)) +
                scaleOut(tween(MenuExitMillis, easing = NexaraMotion.EmphasizedAccelerate), targetScale = 0.94f, transformOrigin = origin),
        ) {
            Surface(
                shape = MenuShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shadowElevation = 8.dp,
                modifier = modifier.testTag(NexaraMenuTags.MENU),
            ) {
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = minWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(MenuInnerPadding),
                    content = content,
                )
            }
        }
    }
}

/**
 * 菜单项：10dp 圆角按压高亮，图标与文字间距 12dp；[destructive] 时文字与图标用错误色。
 * 参数与 Material 的 DropdownMenuItem 对齐，便于各处直接替换。
 * 点按时按 [haptic] 振动，默认轻 tick；复制等确认动作传确认档，不需要时传 null。
 */
@Composable
fun NexaraMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = MenuItemPadding,
    haptic: NexaraHapticEvent? = NexaraHapticEvent.SELECTION,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalNexaraHaptics.current
    val textColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        destructive -> scheme.error
        else -> scheme.onSurface
    }
    val iconColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        destructive -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 112.dp, maxWidth = 300.dp, minHeight = MenuItemMinHeight)
            .clip(MenuItemShape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptic?.let(haptics::perform)
                onClick()
            }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalContentColor provides iconColor) {
                Box(Modifier.padding(end = 12.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalContentColor provides textColor) {
                ProvideTextStyle(NexaraTypography.labelLarge, text)
            }
        }
        if (trailingIcon != null) {
            CompositionLocalProvider(LocalContentColor provides iconColor) {
                Box(Modifier.padding(start = 12.dp), contentAlignment = Alignment.Center) { trailingIcon() }
            }
        }
    }
}

/** 菜单内的分组线，左右与菜单项文字区对齐。 */
@Composable
fun NexaraMenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

object NexaraMenuTags {
    const val MENU = "nexara_menu"
}

private val MenuShape = RoundedCornerShape(16.dp)
private val MenuItemShape = RoundedCornerShape(10.dp)
private val MenuInnerPadding = 6.dp
private val MenuItemMinHeight = 44.dp
private val MenuItemPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private const val MenuEnterMillis = 180
private const val MenuExitMillis = 110

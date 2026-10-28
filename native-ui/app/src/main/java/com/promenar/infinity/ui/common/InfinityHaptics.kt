package com.promenar.infinity.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用内的触感事件。交互类在用户操作时立即触发；状态类只在需要用户留意的节点触发一次，
 * 不随流式输出、工具步骤等高频变化振动。
 */
enum class InfinityHapticEvent(val status: Boolean = false) {
    /** 分段、标签、选项等离散选择。 */
    SELECTION,
    /** 步进器加减、滑块跨过刻度。 */
    STEP,
    TOGGLE_ON,
    TOGGLE_OFF,
    /** 发送、批准、复制等确认动作。 */
    CONFIRM,
    /** 拒绝审批等否定动作。 */
    REJECT,
    /** 有工具调用等待你审批。 */
    ATTENTION(status = true),
    /** 较长的一轮生成完成。 */
    COMPLETED(status = true),
    /** 生成失败。 */
    FAILED(status = true),
}

/** 事件对应的系统触感常量；新常量不可用时回退到旧系统上语义最接近的一档。 */
internal fun infinityHapticConstant(event: InfinityHapticEvent, sdkInt: Int): Int = when (event) {
    InfinityHapticEvent.SELECTION, InfinityHapticEvent.STEP ->
        if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    InfinityHapticEvent.TOGGLE_ON ->
        if (sdkInt >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
    InfinityHapticEvent.TOGGLE_OFF ->
        if (sdkInt >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
    InfinityHapticEvent.CONFIRM, InfinityHapticEvent.COMPLETED ->
        if (sdkInt >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    InfinityHapticEvent.REJECT, InfinityHapticEvent.FAILED ->
        if (sdkInt >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    InfinityHapticEvent.ATTENTION -> HapticFeedbackConstants.LONG_PRESS
}

/**
 * 通过视图触感反馈振动：系统“触摸振动”关闭时由系统静默忽略，因此无需振动权限。
 * [interaction] 关闭时所有事件都不触发；[status] 只控制状态类事件。
 */
@Immutable
class InfinityHaptics(
    private val view: View?,
    val interaction: Boolean,
    val status: Boolean,
) {
    fun allows(event: InfinityHapticEvent): Boolean = interaction && (!event.status || status)

    fun perform(event: InfinityHapticEvent) {
        if (allows(event)) play(event)
    }

    fun toggle(checked: Boolean) = perform(if (checked) InfinityHapticEvent.TOGGLE_ON else InfinityHapticEvent.TOGGLE_OFF)

    /** 单选项被点击：选中项变化时轻振，点已选中的项不振动。 */
    fun select(alreadySelected: Boolean) {
        if (!alreadySelected) perform(InfinityHapticEvent.SELECTION)
    }

    /** 打开触感反馈的这一下偏好尚未生效，单独回应这次操作。 */
    fun confirmEnabled() = play(InfinityHapticEvent.TOGGLE_ON)

    private fun play(event: InfinityHapticEvent) {
        val target = view ?: return
        // 视图已分离或系统拒绝时不影响调用方的动作本身。
        runCatching { target.performHapticFeedback(infinityHapticConstant(event, Build.VERSION.SDK_INT)) }
    }

    companion object {
        val None = InfinityHaptics(view = null, interaction = false, status = false)
    }
}

/** 由 [com.promenar.infinity.ui.theme.InfinityTheme] 按外观偏好提供；预览与测试中不振动。 */
val LocalInfinityHaptics = staticCompositionLocalOf { InfinityHaptics.None }

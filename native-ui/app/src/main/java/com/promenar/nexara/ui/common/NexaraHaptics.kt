package com.promenar.nexara.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用内的触感事件。交互类在用户操作时立即触发；状态类只在需要用户留意的节点触发一次，
 * 不随流式输出、工具步骤等高频变化振动。
 */
enum class NexaraHapticEvent(val status: Boolean = false) {
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
internal fun nexaraHapticConstant(event: NexaraHapticEvent, sdkInt: Int): Int = when (event) {
    NexaraHapticEvent.SELECTION, NexaraHapticEvent.STEP ->
        if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    NexaraHapticEvent.TOGGLE_ON ->
        if (sdkInt >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
    NexaraHapticEvent.TOGGLE_OFF ->
        if (sdkInt >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
    NexaraHapticEvent.CONFIRM, NexaraHapticEvent.COMPLETED ->
        if (sdkInt >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    NexaraHapticEvent.REJECT, NexaraHapticEvent.FAILED ->
        if (sdkInt >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    NexaraHapticEvent.ATTENTION -> HapticFeedbackConstants.LONG_PRESS
}

/**
 * 通过视图触感反馈振动：系统“触摸振动”关闭时由系统静默忽略，因此无需振动权限。
 * [interaction] 关闭时所有事件都不触发；[status] 只控制状态类事件。
 */
@Immutable
class NexaraHaptics(
    private val view: View?,
    val interaction: Boolean,
    val status: Boolean,
) {
    fun allows(event: NexaraHapticEvent): Boolean = interaction && (!event.status || status)

    fun perform(event: NexaraHapticEvent) {
        val target = view ?: return
        if (!allows(event)) return
        // 视图已分离或系统拒绝时不影响调用方的动作本身。
        runCatching { target.performHapticFeedback(nexaraHapticConstant(event, Build.VERSION.SDK_INT)) }
    }

    fun toggle(checked: Boolean) = perform(if (checked) NexaraHapticEvent.TOGGLE_ON else NexaraHapticEvent.TOGGLE_OFF)

    companion object {
        val None = NexaraHaptics(view = null, interaction = false, status = false)
    }
}

/** 由 [com.promenar.nexara.ui.theme.NexaraTheme] 按外观偏好提供；预览与测试中不振动。 */
val LocalNexaraHaptics = staticCompositionLocalOf { NexaraHaptics.None }

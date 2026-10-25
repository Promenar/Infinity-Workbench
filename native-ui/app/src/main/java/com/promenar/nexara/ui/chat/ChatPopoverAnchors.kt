package com.promenar.nexara.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.promenar.nexara.ui.common.NexaraPopoverAnchor

/**
 * 会话界面宽窗口下贴着触发控件弹出的面板所用的锚点。面板由路由统一渲染，触发控件散落在输入区、顶栏与各轮回复里，
 * 由这里把两者连起来：[model] 是输入区模型按钮；[runMode] 与 [turnMemory] 由多个控件共用，点按的控件先把自己的位置交过来。
 */
@Stable
internal class ChatPopoverAnchors {
    val model = NexaraPopoverAnchor()
    val runMode = NexaraPopoverAnchor()
    val turnMemory = NexaraPopoverAnchor()
}

/** 为 null 时（预览、截图测试）触发控件不记录位置，面板按原有形式展示。 */
internal val LocalChatPopoverAnchors = staticCompositionLocalOf<ChatPopoverAnchors?> { null }

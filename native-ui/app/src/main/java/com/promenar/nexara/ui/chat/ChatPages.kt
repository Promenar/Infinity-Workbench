package com.promenar.nexara.ui.chat

/**
 * 会话的二级内容层级（资源管理器、Git 变更），由 [com.promenar.nexara.ui.common.NexaraAdaptivePaneHost] 承载。
 * 与单一模态层队列相互独立：宽窗口下侧滑面板常驻时仍可继续对话，审批到来也不会把面板收起。
 */
internal enum class ChatPage(val token: String) {
    Explorer("explorer"),
    Git("git"),
}

/** 从顶栏等会话入口打开：替换整个层级，以该页为根。 */
internal fun openChatPageRoot(page: ChatPage): List<ChatPage> = listOf(page)

/** 在当前最上层之上打开下一层；已在层级中的页移到最上层，不重复叠放。 */
internal fun pushChatPage(pages: List<ChatPage>, page: ChatPage): List<ChatPage> =
    if (pages.lastOrNull() == page) pages else pages.filterNot { it == page } + page

/** 弹出最上层；只剩根层时整体关闭。 */
internal fun popChatPage(pages: List<ChatPage>): List<ChatPage> = pages.dropLast(1)

internal fun saveChatPages(pages: List<ChatPage>): String = pages.joinToString(",") { it.token }

internal fun restoreChatPages(token: String): List<ChatPage> =
    token.split(',').mapNotNull { part -> ChatPage.entries.firstOrNull { it.token == part } }.distinct()

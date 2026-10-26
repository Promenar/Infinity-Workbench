package com.promenar.nexara.ui.chat

/**
 * 会话的二级内容层级，与单一模态层队列相互独立：宽窗口下检视器展开时仍可继续对话，审批到来也不会把它收起。
 * - 资源管理器、Git 变更、Agent 浏览器（[isPage]）：窄窗口由 [com.promenar.nexara.ui.common.NexaraAdaptivePaneHost] 整页承载，宽窗口在检视器中；
 * - 运行、画布：只作为宽窗口检视器的页签；窄窗口仍用运行详情面板与画布列表，不进入层级。
 * 宽窗口下层级的根即检视器当前页签，层级为空表示检视器收起。
 */
enum class ChatPage(val token: String, val isPage: Boolean) {
    Run("run", isPage = false),
    Explorer("explorer", isPage = true),
    Git("git", isPage = true),
    Canvas("canvas", isPage = false),
    Browser("browser", isPage = true),
}

/** 从顶栏、检视器页签等会话入口打开：替换整个层级，以该页为根。 */
internal fun openChatPageRoot(page: ChatPage): List<ChatPage> = listOf(page)

/** 在当前最上层之上打开下一层；已在层级中的页移到最上层，不重复叠放。 */
internal fun pushChatPage(pages: List<ChatPage>, page: ChatPage): List<ChatPage> =
    if (pages.lastOrNull() == page) pages else pages.filterNot { it == page } + page

/** 弹出最上层；只剩根层时整体关闭。 */
internal fun popChatPage(pages: List<ChatPage>): List<ChatPage> = pages.dropLast(1)

internal fun saveChatPages(pages: List<ChatPage>): String = pages.joinToString(",") { it.token }

internal fun restoreChatPages(token: String): List<ChatPage> =
    token.split(',').mapNotNull { part -> ChatPage.entries.firstOrNull { it.token == part } }.distinct()

/** 窄窗口整页承载的层级：只取资源管理器、Git 与浏览器；运行与画布只是宽窗口检视器的页签，窄窗口不覆盖会话。 */
internal fun chatPageStack(pages: List<ChatPage>): List<ChatPage> = pages.filter { it.isPage }

/**
 * 宽窗口检视器的开合：已在该页签的根层时收起，否则以该页签为根打开。
 * 顶栏图标与边缘条共用，与桌面端侧栏按钮的开合习惯一致。
 */
internal fun toggleChatInspector(pages: List<ChatPage>, page: ChatPage): List<ChatPage> =
    if (pages == listOf(page)) emptyList() else openChatPageRoot(page)

/** 展开检视器时的页签：沿用上次的页签；没有时有运行内容先看运行，否则看文件。 */
internal fun defaultChatInspectorRoot(last: ChatPage?, hasRunContent: Boolean): ChatPage =
    last ?: if (hasRunContent) ChatPage.Run else ChatPage.Explorer

package com.promenar.infinity.navigation

import androidx.navigation.NavHostController

/** 首页会话列（内屏三列的右列）没有会话时的占位页：显示新建入口。 */
internal const val HUB_PANE_EMPTY = "hub_pane_empty"

/**
 * 首页会话列内的跳转。会话列只放一个会话：在列表里选会话、会话里打开别的会话都换掉整列；
 * 子代理查看页压在会话之上，返回回到会话。[paneRoot] 给出当前会话列根层的具体路由。
 */
class HubPaneNavigator internal constructor(
    private val nav: NavHostController,
    private val paneRoot: () -> String?,
) {
    fun openSession(sessionId: String) {
        val route = NavDestinations.chatHero(sessionId)
        if (paneRoot() == route) {
            // 已是当前会话：收起压在它上面的子代理查看页。
            while (nav.previousBackStackEntry != null) nav.popBackStack()
            return
        }
        nav.navigate(route) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    fun open(route: String) {
        nav.navigate(route)
    }

    fun back() {
        nav.popBackStack()
    }

    /** 会话被删除等情况：会话列回到新建入口。 */
    fun clear() {
        nav.navigate(HUB_PANE_EMPTY) { popUpTo(nav.graph.id) { inclusive = true } }
    }
}

/** 会话列能承载的页面：会话与子代理查看页。 */
internal fun isHubPaneRoute(route: String): Boolean =
    route.substringBefore('/') in HubPaneRouteBases

/** 会话列的根层必须是会话。 */
internal fun isHubPaneRootRoute(route: String): Boolean =
    route.substringBefore('/') == ChatRouteBase && route.length > ChatRouteBase.length + 1

/** 会话列根层路由对应的会话 id；不是会话时为 null。 */
internal fun hubPaneSessionId(route: String?): String? =
    route?.takeIf(::isHubPaneRootRoute)?.substringAfter('/')

private val ChatRouteBase = NavDestinations.CHAT_HERO.substringBefore('/')
private val HubPaneRouteBases = setOf(ChatRouteBase, NavDestinations.SUBAGENT_VIEWER_ROUTE.substringBefore('/'))

/**
 * 窗口变宽时收进会话列的整页（主标签页之上全部是会话列可承载的页面）：会话列只放一个会话，
 * 取最上面的会话及压在它上面的页面；没有会话时返回 null。
 */
internal fun hubPagesAboveMainTabs(routesAboveMain: List<String>): List<String>? {
    val root = routesAboveMain.indexOfLast(::isHubPaneRootRoute)
    return if (root < 0) null else routesAboveMain.drop(root)
}

internal fun SettingsBackStack.takeHubPagesAboveMainTabs(): List<String>? =
    takePagesAboveMainTabs(::isHubPaneRoute, ::hubPagesAboveMainTabs)

package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.LocalInfinityHaptics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.clickable
import androidx.compose.material3.ripple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPaneTags
import com.promenar.infinity.ui.startup.StartupNotice
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/** 检视器页签与边缘条上的角标；[gitAvailable] 为 false（工作区不是 Git 仓库）时不显示 Git 页签。 */
internal data class ChatInspectorBadges(
    val running: Boolean = false,
    val pendingApprovals: Int = 0,
    /** 有计划、后台任务或状态面板等可看的运行内容。 */
    val hasRunContent: Boolean = false,
    val gitAvailable: Boolean = false,
    val gitChanges: Int = 0,
    val canvases: Int = 0,
    /** 本会话的 Agent 浏览器状态；为空表示还没有打开网页。 */
    val browser: com.promenar.infinity.data.browser.BrowserMode? = null,
    /** 会话提供浏览器入口时浏览器页签常驻，未打开网页时显示空状态。 */
    val browserEntry: Boolean = false,
) {
    /** 没有运行内容、改动与产物时检视器完全隐藏，只留顶栏入口。 */
    val hasContent: Boolean get() = running || pendingApprovals > 0 || hasRunContent || gitChanges > 0 || canvases > 0 || browser != null

    fun pages(): List<ChatPage> = ChatPage.entries.filter {
        (it != ChatPage.Git || gitAvailable) && (it != ChatPage.Browser || browserEntry || browser != null)
    }
}

private val ChatPage.icon: ImageVector
    get() = when (this) {
        ChatPage.Run -> Icons.Rounded.Checklist
        ChatPage.Explorer -> Icons.Outlined.Folder
        ChatPage.Git -> Icons.Rounded.AccountTree
        ChatPage.Canvas -> Icons.Rounded.Dashboard
        ChatPage.Browser -> Icons.Rounded.Public
    }

@Composable
private fun ChatPage.label(): String = stringResource(
    when (this) {
        ChatPage.Run -> R.string.chat_inspector_tab_run
        ChatPage.Explorer -> R.string.chat_inspector_tab_files
        ChatPage.Git -> R.string.chat_inspector_tab_git
        ChatPage.Canvas -> R.string.chat_inspector_tab_canvas
        ChatPage.Browser -> R.string.chat_inspector_tab_browser
    },
)

/** 页签名加角标含义，供只有图标的入口朗读。 */
@Composable
private fun ChatPage.description(badges: ChatInspectorBadges): String = listOfNotNull(
    label(),
    if (this == ChatPage.Run && badges.pendingApprovals > 0) {
        pluralStringResource(R.plurals.chat_inspector_pending_approvals, badges.pendingApprovals, badges.pendingApprovals)
    } else null,
    if (this == ChatPage.Run && badges.running) stringResource(R.string.chat_inspector_running) else null,
    if (this == ChatPage.Browser) browserDotDescription(badges.browser) else null,
    when (this) {
        ChatPage.Git -> badges.gitChanges
        ChatPage.Canvas -> badges.canvases
        else -> 0
    }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.chat_inspector_count, it, it) },
).joinToString(", ")

/** 页签图标：运行中换成转圈；待批准、Git 改动数与画布数挂在右上角。 */
/**
 * 页签图标与角标：角标贴在图标右上角外侧，只压住图标右缘 [BadgeOverlap]，数字变宽时向图标内侧多让一点，
 * 伸出图标右缘始终不超过 [BadgeReach]。[reserveBadge] 为 true 时把伸出部分计入宽度，后面的页签名称不会被压住。
 */
@Composable
private fun InspectorIcon(page: ChatPage, badges: ChatInspectorBadges, tint: Color, reserveBadge: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val count = when (page) {
        ChatPage.Run -> badges.pendingApprovals
        ChatPage.Git -> badges.gitChanges
        ChatPage.Canvas -> badges.canvases
        ChatPage.Explorer, ChatPage.Browser -> 0
    }
    // 浏览器页签不计数，只用圆点区分 Agent 操作中（主色）与需要用户处理（橙色）。
    val dot = if (page == ChatPage.Browser) browserDotColor(badges.browser) else null
    val badgeColor = if (page == ChatPage.Run) scheme.tertiary else scheme.primary
    val badgeContent = if (page == ChatPage.Run) scheme.onTertiary else scheme.onPrimary
    Layout(
        content = {
            if (page == ChatPage.Run && badges.running) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.primary)
                }
            } else {
                Icon(page.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            if (count > 0) {
                Badge(containerColor = badgeColor, contentColor = badgeContent) { Text(count.toString()) }
            } else if (dot != null) {
                Badge(containerColor = dot)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val icon = measurables[0].measure(loose)
        val badge = measurables.getOrNull(1)?.measure(loose)
        val reach = BadgeReach.roundToPx()
        val badgeX = if (badge == null) 0 else icon.width - maxOf(BadgeOverlap.roundToPx(), badge.width - reach)
        val width = if (badge != null && reserveBadge) badgeX + badge.width else icon.width
        layout(width, icon.height) {
            icon.place(0, 0)
            badge?.place(badgeX, -BadgeLift.roundToPx())
        }
    }
}

@Composable
private fun browserDotColor(mode: com.promenar.infinity.data.browser.BrowserMode?): Color? = when (mode) {
    com.promenar.infinity.data.browser.BrowserMode.AGENT -> MaterialTheme.colorScheme.primary
    com.promenar.infinity.data.browser.BrowserMode.HANDOFF -> MaterialTheme.colorScheme.tertiary
    else -> null
}

@Composable
private fun browserDotDescription(mode: com.promenar.infinity.data.browser.BrowserMode?): String? = when (mode) {
    com.promenar.infinity.data.browser.BrowserMode.AGENT -> stringResource(R.string.chat_inspector_browser_agent)
    com.promenar.infinity.data.browser.BrowserMode.HANDOFF -> stringResource(R.string.chat_inspector_browser_needs_you)
    else -> null
}

private val BadgeOverlap = 4.dp
private val BadgeReach = 12.dp
private val BadgeLift = 6.dp

private val InspectorShape = RoundedCornerShape(20.dp)

@Composable
private fun inspectorBorder() = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * 宽窗口会话右侧的浮动检视器：独立圆角卡片，不以竖线切开会话。
 * 底栏左侧是页签，右侧是加宽与收起；当前页签显示图标与名称，其余只显示图标与角标。
 * 内容以 [InfinityPanePresentation.Inspector] 承载：页面不再贴系统栏留白，根层不重复画顶栏。
 */
@Composable
internal fun ChatInspectorCard(
    root: ChatPage,
    badges: ChatInspectorBadges,
    widened: Boolean,
    onSelect: (ChatPage) -> Unit,
    onToggleWiden: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onLockTouch: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val paneLabel = stringResource(R.string.chat_inspector_label)
    val haptics = LocalInfinityHaptics.current
    Surface(
        modifier = modifier.semantics { paneTitle = paneLabel }.testTag(InfinityPaneTags.INSPECTOR),
        shape = InspectorShape,
        color = MaterialTheme.colorScheme.background,
        border = inspectorBorder(),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector) {
                Box(Modifier.weight(1f).fillMaxWidth(), content = content)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            // 页签与操作都在底栏：展开的内屏双手持握时拇指在下方，不必伸到屏幕顶部；收起留在最右，与边缘条底部的展开按钮同处。
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 页签多、生成中又多出触摸锁时，窄卡片放不下整行；页签区横向滚动，右侧操作始终可见。
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    badges.pages().forEach { page ->
                        InspectorTab(page = page, selected = page == root, badges = badges, onClick = { haptics.select(page == root); onSelect(page) })
                    }
                }
                // 宽窗口没有运行坞，生成中的触摸锁放在检视器底栏。
                onLockTouch?.let { TouchLockButton(onLock = it) }
                IconButton(onClick = { haptics.toggle(!widened); onToggleWiden() }, modifier = Modifier.testTag(ChatInspectorTags.WIDEN)) {
                    Icon(
                        if (widened) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(if (widened) R.string.chat_inspector_restore_width else R.string.chat_inspector_widen),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { haptics.toggle(false); onCollapse() }, modifier = Modifier.testTag(ChatInspectorTags.COLLAPSE)) {
                    Icon(
                        Icons.Rounded.KeyboardDoubleArrowRight,
                        contentDescription = stringResource(R.string.chat_inspector_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorTab(page: ChatPage, selected: Boolean, badges: ChatInspectorBadges, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant
    val description = page.description(badges)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.surfaceContainerHigh else Color.Transparent,
        modifier = Modifier
            .height(40.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            }
            .testTag(ChatInspectorTags.tab(page)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InspectorIcon(page, badges, tint, reserveBadge = true)
            if (selected) {
                Text(page.label(), style = InfinityTypography.labelLarge, color = tint, maxLines = 1)
            }
        }
    }
}

/**
 * 检视器收起后的边缘条：贴在会话右缘、底部对齐的竖向胶囊，只有页签图标与角标，点按以该页签展开。
 * 没有任何内容时整个检视器隐藏，边缘条也不出现。
 */
@Composable
internal fun ChatInspectorStrip(
    badges: ChatInspectorBadges,
    onOpen: (ChatPage) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onLockTouch: (() -> Unit)? = null,
) {
    val haptics = LocalInfinityHaptics.current
    Surface(
        modifier = modifier.testTag(InfinityPaneTags.INSPECTOR_STRIP),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.background,
        border = inspectorBorder(),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            badges.pages().forEach { page ->
                val description = page.description(badges)
                // 不用 IconButton：它把内容裁成 40dp 圆，伸出图标的角标会被切掉。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = null,
                            indication = ripple(bounded = false, radius = 20.dp),
                            role = Role.Button,
                            onClick = { haptics.toggle(true); onOpen(page) },
                        )
                        .semantics { contentDescription = description }
                        .testTag(ChatInspectorTags.stripItem(page)),
                    contentAlignment = Alignment.Center,
                ) {
                    InspectorIcon(page, badges, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(
                modifier = Modifier.width(24.dp).padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            onLockTouch?.let { TouchLockButton(onLock = it) }
            IconButton(onClick = { haptics.toggle(true); onExpand() }, modifier = Modifier.testTag(ChatInspectorTags.EXPAND)) {
                Icon(
                    Icons.Rounded.KeyboardDoubleArrowLeft,
                    contentDescription = stringResource(R.string.chat_inspector_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 运行页签：有待批准的操作时先给出跳转入口；没有运行内容时显示空状态。 */
@Composable
internal fun ChatInspectorRunTab(
    pendingApprovals: Int,
    onJumpToApproval: () -> Unit,
    hasRunContent: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (pendingApprovals > 0) {
            StartupNotice(
                title = pluralStringResource(R.plurals.chat_inspector_pending_approvals, pendingApprovals, pendingApprovals),
                detail = stringResource(R.string.chat_inspector_approval_detail),
                actionLabel = stringResource(R.string.chat_inspector_approval_action),
                icon = Icons.Rounded.VerifiedUser,
                onClick = onJumpToApproval,
                modifier = Modifier.padding(top = InfinitySpacing.Small).testTag(ChatInspectorTags.APPROVAL),
                titleMaxLines = 2,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasRunContent) content() else InspectorEmpty(
                icon = Icons.Rounded.Checklist,
                title = stringResource(R.string.chat_inspector_run_empty_title),
                detail = stringResource(R.string.chat_inspector_run_empty_detail),
            )
        }
    }
}

/** 画布页签没有画布时的空状态，与运行、Git 页签一样在检视器中居中。 */
@Composable
internal fun ChatInspectorCanvasEmpty() = InspectorEmpty(
    icon = ChatPage.Canvas.icon,
    title = stringResource(R.string.canvas_empty_title),
    detail = stringResource(R.string.canvas_empty_description),
)

@Composable
private fun InspectorEmpty(icon: ImageVector, title: String, detail: String, iconSize: Dp = 32.dp) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = InfinitySpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(iconSize))
        Spacer(Modifier.height(InfinitySpacing.Medium))
        Text(title, style = InfinityTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(InfinitySpacing.XSmall))
        Text(
            detail,
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal object ChatInspectorTags {
    const val WIDEN = "chat_inspector_widen"
    const val COLLAPSE = "chat_inspector_collapse"
    const val EXPAND = "chat_inspector_expand"
    const val APPROVAL = "chat_inspector_approval"
    const val TOGGLE = "chat_inspector_toggle"
    fun tab(page: ChatPage) = "chat_inspector_tab_${page.token}"
    fun stripItem(page: ChatPage) = "chat_inspector_strip_${page.token}"
}

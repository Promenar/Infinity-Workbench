package com.promenar.nexara.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.browser.BrowserActionKind
import com.promenar.nexara.data.browser.BrowserAgentAction
import com.promenar.nexara.data.browser.BrowserAvailability
import com.promenar.nexara.data.browser.BrowserHandoffReason
import com.promenar.nexara.data.browser.BrowserMode
import com.promenar.nexara.data.browser.BrowserUiState
import com.promenar.nexara.ui.common.LocalNexaraPanePresentation
import com.promenar.nexara.ui.common.NexaraBackButton
import com.promenar.nexara.ui.common.NexaraPanePresentation
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

/** 会话的 Agent 浏览器页：窄窗口整页、宽窗口在检视器“浏览器”页签。 */
@Composable
fun BrowserPage(sessionId: String, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val host = (context.applicationContext as NexaraApplication).agentBrowser
    val ids by host.sessionIds.collectAsState()
    val session = if (sessionId in ids) host.session(sessionId) else null
    val state = session?.control?.state?.collectAsState()?.value
    BrowserPageContent(
        state = state,
        availability = host.availability(),
        onBack = onBack,
        onTakeOver = { session?.takeOver() },
        onHandBack = { session?.handBack() },
        onReload = { session?.reload() },
        onPageBack = { session?.goBack() },
        onUpdateWebView = { openWebViewStore(context) },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (LocalNexaraPanePresentation.current == NexaraPanePresentation.Inspector) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            ),
    ) {
        session?.let { BrowserWebViewHost(it, Modifier.fillMaxSize()) }
    }
}

/** 无状态页面：[state] 为空表示本会话还没有打开网页。网页区域由 [web] 提供，截图测试可用占位内容代替。 */
@Composable
fun BrowserPageContent(
    state: BrowserUiState?,
    availability: BrowserAvailability,
    onBack: (() -> Unit)?,
    onTakeOver: () -> Unit,
    onHandBack: () -> Unit,
    onReload: () -> Unit,
    onPageBack: () -> Unit,
    onUpdateWebView: () -> Unit,
    modifier: Modifier = Modifier,
    web: @Composable () -> Unit,
) {
    Column(modifier.testTag(BrowserTags.PAGE)) {
        BrowserUrlBar(state, onBack, onReload, onPageBack)
        when {
            availability is BrowserAvailability.Unsupported -> BrowserUnsupported(availability.currentVersion, onUpdateWebView)
            state == null -> BrowserEmpty()
            else -> {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                AnimatedVisibility(visible = state.mode != BrowserMode.IDLE) {
                    BrowserStatusBar(state, onTakeOver, onHandBack)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { web() }
            }
        }
    }
}

@Composable
private fun BrowserUrlBar(state: BrowserUiState?, onBack: (() -> Unit)?, onReload: () -> Unit, onPageBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) NexaraBackButton(onClick = onBack) else Spacer(Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val url = state?.url.orEmpty()
                val secure = url.startsWith("https://")
                Icon(
                    if (secure) Icons.Rounded.Lock else Icons.Rounded.Public,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = displayUrl(url).ifEmpty { stringResource(R.string.browser_title) },
                    style = NexaraTypography.bodyMedium,
                    color = if (url.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(BrowserTags.URL),
                )
            }
        }
        if (state?.canGoBack == true) {
            IconButton(onClick = onPageBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_cd_back), tint = scheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onReload, enabled = state != null) {
            Icon(Icons.Rounded.Refresh, stringResource(R.string.browser_cd_reload), tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrowserStatusBar(state: BrowserUiState, onTakeOver: () -> Unit, onHandBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val handoff = state.mode == BrowserMode.HANDOFF
    val container = if (handoff) scheme.tertiaryContainer else scheme.surfaceContainerHigh
    val content = if (handoff) scheme.onTertiaryContainer else scheme.onSurface
    val (title, detail) = when (state.mode) {
        BrowserMode.AGENT -> stringResource(R.string.browser_mode_agent) to state.action?.let { browserActionText(it) }
        BrowserMode.USER -> stringResource(R.string.browser_mode_user) to stringResource(R.string.browser_mode_user_detail)
        else -> stringResource(handoffTitle(state.handoff ?: BrowserHandoffReason.LOGIN)) to stringResource(R.string.browser_handoff_detail)
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NexaraSpacing.Small, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(BrowserTags.STATUS),
    ) {
        if (handoff) {
            // 交接说明较长，按钮单独一行靠右，标题与说明不被挤压。
            Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = content, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    StatusText(title, detail, content, Modifier.weight(1f))
                }
                Button(
                    onClick = onHandBack,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary),
                    modifier = Modifier.align(Alignment.End).testTag(BrowserTags.HAND_BACK),
                ) { Text(stringResource(R.string.browser_handoff_done)) }
            }
        } else {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (state.mode == BrowserMode.AGENT) scheme.primary else scheme.outline, CircleShape))
                StatusText(title, detail, content, Modifier.weight(1f))
                if (state.mode == BrowserMode.AGENT) {
                    OutlinedButton(onClick = onTakeOver, modifier = Modifier.testTag(BrowserTags.TAKE_OVER)) {
                        Text(stringResource(R.string.browser_take_over))
                    }
                } else {
                    Button(onClick = onHandBack, modifier = Modifier.testTag(BrowserTags.HAND_BACK)) {
                        Text(stringResource(R.string.browser_hand_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(title: String, detail: String?, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = NexaraTypography.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!detail.isNullOrBlank()) {
            Text(detail, style = NexaraTypography.bodySmall, color = color.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun browserActionText(action: BrowserAgentAction): String = when (action.kind) {
    BrowserActionKind.OPEN -> stringResource(R.string.browser_action_open, action.label)
    BrowserActionKind.SNAPSHOT -> stringResource(R.string.browser_action_snapshot)
    BrowserActionKind.CLICK -> stringResource(R.string.browser_action_click, action.ref.orEmpty(), action.label)
    BrowserActionKind.TYPE -> stringResource(R.string.browser_action_type, action.label)
}

internal fun handoffTitle(reason: BrowserHandoffReason): Int = when (reason) {
    BrowserHandoffReason.LOGIN -> R.string.browser_handoff_login
    BrowserHandoffReason.PAYMENT -> R.string.browser_handoff_payment
    BrowserHandoffReason.VERIFICATION -> R.string.browser_handoff_verification
}

@Composable
private fun BrowserEmpty() = BrowserNotice(
    title = stringResource(R.string.browser_empty_title),
    detail = stringResource(R.string.browser_empty_detail),
)

@Composable
private fun BrowserUnsupported(version: String?, onUpdate: () -> Unit) = BrowserNotice(
    title = stringResource(R.string.browser_unsupported_title),
    detail = stringResource(R.string.browser_unsupported_detail, version ?: stringResource(R.string.browser_unsupported_missing)),
    action = stringResource(R.string.browser_update_webview) to onUpdate,
)

@Composable
private fun BrowserNotice(title: String, detail: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = NexaraSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(NexaraSpacing.Medium))
        Text(title, style = NexaraTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(NexaraSpacing.XSmall))
        Text(detail, style = NexaraTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(NexaraSpacing.Medium))
            Button(onClick = onClick) { Text(label) }
        }
    }
}

/** 把会话的 WebView 挂进一个容器；离开组合时交还给会话离屏保持。 */
@Composable
private fun BrowserWebViewHost(session: AgentBrowserSession, modifier: Modifier) {
    if (LocalInspectionMode.current) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest))
        return
    }
    val context = LocalContext.current
    key(session) {
        AndroidView(
            factory = { FrameLayout(it) },
            modifier = modifier,
            update = { container ->
                if (session.webView.parent !== container) {
                    container.removeAllViews()
                    container.addView(session.attach(context))
                }
            },
            onRelease = { container -> if (session.webView.parent === container) session.detach() },
        )
    }
}

/** 地址栏只显示主机与路径，不显示协议与查询参数。 */
internal fun displayUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    val path = uri.path?.takeIf { it.isNotEmpty() && it != "/" }.orEmpty()
    return host.removePrefix("www.") + path
}

private fun openWebViewStore(context: Context) {
    val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull() ?: "com.google.android.webview"
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

object BrowserTags {
    const val PAGE = "browser_page"
    const val URL = "browser_url"
    const val STATUS = "browser_status"
    const val TAKE_OVER = "browser_take_over"
    const val HAND_BACK = "browser_hand_back"
    const val TOP_BAR_ENTRY = "chat_browser_entry"
}

package com.promenar.infinity.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.promenar.infinity.R
import com.promenar.infinity.data.browser.BrowserActionException
import com.promenar.infinity.data.browser.BrowserActionKind
import com.promenar.infinity.data.browser.BrowserAgentAction
import com.promenar.infinity.data.browser.BrowserControl
import com.promenar.infinity.data.browser.BrowserElement
import com.promenar.infinity.data.browser.BrowserFieldPolicy
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserSnapshot
import com.promenar.infinity.data.browser.parseBrowserElement
import com.promenar.infinity.data.browser.parseBrowserSnapshot
import com.promenar.infinity.ui.theme.InfinityBrowserAgentHighlight
import com.promenar.infinity.ui.theme.InfinityBrowserHandoffHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 一个聊天会话的 Agent 浏览器页面。WebView 在应用 Context 上创建，挂到界面时换成 Activity Context，
 * 离屏时按最近一次的尺寸手动布局，让 Agent 在聊天页也能读取与操作页面。只在主线程使用。
 */
@SuppressLint("SetJavaScriptEnabled")
class AgentBrowserSession(
    private val app: Context,
    private val script: String,
    private val scope: CoroutineScope,
    private val onRenderGone: () -> Unit,
) {
    val control = BrowserControl()
    private val detachedContext = themed(app)
    private val contextWrapper = MutableContextWrapper(detachedContext)
    private val key = "__nx" + UUID.randomUUID().toString().replace("-", "")
    private val mutex = Mutex()
    private val navigations = MutableStateFlow(0)
    private var mainFrameError: String? = null
    private var idleJob: Job? = null
    private var destroyed = false
    private var lastWidth = app.resources.displayMetrics.widthPixels
    private var lastHeight = app.resources.displayMetrics.heightPixels

    val webView: WebView = AgentWebView(contextWrapper).also { view ->
        // 配置文件必须在加载任何内容之前设置。
        ProfileStore.getInstance().getOrCreateProfile(AgentBrowserHost.PROFILE_NAME)
        WebViewCompat.setProfile(view, AgentBrowserHost.PROFILE_NAME)
        configure(view)
        layoutDetached(view)
    }

    val isAttached: Boolean get() = webView.parent != null
    val waitingForUser: Boolean get() = control.state.value.mode.let { it == BrowserMode.USER || it == BrowserMode.HANDOFF }

    /** 交给界面挂载；Activity Context 让下拉框、JS 对话框等页面弹窗可以显示。 */
    fun attach(activityContext: Context): WebView {
        (webView.parent as? ViewGroup)?.removeView(webView)
        contextWrapper.baseContext = themed(activityContext)
        return webView
    }

    fun detach() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        contextWrapper.baseContext = detachedContext
        if (!destroyed) layoutDetached(webView)
    }

    fun takeOver() = control.userTakeOver(explicit = true)
    fun handBack() = control.handBack()
    // 用户点导航按钮与触摸网页一样视为接手，Agent 正在操作时会暂停等待交回。
    fun goBack() { if (webView.canGoBack()) { control.userTakeOver(explicit = false); webView.goBack() } }
    fun goForward() { if (webView.canGoForward()) { control.userTakeOver(explicit = false); webView.goForward() } }
    fun reload() { control.userTakeOver(explicit = false); webView.reload() }
    fun userOpen(url: String) { control.userTakeOver(explicit = false); webView.loadUrl(url) }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        idleJob?.cancel()
        control.endHandoff()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    suspend fun open(url: String): BrowserSnapshot =
        act(BrowserAgentAction(BrowserActionKind.OPEN, label = Uri.parse(url).host.orEmpty())) {
            val before = navigations.value
            mainFrameError = null
            webView.loadUrl(url)
            awaitNavigation(before, OPEN_START_TIMEOUT_MS)
            mainFrameError?.let { throw BrowserActionException("LOAD_FAILED", "The page failed to load: $it") }
            settledSnapshot()
        }

    suspend fun snapshot(includeText: Boolean, textOffset: Int): BrowserSnapshot =
        act(BrowserAgentAction(BrowserActionKind.SNAPSHOT)) { snapshotNow(includeText, textOffset) }

    suspend fun describe(ref: String): BrowserElement? = mutex.withLock {
        requirePage()
        parseBrowserElement(call("describe", buildJsonObject { put("ref", ref) }))
    }

    suspend fun click(element: BrowserElement): BrowserSnapshot =
        act(BrowserAgentAction(BrowserActionKind.CLICK, element.ref, element.name)) {
            checked(call("highlight", buildJsonObject { put("ref", element.ref); put("color", AGENT_COLOR) }))
            delay(HIGHLIGHT_LEAD_MS)
            val before = navigations.value
            checked(call("click", buildJsonObject { put("ref", element.ref) }))
            awaitNavigation(before, ACTION_START_TIMEOUT_MS)
            settledSnapshot()
        }

    suspend fun type(element: BrowserElement, text: String, submit: Boolean): BrowserSnapshot =
        act(BrowserAgentAction(BrowserActionKind.TYPE, element.ref, text)) {
            checked(call("highlight", buildJsonObject { put("ref", element.ref); put("color", AGENT_COLOR) }))
            delay(HIGHLIGHT_LEAD_MS)
            val before = navigations.value
            checked(call("type", buildJsonObject { put("ref", element.ref); put("text", text); put("submit", submit) }))
            if (submit) awaitNavigation(before, SUBMIT_START_TIMEOUT_MS) else delay(SETTLE_MS)
            settledSnapshot()
        }

    suspend fun handOff(element: BrowserElement, reason: BrowserHandoffReason): BrowserSnapshot = mutex.withLock {
        requirePage()
        idleJob?.cancel()
        control.requestHandoff(reason, BrowserAgentAction(BrowserActionKind.TYPE, element.ref, element.name))
        try {
            call("highlight", buildJsonObject {
                put("ref", element.ref); put("color", HANDOFF_COLOR); put("keep", true)
                put("label", app.getString(R.string.browser_handoff_field_badge))
            })
            if (!control.awaitHandBack(USER_WAIT_MS)) {
                throw BrowserActionException("USER_TIMEOUT", "The user did not hand the browser back within ${USER_WAIT_MS / 60_000} minutes.")
            }
        } finally {
            withContext(NonCancellable) {
                control.endHandoff()
                if (!destroyed) runCatching { call("clearHighlight", JsonObject(emptyMap())) }
            }
        }
        try {
            delay(SETTLE_MS)
            snapshotNow(includeText = false, textOffset = 0)
        } finally {
            scheduleIdle()
        }
    }

    private suspend fun <T> act(action: BrowserAgentAction, block: suspend () -> T): T = mutex.withLock {
        if (action.kind != BrowserActionKind.OPEN) requirePage()
        if (!control.awaitAgentTurn(USER_WAIT_MS)) {
            throw BrowserActionException("USER_TIMEOUT", "The user kept control of the browser for more than ${USER_WAIT_MS / 60_000} minutes.")
        }
        idleJob?.cancel()
        control.agentStarted(action)
        try {
            block()
        } finally {
            scheduleIdle()
        }
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(AGENT_IDLE_AFTER_MS)
            control.agentIdle()
        }
    }

    private fun requirePage() {
        if (destroyed) throw BrowserActionException("NO_PAGE", "The Agent browser page was closed.")
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") throw BrowserActionException("NO_PAGE", "No page is open in the Agent browser.")
    }

    private suspend fun snapshotNow(includeText: Boolean, textOffset: Int): BrowserSnapshot =
        parseBrowserSnapshot(call("snapshot", buildJsonObject { put("includeText", includeText); put("textOffset", textOffset) }))

    /** 加载完成事件可能早于脚本渲染出内容；页面仍在加载或还没有可操作元素时稍等再读。 */
    private suspend fun settledSnapshot(): BrowserSnapshot {
        var snapshot = snapshotNow(includeText = false, textOffset = 0)
        repeat(SETTLE_RETRIES) {
            if (!snapshot.loading && snapshot.elements.isNotEmpty()) return snapshot
            delay(SETTLE_MS)
            snapshot = snapshotNow(includeText = false, textOffset = 0)
        }
        return snapshot
    }

    /** 有导航就等它加载完成；只是页面内更新时稍等脚本渲染。 */
    private suspend fun awaitNavigation(before: Int, startTimeoutMs: Long) {
        val started = withTimeoutOrNull(startTimeoutMs) { navigations.first { it > before } } != null
        if (started) withTimeoutOrNull(LOAD_TIMEOUT_MS) { control.state.first { !it.loading } }
        delay(SETTLE_MS)
    }

    private suspend fun call(op: String, args: JsonObject): String? {
        if (destroyed) throw BrowserActionException("NO_PAGE", "The Agent browser page was closed.")
        val payload = JsonObject(args + ("sensitivePattern" to JsonPrimitive(BrowserFieldPolicy.pageHintPattern)))
        val source = "$script(${JsonPrimitive(key)},${JsonPrimitive(op)},$payload)"
        return withTimeoutOrNull(SCRIPT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(source, ValueCallback { if (continuation.isActive) continuation.resume(it) })
            }
        } ?: throw BrowserActionException("PAGE_UNRESPONSIVE", "The page did not respond to the browser script.")
    }

    private fun checked(raw: String?) {
        val result = runCatching { Json.parseToJsonElement(raw ?: "null").jsonObject }.getOrNull()
            ?: throw BrowserActionException("SCRIPT_ERROR", "The browser script returned no result.")
        if ((result["ok"] as? JsonPrimitive)?.booleanOrNull == true) return
        val code = (result["error"] as? JsonPrimitive)?.contentOrNull ?: "SCRIPT_ERROR"
        val message = (result["message"] as? JsonPrimitive)?.contentOrNull ?: "The browser action failed."
        throw BrowserActionException(code, message)
    }

    private fun layoutDetached(view: WebView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(lastWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(lastHeight, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, lastWidth, lastHeight)
    }

    @Suppress("DEPRECATION")
    private fun configure(view: WebView) {
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            saveFormData = false
        }
        WebViewCompat.getProfile(view).cookieManager.setAcceptThirdPartyCookies(view, false)
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    private inner class AgentWebView(context: Context) : WebView(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // Agent 操作时用户触碰页面即视为接手。
            if (event.actionMasked == MotionEvent.ACTION_DOWN) control.userTakeOver(explicit = false)
            return super.dispatchTouchEvent(event)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && parent != null) { lastWidth = w; lastHeight = h }
        }
    }

    private inner class Client : WebViewClient() {
        /** 只放行网页导航；intent:、market:、tel: 等外部协议一律不交给系统。 */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.scheme?.lowercase() !in WEB_SCHEMES

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameError = null
            navigations.value += 1
            control.updatePage { it.copy(url = url ?: it.url, loading = true, progress = 0, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            control.updatePage { it.copy(url = url ?: it.url, title = view.title.orEmpty(), loading = false, progress = 100, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            control.updatePage { it.copy(url = url ?: it.url, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) mainFrameError = error.description?.toString() ?: "error ${error.errorCode}"
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 渲染进程退出后该 WebView 不可再用；交给宿主销毁，下次打开时重建。
            onRenderGone()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = control.updatePage { it.copy(progress = newProgress) }
        override fun onReceivedTitle(view: WebView, title: String?) = control.updatePage { it.copy(title = title.orEmpty()) }
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean = false
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

        // 离屏时没有可承载对话框的 Activity：提示框直接确认，确认与输入框按取消处理，离开页面允许。
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            if (isAttached) false else true.also { result.confirm() }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            if (isAttached) false else true.also { result.cancel() }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
            if (isAttached) false else true.also { result.cancel() }
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            if (isAttached) false else true.also { result.confirm() }
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
        val AGENT_COLOR = InfinityBrowserAgentHighlight.cssHex()
        val HANDOFF_COLOR = InfinityBrowserHandoffHighlight.cssHex()
        const val HIGHLIGHT_LEAD_MS = 450L
        const val SETTLE_MS = 600L
        const val SETTLE_RETRIES = 5
        const val OPEN_START_TIMEOUT_MS = 8_000L
        const val ACTION_START_TIMEOUT_MS = 1_500L
        const val SUBMIT_START_TIMEOUT_MS = 3_000L
        const val LOAD_TIMEOUT_MS = 25_000L
        const val SCRIPT_TIMEOUT_MS = 10_000L
        const val AGENT_IDLE_AFTER_MS = 6_000L
        const val USER_WAIT_MS = 10 * 60_000L

        fun themed(context: Context) = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
    }
}

private fun Color.cssHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

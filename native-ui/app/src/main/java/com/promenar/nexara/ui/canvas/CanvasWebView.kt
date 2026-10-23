package com.promenar.nexara.ui.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.renderer.rememberRichContentPalette
import java.io.ByteArrayInputStream

/** 画布独占实例，不借用富内容池；预览完全让出触摸，只有全屏可交互。 */
@Composable
fun CanvasWebView(
    html: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    readOnly: Boolean = false,
    onInsertText: (String) -> Unit = {},
) {
    if (LocalInspectionMode.current) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow))
        return
    }
    val context = LocalContext.current
    val scriptsAllowed = remember {
        val provider = WebView.getCurrentWebViewPackage()
        supportsCanvasNetworkIsolation(provider?.packageName, provider?.versionName)
    }
    val palette = rememberRichContentPalette()
    val currentInsert by rememberUpdatedState(onInsertText)
    val client = remember { CanvasWebViewClient { currentInsert(it) } }
    val style = remember(palette) {
        """
        :root { color-scheme: ${if (palette.isDark) "dark" else "light"}; }
        html, body { background: ${palette.background}; color: ${palette.foreground}; }
        body { margin: 0; padding: 12px; box-sizing: border-box; font-family: system-ui, sans-serif; }
        * { box-sizing: border-box; } a { color: ${palette.primary}; }
        """.trimIndent()
    }
    val background = MaterialTheme.colorScheme.background.toArgb()
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        if (!scriptsAllowed) {
            Text(stringResource(R.string.canvas_scripts_unavailable), style = NexaraTypography.bodySmall, modifier = Modifier.padding(12.dp))
        }
        AndroidView(
            factory = { CanvasNativeWebView(context).apply { configure(client, scriptsAllowed) } },
            modifier = Modifier.fillMaxSize().weight(1f),
            onRelease = { view ->
                client.dispose()
                view.stopLoading()
                view.removeAllViews()
                view.destroy()
            },
            update = { view ->
                view.interactive = interactive
                view.isFocusable = interactive
                view.isFocusableInTouchMode = interactive
                view.importantForAccessibility = if (interactive) WebView.IMPORTANT_FOR_ACCESSIBILITY_AUTO else WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                view.setBackgroundColor(background)
                client.load(view, html, style, scriptsAllowed && interactive && !readOnly)
            },
        )
    }
}

private class CanvasNativeWebView(context: Context) : WebView(context) {
    var interactive: Boolean = true
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = interactive && super.dispatchTouchEvent(event)
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = interactive && super.onGenericMotionEvent(event)

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(client: CanvasWebViewClient, scriptsAllowed: Boolean) {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = scriptsAllowed
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = client
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
    }
}

private class CanvasWebViewClient(private val onInsertText: (String) -> Unit) : WebViewClient() {
    private val documents = CanvasDocumentSession()
    private var gate: CanvasBridgeGate? = null
    private var nativePort: WebMessagePort? = null
    private var connectedUrl: String? = null
    private var disposed = false

    fun load(view: WebView, source: String, style: String, bridgeEnabled: Boolean) {
        val previous = documents.currentUrl
        val url = documents.register(source, style, bridgeEnabled)
        if (previous != url) {
            disconnect()
            view.loadUrl(url)
        }
    }

    fun dispose() { disposed = true; disconnect() }

    private fun disconnect() {
        gate?.revoke()
        gate = null
        nativePort?.close()
        nativePort = null
        connectedUrl = null
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        disconnect()
        if (url != documents.currentUrl) view.stopLoading()
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (disposed || !documents.bridgeEnabled || url == null || url != documents.currentUrl || view.url != url || connectedUrl == url) return
        val token = documents.currentToken ?: return
        val currentGate = CanvasBridgeGate(url, token, enabled = true)
        val ports = view.createWebMessageChannel()
        gate = currentGate
        connectedUrl = url
        nativePort = ports[0]
        ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                if (disposed || gate !== currentGate) return
                currentGate.accept(message.data, view.url)?.let(onInsertText)
            }
        })
        // sandbox 使用不透明来源；精确主文档 URL 与端口租期约束交付，子文档无法访问父接口。
        view.postWebMessage(WebMessage(token, arrayOf(ports[1])), Uri.parse("*"))
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        val html = if (request.method == "GET") documents.contentFor(request.url.toString(), request.isForMainFrame) else null
        return if (html != null) WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))) else denied()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse = denied()

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", headers, ByteArrayInputStream(ByteArray(0)))
    private val headers = CANVAS_SECURITY_HEADERS
}

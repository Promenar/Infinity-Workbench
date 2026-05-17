package com.promenar.nexara.ui.renderer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichContentWebView(
    html: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    minHeight: Int = 40,
    maxHeight: Int = 600,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    val context = LocalContext.current
    var webViewHeight by remember { mutableIntStateOf(minHeight) }

    // ── 根治时序竞态：WebViewClient 必须在 loadDataWithBaseURL 之前就位 ──
    // 通过 rememberUpdatedState 保持 minHeight/maxHeight 引用的新鲜度，
    // 避免 WebViewClient 闭包捕获过期参数值。
    val currentMinHeight by rememberUpdatedState(minHeight)
    val currentMaxHeight by rememberUpdatedState(maxHeight)

    val webView = remember {
        RichContentWebViewPool.acquire(context).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 双重测量 + 延迟重测，覆盖 JS 动态渲染（Mermaid/ECharts 等）
                    fun measure() {
                        view?.evaluateJavascript(
                            "(function() { return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight); })()"
                        ) { heightStr ->
                            val h = heightStr.toIntOrNull() ?: currentMinHeight
                            webViewHeight = h.coerceIn(currentMinHeight, currentMaxHeight)
                        }
                    }
                    measure()
                    view?.postDelayed({ measure() }, 100)
                }
            }
        }.also { onWebViewCreated?.invoke(it) }
    }

    val finalHtml = remember(html, fontSize) {
        val trimmed = html.trim()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.contains("<html", ignoreCase = true) ||
            trimmed.contains("<body", ignoreCase = true)
        ) {
            html
        } else {
            val escaped = html.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    html, body {
                        margin: 0;
                        padding: 8px;
                        background-color: transparent;
                        font-family: monospace;
                        font-size: ${fontSize}px;
                        color: #A1A1AA;
                        word-wrap: break-word;
                        white-space: pre-wrap;
                        height: auto !important;
                        overflow: hidden;
                        box-sizing: border-box;
                    }
                </style>
            </head>
            <body>$escaped</body>
            </html>
            """.trimIndent()
        }
    }

    // 避免每次 Recompose 都重新 load，减少无用 WebView 重载
    var lastLoadedHtml by remember { mutableStateOf("") }

    DisposableEffect(webView) {
        onDispose {
            // 归还前重置 WebViewClient，防止测高闭包在池中长期持有
            webView.webViewClient = WebViewClient()
            RichContentWebViewPool.release(webView)
        }
    }

    AndroidView(
        factory = { webView },
        update = { wv ->
            wv.settings.defaultFontSize = fontSize
            if (lastLoadedHtml != finalHtml) {
                lastLoadedHtml = finalHtml
                wv.loadDataWithBaseURL(
                    "file:///android_asset/",
                    finalHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(webViewHeight.dp)
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLowest)
    )
}

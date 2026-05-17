package com.promenar.nexara.ui.renderer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
    val webView = remember {
        RichContentWebViewPool.acquire(context).also { onWebViewCreated?.invoke(it) }
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

    LaunchedEffect(webView, finalHtml) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight); })()"
                ) { heightStr ->
                    val h = heightStr.toIntOrNull() ?: minHeight
                    webViewHeight = h.coerceIn(minHeight, maxHeight)
                }
                view?.postDelayed({
                    view.evaluateJavascript(
                        "(function() { return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight); })()"
                    ) { heightStr ->
                        val h = heightStr.toIntOrNull() ?: minHeight
                        webViewHeight = h.coerceIn(minHeight, maxHeight)
                    }
                }, 100)
            }
        }
    }

    DisposableEffect(webView) {
        onDispose { RichContentWebViewPool.release(webView) }
    }

    AndroidView(
        factory = { webView },
        update = { wv ->
            wv.settings.defaultFontSize = fontSize
            wv.loadDataWithBaseURL(
                "file:///android_asset/",
                finalHtml,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .height(webViewHeight.dp)
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLowest)
    )
}

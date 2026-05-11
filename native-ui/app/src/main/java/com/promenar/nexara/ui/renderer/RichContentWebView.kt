package com.promenar.nexara.ui.renderer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
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
    minHeight: Int = 40,
    maxHeight: Int = 600,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    val context = LocalContext.current
    var webViewHeight by remember { mutableIntStateOf(minHeight) }
    val webView = remember {
        RichContentWebViewPool.acquire(context).also { onWebViewCreated?.invoke(it) }
    }

    LaunchedEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { return document.body.scrollHeight; })()"
                ) { heightStr ->
                    val h = heightStr.toIntOrNull() ?: minHeight
                    webViewHeight = h.coerceIn(minHeight, maxHeight)
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose { RichContentWebViewPool.release(webView) }
    }

    AndroidView(
        factory = { webView },
        update = { wv ->
            wv.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp, max = maxHeight.dp)
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLowest)
    )
}

package com.promenar.nexara.ui.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun InlineLatexSpan(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenDensity = context.resources.displayMetrics.density
    val maxWidthPx = context.resources.displayMetrics.widthPixels
    val maxHeightPx = (40 * screenDensity).toInt()

    var bitmap by remember(latex) { mutableStateOf<Bitmap?>(null) }
    var contentWidthDp by remember(latex) { mutableStateOf(100.dp) }
    var contentHeightDp by remember(latex) { mutableStateOf(24.dp) }

    val webView = remember { RichContentWebViewPool.acquire(context) }

    DisposableEffect(Unit) {
        onDispose { RichContentWebViewPool.release(webView) }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier
                .width(contentWidthDp)
                .height(contentHeightDp)
        )
    } else {
        AndroidView(
            factory = { webView },
            update = { wv ->
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "(function(){ return document.body.scrollWidth + ',' + document.body.scrollHeight; })()"
                        ) { dims ->
                            val parts = dims.replace("\"", "").split(",")
                            val cssW = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 100
                            val cssH = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 24
                            val pxW = (cssW * screenDensity).toInt()
                                .coerceAtLeast(1).coerceAtMost(maxWidthPx)
                            val pxH = (cssH * screenDensity).toInt()
                                .coerceAtLeast(1).coerceAtMost(maxHeightPx)
                            contentWidthDp = with(density) { pxW.toDp() }
                            contentHeightDp = with(density) { pxH.toDp() }
                            view?.post {
                                val viewW = view.width.coerceAtLeast(1)
                                val viewH = view.height.coerceAtLeast(1)
                                val full = Bitmap.createBitmap(
                                    viewW, viewH, Bitmap.Config.ARGB_8888
                                )
                                view.draw(Canvas(full))
                                val cropW = pxW.coerceAtMost(viewW)
                                val cropH = pxH.coerceAtMost(viewH)
                                bitmap = Bitmap.createBitmap(full, 0, 0, cropW, cropH)
                            }
                        }
                    }
                }
                wv.loadDataWithBaseURL(
                    "file:///android_asset/",
                    buildInlineLatexHtml(latex, fontSize),
                    "text/html",
                    "UTF-8",
                    null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp, max = 40.dp)
        )
    }
}

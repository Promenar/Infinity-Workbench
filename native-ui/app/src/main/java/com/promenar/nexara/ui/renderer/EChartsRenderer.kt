package com.promenar.nexara.ui.renderer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun EChartsBlock(
    optionJson: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showFullScreen by remember { mutableStateOf(false) }
    val webView = remember { RichContentWebViewPool.acquire(context) }
    var webViewHeight by remember { mutableIntStateOf(200) }
    val html = remember(optionJson) { buildEChartsHtml(optionJson, fullScreen = false) }

    LaunchedEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { return document.body.scrollHeight; })()"
                ) { heightStr ->
                    val h = heightStr?.toIntOrNull() ?: 200
                    webViewHeight = h.coerceIn(200, 500)
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose { RichContentWebViewPool.release(webView) }
    }

    Column(modifier = modifier.semantics { contentDescription = "Interactive chart" }) {
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 500.dp)
                .clip(NexaraShapes.medium)
                .background(NexaraColors.SurfaceLowest)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showFullScreen = true }) {
                Icon(
                    Icons.Rounded.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = NexaraColors.OnSurfaceVariant
                )
            }
            IconButton(onClick = { exportCSV(optionJson, context) }) {
                Icon(
                    Icons.Rounded.TableChart,
                    contentDescription = "Export CSV",
                    tint = NexaraColors.OnSurfaceVariant
                )
            }
            IconButton(onClick = { exportPNG(webView, context) }) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Export PNG",
                    tint = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }

    if (showFullScreen) {
        Dialog(
            onDismissRequest = { showFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                RichContentWebView(
                    html = buildEChartsHtml(optionJson, fullScreen = true),
                    modifier = Modifier.fillMaxSize(),
                    minHeight = 40,
                    maxHeight = Int.MAX_VALUE
                )
                IconButton(
                    onClick = { showFullScreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

private fun buildEChartsHtml(optionJson: String, fullScreen: Boolean): String {
    val encoded = android.util.Base64.encodeToString(
        optionJson.toByteArray(), android.util.Base64.NO_WRAP
    )
    val height = if (fullScreen) "100vh" else "350px"
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <script src="echarts/echarts.min.js"></script>
        <style>
            body { margin: 0; padding: 0; background: transparent; }
            #chart { width: 100%; height: $height; }
        </style>
    </head>
    <body>
        <div id="chart"></div>
        <script>
            try {
                var chart = echarts.init(document.getElementById('chart'), 'dark');
                var option = JSON.parse(atob("$encoded"));
                option.backgroundColor = 'transparent';
                option.toolbox = {
                    show: true,
                    feature: {
                        saveAsImage: { show: true, title: 'Save' },
                        dataView: { show: true, title: 'Data', readOnly: true },
                        restore: { show: true, title: 'Reset' }
                    }
                };
                option.dataZoom = [{ type: 'inside' }, { type: 'slider' }];
                chart.setOption(option);
                window.addEventListener('resize', function() { chart.resize(); });
            } catch(e) {
                document.getElementById('chart').innerHTML =
                    '<p style="color:#FFB4AB;font-size:12px;">ECharts Error: ' + e.message + '</p>';
            }
        </script>
    </body>
    </html>
    """.trimIndent()
}

private fun exportCSV(optionJson: String, context: Context) {
    val csv = buildString {
        try {
            val option = Json.parseToJsonElement(optionJson).jsonObject
            val series = option["series"]?.jsonArray ?: return@buildString
            series.forEach { s ->
                val data = s.jsonObject["data"]?.jsonArray ?: return@forEach
                data.forEach { point ->
                    val line = when (point) {
                        is JsonArray -> point.joinToString(",") { it.jsonPrimitive.content }
                        else -> point.jsonPrimitive.content
                    }
                    appendLine(line)
                }
            }
        } catch (_: Exception) {
            return
        }
    }
    if (csv.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    context.startActivity(
        Intent.createChooser(intent, "Export CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun exportPNG(webView: WebView, context: Context) {
    if (webView.width <= 0 || webView.height <= 0) return
    val bitmap = Bitmap.createBitmap(
        webView.width, webView.height, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    webView.draw(canvas)

    val filename = "echarts_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        ?: return

    resolver.openOutputStream(uri)?.use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
    }

    bitmap.recycle()
}

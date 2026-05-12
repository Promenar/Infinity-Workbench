package com.promenar.nexara.ui.renderer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.nexara.ui.theme.NexaraColors

private val HTML_ARTIFACT_LANGUAGES = setOf("html", "htm", "svg", "xml")

internal fun isHtmlArtifact(language: String?): Boolean =
    language != null && language.lowercase() in HTML_ARTIFACT_LANGUAGES

@Composable
fun HtmlArtifactCard(
    htmlCode: String,
    language: String?,
    fontSize: Int = 13,
    modifier: Modifier = Modifier
) {
    if (!isHtmlArtifact(language)) return

    var showFullScreen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHigh)
    ) {
        Column {
            RichContentWebView(
                html = htmlCode,
                fontSize = fontSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                minHeight = 150,
                onWebViewCreated = { wv -> webView = wv }
            )

            HorizontalDivider(
                color = NexaraColors.OutlineVariant,
                thickness = 0.5.dp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    webView?.let { exportHtmlArtifactPng(it, context) }
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Export PNG",
                        modifier = Modifier.size(16.dp),
                        tint = NexaraColors.OnSurfaceVariant
                    )
                    Text(
                        "Export PNG",
                        modifier = Modifier.padding(start = 4.dp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
                TextButton(onClick = { showFullScreen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Fullscreen,
                        contentDescription = "Full screen",
                        modifier = Modifier.size(16.dp),
                        tint = NexaraColors.OnSurfaceVariant
                    )
                    Text(
                        "Full Screen Preview",
                        modifier = Modifier.padding(start = 4.dp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }
    }

    if (showFullScreen) {
        HtmlArtifactsPopup(
            htmlCode = htmlCode,
            fontSize = fontSize,
            onDismiss = { showFullScreen = false }
        )
    }
}

@Composable
fun HtmlArtifactsPopup(
    htmlCode: String,
    fontSize: Int = 13,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NexaraColors.SurfaceDim)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexaraColors.SurfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HTML Preview",
                        style = MaterialTheme.typography.titleSmall,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        webView?.let { exportHtmlArtifactPng(it, context) }
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Export PNG",
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    RichContentWebView(
                        html = htmlCode,
                        fontSize = fontSize,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = 200,
                        maxHeight = 10000,
                        onWebViewCreated = { wv -> webView = wv }
                    )
                }
            }
        }
    }
}

private fun exportHtmlArtifactPng(webView: WebView, context: Context) {
    if (webView.width <= 0 || webView.height <= 0) return
    val bitmap = Bitmap.createBitmap(
        webView.width, webView.height, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    webView.draw(canvas)

    val filename = "html_artifact_${System.currentTimeMillis()}.png"
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

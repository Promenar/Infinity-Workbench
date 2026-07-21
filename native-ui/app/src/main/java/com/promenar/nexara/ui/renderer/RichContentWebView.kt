package com.promenar.nexara.ui.renderer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.nexaraDomainColors
import java.util.Locale

@Immutable
internal data class RichContentPalette(
    val background: String,
    val foreground: String,
    val surface: String,
    val outline: String,
    val primary: String,
    val error: String,
    val codeBackground: String,
    val codeForeground: String,
    val isDark: Boolean,
)

private fun Color.toCssColor(): String = String.format(
    Locale.US,
    "#%06X",
    toArgb() and 0xFFFFFF,
)

@Composable
internal fun rememberRichContentPalette(): RichContentPalette {
    val scheme = MaterialTheme.colorScheme
    val domainColors = MaterialTheme.nexaraDomainColors
    return remember(
        scheme.background,
        scheme.onBackground,
        scheme.surfaceContainer,
        scheme.outline,
        scheme.primary,
        scheme.error,
        scheme.surfaceContainerLow,
        domainColors.codeString,
    ) {
        RichContentPalette(
            background = scheme.background.toCssColor(),
            foreground = scheme.onBackground.toCssColor(),
            surface = scheme.surfaceContainer.toCssColor(),
            outline = scheme.outline.toCssColor(),
            primary = scheme.primary.toCssColor(),
            error = scheme.error.toCssColor(),
            codeBackground = scheme.surfaceContainerLow.toCssColor(),
            codeForeground = domainColors.codeString.toCssColor(),
            isDark = scheme.background.luminance() < 0.5f,
        )
    }
}

private fun themeStyle(palette: RichContentPalette, fontSize: Int): String = """
    <style id="nexara-theme">
        :root {
            color-scheme: ${if (palette.isDark) "dark" else "light"};
            --nexara-background: ${palette.background};
            --nexara-foreground: ${palette.foreground};
            --nexara-surface: ${palette.surface};
            --nexara-outline: ${palette.outline};
            --nexara-primary: ${palette.primary};
            --nexara-error: ${palette.error};
            --nexara-code-background: ${palette.codeBackground};
            --nexara-code-foreground: ${palette.codeForeground};
        }
        html, body {
            background-color: var(--nexara-background);
            color: var(--nexara-foreground);
        }
        a { color: var(--nexara-primary); }
        pre, code, kbd, samp {
            background-color: var(--nexara-code-background);
            color: var(--nexara-code-foreground);
        }
        pre, blockquote, table, th, td { border-color: var(--nexara-outline); }
        .error, [data-nexara-error] { color: var(--nexara-error); }
        body { font-size: ${fontSize}px; }
    </style>
""".trimIndent()

private fun applyThemeToHtml(
    html: String,
    fontSize: Int,
    palette: RichContentPalette,
): String {
    val trimmed = html.trim()
    val style = themeStyle(palette, fontSize)
    return if (
        trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
        trimmed.contains("<html", ignoreCase = true) ||
        trimmed.contains("<body", ignoreCase = true)
    ) {
        val closingHead = Regex("</head>", RegexOption.IGNORE_CASE)
        if (closingHead.containsMatchIn(html)) {
            html.replaceFirst(closingHead, "$style\n</head>")
        } else {
            "$style\n$html"
        }
    } else {
        val escaped = html.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            $style
            <style>
                html, body {
                    margin: 0;
                    padding: 8px;
                    font-family: monospace;
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
    val palette = rememberRichContentPalette()
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

    val finalHtml = remember(html, fontSize, palette) {
        applyThemeToHtml(html, fontSize, palette)
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
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    )
}

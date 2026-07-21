package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun PlantUmlBlock(
    code: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 13
) {
    val palette = rememberRichContentPalette()
    val html = remember(code, fontSize, palette) {
        buildPlantUmlHtml(code, fontSize, palette)
    }
    RichContentWebView(
        html = html,
        modifier = modifier.semantics {
            contentDescription = "PlantUML diagram: ${code.take(60)}"
        },
        fontSize = fontSize,
        minHeight = 100,
        maxHeight = 600
    )
}

private fun buildPlantUmlHtml(
    code: String,
    fontSize: Int = 13,
    palette: RichContentPalette,
): String {
    val encoded = encodeForKroki(code)
    val krokiUrl = "https://kroki.io/plantuml/svg/$encoded"
    val escapedCode = code
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\$")
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            body { margin: 0; padding: 12px; background: ${palette.background}; color: ${palette.foreground}; display: flex; justify-content: center; font-size: ${fontSize}px; }
            img { max-width: 100%; height: auto; }
            pre {
                display: none;
                background: ${palette.codeBackground}; color: ${palette.codeForeground}; padding: 12px;
                border: 1px solid ${palette.outline};
                border-radius: 8px; font-size: ${fontSize}px; white-space: pre-wrap;
                word-break: break-word; max-width: 100%; overflow-x: auto;
            }
        </style>
    </head>
    <body>
        <img id="diagram" alt="PlantUML diagram" />
        <pre id="fallback"></pre>
        <script>
            var code = `$escapedCode`;
            document.getElementById('fallback').textContent = code;
            fetch('$krokiUrl')
                .then(function(r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.text();
                })
                .then(function(svg) {
                    var parsed = new DOMParser().parseFromString(svg, 'image/svg+xml');
                    var root = parsed.documentElement;
                    var style = parsed.createElementNS('http://www.w3.org/2000/svg', 'style');
                    style.textContent = `
                        svg { background: ${palette.background}; color: ${palette.foreground}; }
                        text { fill: ${palette.foreground} !important; }
                        rect, polygon, ellipse { fill: ${palette.surface} !important; stroke: ${palette.outline} !important; }
                        line, path { stroke: ${palette.outline} !important; }
                        a text { fill: ${palette.primary} !important; }
                        .error text { fill: ${palette.error} !important; }
                    `;
                    root.insertBefore(style, root.firstChild);
                    var themedSvg = new XMLSerializer().serializeToString(root);
                    document.getElementById('diagram').src =
                        'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(themedSvg)));
                    document.getElementById('diagram').style.display = '';
                    document.getElementById('fallback').style.display = 'none';
                })
                .catch(function(e) {
                    document.getElementById('diagram').style.display = 'none';
                    document.getElementById('fallback').style.display = 'block';
                });
        </script>
    </body>
    </html>
    """.trimIndent()
}

private fun encodeForKroki(source: String): String {
    val deflater = java.util.zip.Deflater(9)
    deflater.setInput(source.toByteArray(Charsets.UTF_8))
    deflater.finish()
    val output = ByteArray(32768)
    val size = deflater.deflate(output)
    deflater.end()
    return android.util.Base64.encodeToString(
        output.copyOf(size),
        android.util.Base64.NO_PADDING or
                android.util.Base64.URL_SAFE or
                android.util.Base64.NO_WRAP
    )
}

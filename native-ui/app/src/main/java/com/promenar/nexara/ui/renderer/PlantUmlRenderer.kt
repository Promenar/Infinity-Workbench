package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun PlantUmlBlock(
    code: String,
    modifier: Modifier = Modifier
) {
    val html = buildPlantUmlHtml(code)
    RichContentWebView(
        html = html,
        modifier = modifier.semantics {
            contentDescription = "PlantUML diagram: ${code.take(60)}"
        },
        minHeight = 100,
        maxHeight = 600
    )
}

private fun buildPlantUmlHtml(code: String): String {
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
            body { margin: 0; padding: 12px; background: transparent; display: flex; justify-content: center; }
            img { max-width: 100%; height: auto; }
            pre {
                display: none;
                background: #1C1B1D; color: #E5E1E4; padding: 12px;
                border-radius: 8px; font-size: 13px; white-space: pre-wrap;
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
                    document.getElementById('diagram').src =
                        'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svg)));
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

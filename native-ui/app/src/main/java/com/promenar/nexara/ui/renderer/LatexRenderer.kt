package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun LatexBlock(
    latex: String,
    modifier: Modifier = Modifier
) {
    val html = buildLatexHtml(latex)
    RichContentWebView(
        html = html,
        modifier = modifier,
        minHeight = 30,
        maxHeight = 400
    )
}

private fun buildLatexHtml(latex: String): String {
    val escaped = latex
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")

    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <link rel="stylesheet" href="katex/katex.min.css">
        <script src="katex/katex.min.js"></script>
        <style>
            body {
                margin: 0; padding: 12px;
                background: transparent;
                color: #E5E1E4;
                display: flex; justify-content: center; align-items: center;
                min-height: 20px;
            }
            .katex { font-size: 1.1em; }
            .error { color: #FFB4AB; font-size: 12px; }
        </style>
    </head>
    <body>
        <div id="math"></div>
        <script>
            try {
                katex.render("$escaped", document.getElementById("math"), {
                    throwOnError: false,
                    displayMode: true
                });
            } catch(e) {
                document.getElementById("math").innerHTML =
                    '<span class="error">' + e.message + '</span>';
            }
        </script>
    </body>
    </html>
    """.trimIndent()
}

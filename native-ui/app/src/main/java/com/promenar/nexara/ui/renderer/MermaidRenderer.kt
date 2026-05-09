package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MermaidBlock(
    code: String,
    modifier: Modifier = Modifier
) {
    val html = buildMermaidHtml(code)
    RichContentWebView(
        html = html,
        modifier = modifier,
        minHeight = 100,
        maxHeight = 600
    )
}

private fun buildMermaidHtml(code: String): String {
    val escaped = code
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")

    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <script src="mermaid/mermaid.min.js"></script>
        <style>
            body { margin: 0; padding: 12px; background: transparent; }
            .mermaid { display: flex; justify-content: center; }
            .mermaid svg { max-width: 100%; height: auto; }
            .node rect, .node circle, .node polygon { fill: #2A2A2C !important; stroke: #464554 !important; }
            .nodeLabel, .edgeLabel { color: #E5E1E4 !important; fill: #E5E1E4 !important; }
            .edgePath .path { stroke: #908FA0 !important; }
            .cluster rect { fill: #201F22 !important; stroke: #464554 !important; }
        </style>
    </head>
    <body>
        <div class="mermaid">
            $escaped
        </div>
        <script>
            mermaid.initialize({
                startOnLoad: true,
                theme: 'dark',
                themeVariables: {
                    darkMode: true,
                    background: 'transparent',
                    primaryColor: '#C0C1FF',
                    primaryTextColor: '#E5E1E4',
                    lineColor: '#908FA0',
                    secondaryColor: '#2A2A2C',
                    tertiaryColor: '#201F22'
                }
            });
        </script>
    </body>
    </html>
    """.trimIndent()
}

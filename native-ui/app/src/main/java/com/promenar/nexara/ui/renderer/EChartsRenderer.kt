package com.promenar.nexara.ui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun EChartsBlock(
    optionJson: String,
    modifier: Modifier = Modifier
) {
    val html = buildEChartsHtml(optionJson)
    RichContentWebView(
        html = html,
        modifier = modifier,
        minHeight = 200,
        maxHeight = 500
    )
}

private fun buildEChartsHtml(optionJson: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <script src="echarts/echarts.min.js"></script>
        <style>
            body { margin: 0; padding: 0; background: transparent; }
            #chart { width: 100%; height: 350px; }
        </style>
    </head>
    <body>
        <div id="chart"></div>
        <script>
            try {
                var chart = echarts.init(document.getElementById('chart'), 'dark');
                var option = $optionJson;
                option.backgroundColor = 'transparent';
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

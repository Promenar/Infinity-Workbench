/**
 * ECharts HTML 模板
 *
 * 将 EChartsRenderer 中的内联 HTML 模板提取为独立函数，
 * 参数化生成，便于维护和测试。
 */

interface EChartsPreviewOptions {
    chartOption: any;
    isDark: boolean;
    localEchartsUri: string | null;
    cdnUrl: string;
    scriptTagWithFallback: (lib: any, localUri: string | null, cdn: string) => string;
}

export function renderEChartsPreviewHtml(opts: EChartsPreviewOptions): string {
    return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
      ${opts.scriptTagWithFallback('echarts', opts.localEchartsUri, opts.cdnUrl)}
      <style>
        body {
          margin: 0;
          padding: 0;
          background-color: ${opts.isDark ? '#000000' : '#ffffff'};
          height: 120px;
          overflow: hidden;
        }
        #chart-container {
          width: 100%;
          height: 120px;
        }
      </style>
    </head>
    <body>
      <div id="chart-container"></div>
      <script>
        const chartDom = document.getElementById('chart-container');
        const myChart = echarts.init(chartDom, '${opts.isDark ? 'dark' : 'light'}');
        const option = ${JSON.stringify(opts.chartOption)};
        
        if (option.title) {
             if (typeof option.title === 'object' && !Array.isArray(option.title)) {
                 option.title.show = false; 
             } else if (Array.isArray(option.title)) {
                 option.title.forEach(t => t.show = false);
             }
        }
        option.backgroundColor = 'transparent';
        option.silent = true;
        myChart.setOption(option);

        // 上报高度
        setTimeout(function() {
          var height = chartDom.scrollHeight || document.body.scrollHeight;
          if (window.ReactNativeWebView) {
            window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'height', value: height + 20 }));
          }
        }, 500);
      </script>
    </body>
    </html>`;
}

interface EChartsFullscreenOptions {
    chartOption: any;
    isDark: boolean;
    localEchartsUri: string | null;
    cdnUrl: string;
    scriptTagWithFallback: (lib: any, localUri: string | null, cdn: string) => string;
}

export function renderEChartsFullscreenHtml(opts: EChartsFullscreenOptions): string {
    return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
      ${opts.scriptTagWithFallback('echarts', opts.localEchartsUri, opts.cdnUrl)}
      <style>
        body {
          margin: 0;
          padding: 0;
          background-color: ${opts.isDark ? '#000000' : '#ffffff'};
          height: 100vh;
          display: flex;
          flex-direction: column;
        }
        #chart-container {
          flex: 1;
          width: 100%;
          min-height: 100%;
        }
      </style>
    </head>
    <body>
      <div id="chart-container"></div>
      <script>
        const chartDom = document.getElementById('chart-container');
        const myChart = echarts.init(chartDom, '${opts.isDark ? 'dark' : 'light'}');
        const option = ${JSON.stringify(opts.chartOption)};
        
        if (option.title) {
             if (typeof option.title === 'object' && !Array.isArray(option.title)) {
                 option.title.show = false; 
             } else if (Array.isArray(option.title)) {
                 option.title.forEach(t => t.show = false);
             }
        }

        // 全屏模式：添加完整工具箱
        if (!option.toolbox || typeof option.toolbox !== 'object' || Array.isArray(option.toolbox)) {
            option.toolbox = {};
        }
        option.toolbox.show = true;
        option.toolbox.top = 0;
        option.toolbox.right = 10;
        option.toolbox.feature = Object.assign({}, option.toolbox.feature || {}, {
            saveAsImage: { title: '保存图片', pixelRatio: 2 },
            dataView: { title: '数据视图', lang: ['数据视图', '关闭', '刷新'], readOnly: true },
            restore: { title: '还原' },
            dataZoom: { title: { zoom: '区域缩放', back: '还原缩放' } },
        });

        if (option.legend && typeof option.legend === 'object' && !Array.isArray(option.legend)) {
             option.legend.top = 60;
        }

        if (option.grid) {
            if (typeof option.grid === 'object' && !Array.isArray(option.grid)) {
                option.grid.top = option.grid.top || 130;
            } else if (Array.isArray(option.grid)) {
                option.grid.forEach(g => g.top = g.top || 130);
            }
        } else {
            option.grid = { top: 130, left: '10%', right: '10%', bottom: '12%', containLabel: true };
        }
        option.backgroundColor = 'transparent';
        myChart.setOption(option);
        window.addEventListener('resize', () => myChart.resize());
      </script>
    </body>
    </html>`;
}

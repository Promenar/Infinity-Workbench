/**
 * Mermaid HTML 模板
 *
 * 将 MermaidRenderer 中的内联 HTML 模板提取为独立函数，
 * 参数化生成，便于维护和测试。
 */

interface MermaidPreviewOptions {
    cleanContent: string;
    isDark: boolean;
    localMermaidUri: string | null;
    cdnUrl: string;
    scriptTagWithFallback: (lib: any, localUri: string | null, cdn: string) => string;
}

export function renderMermaidPreviewHtml(opts: MermaidPreviewOptions): string {
    return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
      ${opts.scriptTagWithFallback('mermaid', opts.localMermaidUri, opts.cdnUrl)}
      <style>
        body {
          margin: 0;
          padding: 10px;
          background-color: ${opts.isDark ? '#000000' : '#ffffff'};
          color: ${opts.isDark ? '#e4e4e7' : '#27272a'};
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          display: flex;
          justify-content: center;
          align-items: center;
          height: 120px;
          overflow: hidden;
          font-size: 12px;
        }
        #mermaid-container {
          width: 100%;
          display: flex;
          justify-content: center;
        }
      </style>
    </head>
    <body>
      <div id="mermaid-container" class="mermaid">
        ${opts.cleanContent}
      </div>
      <script>
        mermaid.initialize({
          startOnLoad: true,
          theme: '${opts.isDark ? 'dark' : 'default'}',
          securityLevel: 'strict',
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        });

        // 上报内容高度
        function sendHeight() {
          setTimeout(function() {
            var container = document.getElementById('mermaid-container');
            var height = container ? container.scrollHeight : document.body.scrollHeight;
            if (window.ReactNativeWebView) {
              window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'height', value: height + 40 }));
            }
          }, 500);
        }
        window.addEventListener('load', sendHeight);
        // 如果 mermaid 渲染慢，可以在渲染完成后再次上报
      </script>
    </body>
    </html>`;
}

interface MermaidFullscreenOptions {
    cleanContent: string;
    isDark: boolean;
    localMermaidUri: string | null;
    cdnUrl: string;
    scriptTagWithFallback: (lib: any, localUri: string | null, cdn: string) => string;
}

export function renderMermaidFullscreenHtml(opts: MermaidFullscreenOptions): string {
    return `
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
      ${opts.scriptTagWithFallback('mermaid', opts.localMermaidUri, opts.cdnUrl)}
      <style>
        body {
          margin: 0;
          padding: 20px;
          background-color: ${opts.isDark ? '#000000' : '#ffffff'};
          color: ${opts.isDark ? '#e4e4e7' : '#27272a'};
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          display: flex;
          justify-content: center;
          align-items: center;
          min-height: 100vh;
        }
        #mermaid-container {
          width: 100%;
          display: flex;
          justify-content: center;
        }
        /* 针对全屏模式的滚动条美化 */
        ::-webkit-scrollbar {
          width: 4px;
          height: 4px;
        }
        ::-webkit-scrollbar-thumb {
          background: ${opts.isDark ? '#3f3f46' : '#d4d4d8'};
          border-radius: 2px;
        }
        /* SVG 缩放/平移控制按钮 */
        .zoom-controls {
          position: fixed;
          bottom: 90px;
          right: 20px;
          display: flex;
          flex-direction: column;
          gap: 6px;
          z-index: 100;
        }
        .zoom-btn {
          width: 40px;
          height: 40px;
          border-radius: 20px;
          border: none;
          background: ${opts.isDark ? '#2c2c2e' : '#f3f4f6'};
          color: ${opts.isDark ? '#fff' : '#333'};
          font-size: 20px;
          font-weight: bold;
          display: flex;
          align-items: center;
          justify-content: center;
          cursor: pointer;
          box-shadow: 0 2px 8px rgba(0,0,0,0.15);
        }
        .zoom-btn:active { opacity: 0.7; }
      </style>
    </head>
    <body>
      <div id="mermaid-container" class="mermaid">
        ${opts.cleanContent}
      </div>
      <div class="zoom-controls">
        <button class="zoom-btn" onclick="zoomIn()" title="放大">+</button>
        <button class="zoom-btn" onclick="zoomOut()" title="缩小">−</button>
        <button class="zoom-btn" onclick="fitScreen()" title="适应屏幕">⊡</button>
      </div>
      <script>
        mermaid.initialize({
          startOnLoad: true,
          theme: '${opts.isDark ? 'dark' : 'default'}',
          securityLevel: 'loose',
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        });

        // SVG 缩放/平移逻辑
        var svgScale = 1;
        var svgX = 0, svgY = 0;
        var isDragging = false, dragStartX, dragStartY;

        function getSvg() {
          return document.querySelector('#mermaid-container svg');
        }

        function updateTransform() {
          var svg = getSvg();
          if (svg) svg.style.transform = 'translate(' + svgX + 'px,' + svgY + 'px) scale(' + svgScale + ')';
          svg.style.transformOrigin = 'center center';
          svg.style.transition = 'transform 0.2s ease';
        }

        function zoomIn() { svgScale = Math.min(svgScale + 0.2, 5); updateTransform(); }
        function zoomOut() { svgScale = Math.max(svgScale - 0.2, 0.2); updateTransform(); }
        function fitScreen() {
          svgScale = 1; svgX = 0; svgY = 0;
          updateTransform();
        }

        // Touch/Mouse drag support
        document.addEventListener('mousedown', function(e) { isDragging = true; dragStartX = e.clientX - svgX; dragStartY = e.clientY - svgY; });
        document.addEventListener('mousemove', function(e) { if (isDragging) { svgX = e.clientX - dragStartX; svgY = e.clientY - dragStartY; var svg = getSvg(); if (svg) { svg.style.transition = 'none'; svg.style.transform = 'translate(' + svgX + 'px,' + svgY + 'px) scale(' + svgScale + ')'; } } });
        document.addEventListener('mouseup', function() { isDragging = false; });
        // Mouse wheel zoom
        document.addEventListener('wheel', function(e) { e.preventDefault(); svgScale = e.deltaY < 0 ? Math.min(svgScale + 0.1, 5) : Math.max(svgScale - 0.1, 0.2); updateTransform(); }, { passive: false });
      </script>
    </body>
    </html>`;
}

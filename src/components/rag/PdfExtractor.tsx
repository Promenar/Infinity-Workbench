import React, { useRef, useImperativeHandle, forwardRef, useEffect } from 'react';
import { View } from 'react-native';
import { WebView } from 'react-native-webview';

export interface PdfExtractorRef {
    extractText: (base64: string) => Promise<string>;
}

/**
 * 隐藏的 WebView 组件，用于通过 PDF.js 解析 PDF 文本
 * 使用 CDN 加载 pdf.js (需联网)，作为原生解析的轻量级替代方案。
 */
export const PdfExtractor = forwardRef<PdfExtractorRef, {}>((props, ref) => {
    const webviewRef = useRef<WebView>(null);
    const pendingResolves = useRef<((value: string) => void) | null>(null);
    const pendingRejects = useRef<((reason: any) => void) | null>(null);

    // HTML 模板：注入 PDF.js
    const htmlContent = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js"></script>
  <script>
    // 配置 worker
    pdfjsLib.GlobalWorkerOptions.workerSrc = 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';
  </script>
</head>
<body>
  <div id="status">Ready</div>
  <script>
    function log(msg) {
        // console.log(msg); // Debug
    }

    // 监听来自 RN 的消息
    // 我们通过 evaluateJavaScript 调用函数更直接，但也可以用这种方式
    
    // 全局提取函数
    window.extractPdfText = async function(base64Data) {
      try {
        log('Starting PDF extraction...');
        // Atob 解码 Base64
        const binaryString = window.atob(base64Data);
        const bytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
          bytes[i] = binaryString.charCodeAt(i);
        }

        const loadingTask = pdfjsLib.getDocument({ data: bytes });
        const pdf = await loadingTask.promise;
        
        let fullText = '';
        log('PDF Loaded, pages: ' + pdf.numPages);

        for (let i = 1; i <= pdf.numPages; i++) {
          const page = await pdf.getPage(i);
          const textContent = await page.getTextContent();
          
          // 简单的文本拼接，不保留布局
          const pageText = textContent.items.map(item => item.str).join(' ');
          fullText += pageText + '\\n\\n';
        }

        // 发送结果回 RN
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'success', text: fullText }));
        
      } catch (e) {
        log('Error: ' + e.message);
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'error', message: e.message }));
      }
    }
  </script>
</body>
</html>
    `;

    // 暴露给父组件的方法
    useImperativeHandle(ref, () => ({
        extractText: (base64: string) => {
            return new Promise((resolve, reject) => {
                if (pendingResolves.current) {
                    reject(new Error('A parsing task is already in progress'));
                    return;
                }

                pendingResolves.current = resolve;
                pendingRejects.current = reject;

                // 调用 WebView 中的全局函数
                // 注意：Base64 可能很长，直接注入 JS 可能会受限。
                // 如果 Base64 太大卡死，可能需要分块传输，但 PDF.js 需要完整文件。
                // 另一种方式是 injectJavaScript。
                if (webviewRef.current) {
                    // 必须转义
                    webviewRef.current.injectJavaScript(`window.extractPdfText('${base64}'); true;`);
                } else {
                    reject(new Error('WebView not ready'));
                    pendingResolves.current = null;
                    pendingRejects.current = null;
                }
            });
        }
    }));

    const onMessage = (event: any) => {
        try {
            const data = JSON.parse(event.nativeEvent.data);
            if (data.type === 'success') {
                if (pendingResolves.current) {
                    pendingResolves.current(data.text);
                }
            } else if (data.type === 'error') {
                if (pendingRejects.current) {
                    pendingRejects.current(new Error(data.message));
                }
            }
        } catch (e) {
            if (pendingRejects.current) pendingRejects.current(e);
        } finally {
            pendingResolves.current = null;
            pendingRejects.current = null;
        }
    };

    return (
        <View style={{ height: 0, width: 0, overflow: 'hidden' }}>
            <WebView
                ref={webviewRef}
                source={{ html: htmlContent }}
                onMessage={onMessage}
                javaScriptEnabled={true}
                originWhitelist={['*']}
                // 允许本地内容访问
                allowFileAccess={true}
            />
        </View>
    );
});

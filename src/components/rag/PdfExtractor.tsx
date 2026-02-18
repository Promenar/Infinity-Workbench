import React, { useRef, useImperativeHandle, forwardRef } from 'react';
import { View } from 'react-native';
import { WebView } from 'react-native-webview';
import * as FileSystem from 'expo-file-system/legacy';

export interface PdfExtractorRef {
  extractText: (base64: string) => Promise<string>;
  extractTextFromUri: (uri: string) => Promise<string>;
}

const SIZE_THRESHOLD = 5 * 1024 * 1024;
const EXTRACTION_TIMEOUT = 60000;

let taskIdCounter = 0;

const generateTaskId = () => {
  taskIdCounter += 1;
  return `task_${Date.now()}_${taskIdCounter}`;
};

const htmlContent = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js"></script>
  <script>
    pdfjsLib.GlobalWorkerOptions.workerSrc = 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';
  </script>
</head>
<body>
  <script>
    window.extractPdfText = async function(taskId, base64Data) {
      try {
        const binaryString = window.atob(base64Data);
        const bytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
          bytes[i] = binaryString.charCodeAt(i);
        }
        await processPdf(taskId, { data: bytes });
      } catch (e) {
        sendResult(taskId, 'error', null, e.message);
      }
    };

    window.extractPdfFromUri = async function(taskId, fileUri) {
      try {
        await processPdf(taskId, { url: fileUri });
      } catch (e) {
        sendResult(taskId, 'error', null, e.message);
      }
    };

    async function processPdf(taskId, source) {
      const pdf = await pdfjsLib.getDocument(source).promise;
      let fullText = '';
      
      for (let i = 1; i <= pdf.numPages; i++) {
        const page = await pdf.getPage(i);
        const textContent = await page.getTextContent();
        fullText += textContent.items.map(item => item.str).join(' ') + '\\n\\n';
      }
      
      sendResult(taskId, 'success', fullText);
    }

    function sendResult(taskId, type, text, error) {
      window.ReactNativeWebView.postMessage(JSON.stringify({ 
        taskId, 
        type, 
        text, 
        error 
      }));
    }
  </script>
</body>
</html>
`;

export const PdfExtractor = forwardRef<PdfExtractorRef, {}>((props, ref) => {
  const webviewRef = useRef<WebView>(null);
  const pendingTasks = useRef<Map<string, { 
    resolve: (value: string) => void; 
    reject: (reason: any) => void;
    timer: ReturnType<typeof setTimeout>;
    tempFile?: string;
  }>>(new Map());

  const cleanupTask = (taskId: string) => {
    const task = pendingTasks.current.get(taskId);
    if (task) {
      clearTimeout(task.timer);
      if (task.tempFile) {
        FileSystem.deleteAsync(task.tempFile, { idempotent: true }).catch(() => {});
      }
      pendingTasks.current.delete(taskId);
    }
  };

  const createPromise = (taskId: string, tempFile?: string): Promise<string> => {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanupTask(taskId);
        reject(new Error('PDF extraction timeout'));
      }, EXTRACTION_TIMEOUT);

      pendingTasks.current.set(taskId, { resolve, reject, timer, tempFile });
    });
  };

  useImperativeHandle(ref, () => ({
    extractText: async (base64: string) => {
      const taskId = generateTaskId();
      
      if (base64.length > SIZE_THRESHOLD) {
        const tempFile = `${FileSystem.cacheDirectory}pdf_temp_${taskId}.pdf`;
        try {
          await FileSystem.writeAsStringAsync(tempFile, base64, {
            encoding: FileSystem.EncodingType.Base64,
          });
          
          webviewRef.current?.injectJavaScript(
            `window.extractPdfFromUri('${taskId}', '${tempFile}'); true;`
          );
          
          return createPromise(taskId, tempFile);
        } catch (e) {
          await FileSystem.deleteAsync(tempFile, { idempotent: true }).catch(() => {});
          throw e;
        }
      } else {
        webviewRef.current?.injectJavaScript(
          `window.extractPdfText('${taskId}', '${base64}'); true;`
        );
        return createPromise(taskId);
      }
    },
    
    extractTextFromUri: async (uri: string) => {
      const taskId = generateTaskId();
      webviewRef.current?.injectJavaScript(
        `window.extractPdfFromUri('${taskId}', '${uri}'); true;`
      );
      return createPromise(taskId);
    },
  }));

  const onMessage = (event: any) => {
    try {
      const { taskId, type, text, error } = JSON.parse(event.nativeEvent.data);
      const task = pendingTasks.current.get(taskId);
      
      if (!task) return;
      
      if (type === 'success') {
        task.resolve(text);
      } else {
        task.reject(new Error(error || 'Unknown PDF extraction error'));
      }
      
      cleanupTask(taskId);
    } catch (e) {
      console.error('[PdfExtractor] Message parse error:', e);
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
        allowFileAccess={true}
        allowFileAccessFromFileURLs={true}
        allowUniversalAccessFromFileURLs={true}
      />
    </View>
  );
});

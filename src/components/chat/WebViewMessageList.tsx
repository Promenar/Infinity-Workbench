import React, { useRef, useEffect, useCallback, useMemo, useState } from 'react';
import { WebView, WebViewMessageEvent } from 'react-native-webview';
import { Asset } from 'expo-asset';
import { Platform } from 'react-native';
import { readAsStringAsync } from 'expo-file-system/legacy';
import type { Message } from '../../types/chat';
import type { RNToWebMessage, WebToRNMessage, WebViewThemePayload } from '../../types/webview-bridge';
import { generatePalette } from '../../lib/color-utils';

// Metro 将 web-renderer 构建产物打包为 asset
const WEB_RENDERER_ASSET = require('../../../assets/web-renderer/web-renderer.bundle');

interface WebViewMessageListProps {
  messages: Message[];
  isDark: boolean;
  colors: Record<string, string>;
}

// 全局缓存，避免重复加载
let htmlCache: string | null = null;

/**
 * 加载 web-renderer 构建产物（单 HTML 文件）
 *
 * 使用 expo-asset 解析 Metro 打包的 .bundle 文件路径，
 * 再通过 expo-file-system 读取为字符串。
 */
async function loadWebRendererHTML(): Promise<string> {
  if (htmlCache) return htmlCache;

  try {
    const asset = Asset.fromModule(WEB_RENDERER_ASSET);

    // Android debug 模式下需要显式下载
    if (!asset.localUri || (Platform.OS === 'android' && !asset.downloaded)) {
      await asset.downloadAsync();
    }

    const uri = asset.localUri || asset.uri;
    if (!uri) throw new Error('无法解析 web-renderer asset URI');

    // 通过 FileSystem 读取文件内容（兼容 file:// 和 http:// URI）
    const html = await readAsStringAsync(uri);
    htmlCache = html;
    return html;
  } catch (e) {
    console.error('[WebViewMessageList] 加载 web-renderer 产物失败:', e);
    return getFallbackHTML();
  }
}

/**
 * Fallback：构建产物加载失败时的最小 HTML
 */
function getFallbackHTML(): string {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;color:#999;padding:20px;text-align:center}
</style>
</head>
<body>
<div>WebView Renderer 加载失败<br>请执行 scripts/build-web-renderer.sh 重新构建</div>
<script>
if(window.ReactNativeWebView)window.ReactNativeWebView.postMessage(JSON.stringify({type:'ERROR',message:'web-renderer asset load failed'}));
</script>
</body>
</html>`;
}

export const WebViewMessageList: React.FC<WebViewMessageListProps> = ({
  messages,
  isDark,
  colors,
}) => {
  const webViewRef = useRef<WebView>(null);
  const isReadyRef = useRef(false);
  const prevMessagesLengthRef = useRef(0);
  const [htmlContent, setHtmlContent] = useState<string>('');

  // 构建主题数据
  const themePayload = useMemo<WebViewThemePayload>(() => {
    const accentBase = (colors as any)[500] || '#6366f1';
    const palette = generatePalette(accentBase);
    return {
      isDark,
      accentColor: accentBase,
      palette: {
        50: palette[50], 100: palette[100], 200: palette[200], 300: palette[300],
        400: palette[400], 500: palette[500], 600: palette[600], 700: palette[700],
        800: palette[800], 900: palette[900],
        opacity10: palette.opacity10, opacity20: palette.opacity20, opacity30: palette.opacity30,
      },
    };
  }, [isDark, colors]);

  // 加载构建产物
  useEffect(() => {
    loadWebRendererHTML().then(setHtmlContent);
  }, []);

  // 将 Message 转换为 BridgeMessage（精简字段）
  const toBridgeMessages = useCallback((msgs: Message[]) => {
    return msgs.map(msg => ({
      id: msg.id,
      role: msg.role,
      content: msg.content,
      createdAt: msg.createdAt,
      status: msg.status,
      isError: msg.isError,
      errorMessage: msg.errorMessage,
      reasoning: msg.reasoning,
    }));
  }, []);

  // 发送消息到 WebView
  const postToWebView = useCallback((msg: RNToWebMessage) => {
    const json = JSON.stringify(msg);
    webViewRef.current?.injectJavaScript(
      `(function(){ try { window.postMessage(${json}, '*'); } catch(e){} })(); true;`
    );
  }, []);

  // 消息变化时推送增量更新
  useEffect(() => {
    if (!isReadyRef.current) return;

    const currentLength = messages.length;
    const prevLength = prevMessagesLengthRef.current;

    if (currentLength > prevLength && prevLength > 0) {
      messages.slice(prevLength).forEach(msg => {
        postToWebView({ type: 'APPEND_MESSAGE', payload: toBridgeMessages([msg])[0] });
      });
    } else if (currentLength > 0) {
      postToWebView({
        type: 'INIT',
        payload: { messages: toBridgeMessages(messages), theme: themePayload },
      });
    }

    prevMessagesLengthRef.current = currentLength;
  }, [messages]);

  // 主题变化时推送
  useEffect(() => {
    if (!isReadyRef.current) return;
    postToWebView({ type: 'THEME_CHANGE', payload: themePayload });
  }, [themePayload]);

  // 处理来自 WebView 的消息
  const onMessage = useCallback((event: WebViewMessageEvent) => {
    try {
      const data = JSON.parse(event.nativeEvent.data) as WebToRNMessage;
      if (data.type === 'READY') {
        isReadyRef.current = true;
        prevMessagesLengthRef.current = messages.length;
        postToWebView({
          type: 'INIT',
          payload: { messages: toBridgeMessages(messages), theme: themePayload },
        });
      } else if (data.type === 'ERROR') {
        console.error('[WebViewMessageList] WebView error:', data.message);
      }
    } catch {}
  }, [messages, themePayload, toBridgeMessages, postToWebView]);

  // HTML 未加载完成
  if (!htmlContent) {
    return (
      <WebView
        source={{ html: getFallbackHTML() }}
        style={{ flex: 1, backgroundColor: isDark ? '#0a0a0c' : '#ffffff' }}
      />
    );
  }

  return (
    <WebView
      ref={webViewRef}
      source={{ html: htmlContent }}
      style={{ flex: 1, backgroundColor: isDark ? '#0a0a0c' : '#ffffff' }}
      onMessage={onMessage}
      originWhitelist={['*']}
      javaScriptEnabled={true}
      domStorageEnabled={true}
      scrollEnabled={false}
      showsVerticalScrollIndicator={false}
      bounces={false}
      overScrollMode="never"
      nestedScrollEnabled={true}
      contentMode="mobile"
      allowsBackForwardNavigationGestures={false}
      cacheEnabled={true}
      cacheMode="LOAD_DEFAULT"
      allowFileAccess={true}
      allowUniversalAccessFromFileURLs={true}
      mixedContentMode="always"
    />
  );
};

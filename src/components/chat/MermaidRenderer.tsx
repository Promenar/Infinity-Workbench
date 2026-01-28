import React, { useState } from 'react';
import { View, StyleSheet, Dimensions, ActivityIndicator, TouchableOpacity, Text } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { Maximize2, X } from 'lucide-react-native';
import { Modal } from 'react-native';
import * as Haptics from 'expo-haptics';

interface MermaidRendererProps {
  content: string;
}

/**
 * Mermaid 图表渲染组件
 * 使用 WebView 加载 CDN 版本的 mermaid.js 进行渲染
 */
export const MermaidRenderer: React.FC<MermaidRendererProps> = ({ content }) => {
  const { isDark } = useTheme();
  const [height, setHeight] = useState(200);
  const [isFullscreen, setIsFullscreen] = useState(false);

  // 清洗内容：移除可能存在的 markdown 代码块标记
  const cleanContent = content
    .replace(/^```mermaid\n?/, '')
    .replace(/```$/, '')
    .trim();

  // 根据主题生成 HTML
  const generateHtml = (isFull = false) => `
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=${isFull ? '5.0' : '1.0'}, user-scalable=${isFull ? 'yes' : 'no'}">
      <script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.0/dist/mermaid.min.js"></script>
      <style>
        body {
          margin: 0;
          padding: 12px;
          background-color: ${isDark ? '#000000' : '#ffffff'};
          color: ${isDark ? '#e4e4e7' : '#27272a'};
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          display: flex;
          justify-content: center;
          align-items: center;
          min-height: 100vh;
        }
        .mermaid {
          width: 100%;
          display: flex;
          justify-content: center;
        }
      </style>
    </head>
    <body>
      <div class="mermaid">
        ${cleanContent}
      </div>
      <script>
        mermaid.initialize({
          startOnLoad: true,
          theme: '${isDark ? 'dark' : 'default'}',
          securityLevel: 'loose',
        });

        // 监听高度变化并发送给 RN
        const sendHeight = () => {
          const height = document.body.scrollHeight;
          window.ReactNativeWebView.postMessage(JSON.stringify({ height }));
        };

        // 渲染完成后稍微延迟发送高度
        setTimeout(sendHeight, 500);
        
        // 也可以使用 ResizeObserver
        const resizeObserver = new ResizeObserver(() => sendHeight());
        resizeObserver.observe(document.body);
      </script>
    </body>
    </html>
  `;

  return (
    <View style={[styles.container, { borderColor: isDark ? '#3f3f46' : '#e4e4e7' }]}>
      {/* 标题栏 */}
      <View style={[styles.header, { borderBottomColor: isDark ? '#3f3f46' : '#e4e4e7' }]}>
        <Text style={[styles.title, { color: isDark ? '#a1a1aa' : '#71717a' }]}>Mermaid 流程图</Text>
        <TouchableOpacity onPress={() => {
          Haptics.selectionAsync();
          setIsFullscreen(true);
        }}>
          <Maximize2 size={16} color={isDark ? '#a1a1aa' : '#71717a'} />
        </TouchableOpacity>
      </View>

      <WebView
        source={{ html: generateHtml(false) }}
        style={{ height, backgroundColor: 'transparent' }} // WebView 自身背景透明，内容背景由 HTML 控制
        scrollEnabled={false}
        javaScriptEnabled={true}
        onMessage={(event) => {
          try {
            const data = JSON.parse(event.nativeEvent.data);
            if (data.height && Math.abs(data.height - height) > 10) {
              setHeight(data.height + 20); // 加一点 padding
            }
          } catch (e) {}
        }}
        androidLayerType="software"
      />

      {/* 全屏模态框 */}
      <Modal visible={isFullscreen} animationType="slide" onRequestClose={() => setIsFullscreen(false)}>
        <View style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff' }}>
           <TouchableOpacity 
              style={[styles.closeButton, { backgroundColor: isDark ? '#27272a' : '#f4f4f5' }]} 
              onPress={() => setIsFullscreen(false)}
            >
              <X size={24} color={isDark ? '#fff' : '#000'} />
           </TouchableOpacity>
           <WebView
             source={{ html: generateHtml(true) }}
             style={{ flex: 1, backgroundColor: 'transparent' }}
             javaScriptEnabled={true}
           />
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    marginVertical: 12,
    borderWidth: 1,
    borderRadius: 12,
    overflow: 'hidden',
    width: '100%',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  title: {
    fontSize: 12,
    fontWeight: '600',
  },
  closeButton: {
    position: 'absolute',
    top: 40,
    right: 20,
    zIndex: 10,
    padding: 10,
    borderRadius: 20,
  },
});

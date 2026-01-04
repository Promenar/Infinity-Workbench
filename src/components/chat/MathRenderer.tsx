import React, { useState } from 'react';
import { View, StyleSheet, Dimensions, TouchableOpacity, Text, Platform } from 'react-native';
import { WebView } from 'react-native-webview';
import { SvgXml } from 'react-native-svg';
import { Play, Square } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { useTheme } from '../../theme/ThemeProvider';

interface MathRendererProps {
  content: string;
  isBlock?: boolean; // true for $$..$$, false for $...$
}

/**
 * LaTeX 数学公式渲染组件
 * 使用 KaTeX 通过 WebView 渲染
 */
export const MathRenderer: React.FC<MathRendererProps> = ({ content, isBlock = false }) => {
  const { isDark } = useTheme();

  const backgroundColor = isDark ? '#000000' : '#ffffff';
  const textColor = isDark ? '#e4e4e7' : '#27272a';

  // KaTeX CDN HTML
  const html = `
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"></script>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            background-color: ${backgroundColor};
            color: ${textColor};
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: ${isBlock ? '18px' : '16px'};
            line-height: 1.6;
            padding: ${isBlock ? '12px 0' : '2px 0'};
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: ${isBlock ? 'center' : 'flex-start'};
        }
        .katex {
            font-size: 1em !important;
        }
        .katex-display {
            margin: 0 !important;
            ${isBlock ? 'text-align: center;' : ''}
        }
        #math-container {
            ${isBlock ? 'width: 100%; text-align: center;' : 'display: inline-block;'}
        }
    </style>
</head>
<body>
    <div id="math-container"></div>
    <script>
        try {
            const container = document.getElementById('math-container');
            katex.render(${JSON.stringify(content)}, container, {
                throwOnError: false,
                displayMode: ${isBlock ? 'true' : 'false'},
                trust: true,
                strict: false,
                output: 'html'
            });
        } catch (error) {
            document.body.innerHTML = '<span style="color: #ef4444;">LaTeX Error: ' + error.message + '</span>';
        }
    </script>
</body>
</html>
    `;

  return (
    <View style={[styles.container, isBlock ? styles.blockContainer : styles.inlineContainer]}>
      <WebView
        source={{ html }}
        originWhitelist={['*']}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        startInLoadingState={true}
        style={[
          styles.webview,
          { backgroundColor },
          isBlock ? styles.blockWebview : styles.inlineWebview,
        ]}
        scrollEnabled={false}
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        androidLayerType="software"
        onError={(syntheticEvent) => {
          const { nativeEvent } = syntheticEvent;
          console.warn('LaTeX WebView error:', nativeEvent);
        }}
        injectedJavaScript={`
                    const height = document.body.scrollHeight;
                    window.ReactNativeWebView.postMessage(JSON.stringify({ height }));
                    true;
                `}
        onMessage={(event) => {
          // 可选：动态调整高度
        }}
      />
    </View>
  );
};

interface AnimatedSVGRendererProps {
  svgContent: string;
  width?: number | string;
  height?: number;
}

/**
 * SVG 动画渲染组件（支持 CSS 动画和 SMIL）
 * 使用 WebView 渲染，支持完整的 SVG 特性
 */
export const AnimatedSVGRenderer: React.FC<AnimatedSVGRendererProps> = ({
  svgContent,
  width = '100%',
  height = 300,
}) => {
  const { isDark } = useTheme();
  const backgroundColor = isDark ? '#000000' : '#ffffff';

  const html = `
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            background-color: ${backgroundColor};
            display: flex;
            align-items: center;
            justify-content: center;
            margin: 0;
            padding: 0;
            width: 100vw;
            height: 100vh;
            overflow: hidden;
        }
        svg {
            width: 100% !important;
            height: 100% !important;
            max-width: 100% !important;
            max-height: 100% !important;
            margin: auto;
            display: block;
        }
    </style>
</head>
<body>
    ${svgContent}
</body>
</html>
    `;

  return (
    <View style={[styles.svgContainer, { height }]}>
      <WebView
        source={{ html }}
        style={[styles.webview, { backgroundColor }]}
        scrollEnabled={false}
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        androidLayerType="software"
      />
    </View>
  );
};

interface InteractiveSVGRendererProps {
  svgContent: string;
  height?: number;
}

/**
 * 交互式 SVG 渲染器
 * 默认显示静态 SVG (SvgXml)，点击播放按钮后切换为 WebView 渲染动画
 */
export const InteractiveSVGRenderer: React.FC<InteractiveSVGRendererProps> = ({
  svgContent,
  height = 250,
}) => {
  const { isDark } = useTheme();
  const [isPlaying, setIsPlaying] = useState(false);

  const togglePlay = () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    setIsPlaying(!isPlaying);
  };

  return (
    <View style={[styles.svgContainer, { height, position: 'relative' }]}>
      {isPlaying ? (
        // 播放状态：WebView 渲染动画
        <View style={{ flex: 1 }}>
          <AnimatedSVGRenderer svgContent={svgContent} height={height} />
          {/* 停止按钮 */}
          <TouchableOpacity onPress={togglePlay} style={styles.controlButton} activeOpacity={0.8}>
            <Square size={16} color="white" fill="white" />
            <Text style={styles.controlText}>停止</Text>
          </TouchableOpacity>
        </View>
      ) : (
        // 静态状态：SvgXml 渲染预览
        <View
          style={{
            flex: 1,
            backgroundColor: isDark ? '#18181b' : '#f9fafb',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <SvgXml xml={svgContent} width="100%" height="100%" onError={() => {}} />
          {/* 播放按钮遮罩 */}
          <View style={styles.overlay}>
            <TouchableOpacity onPress={togglePlay} style={styles.playButton} activeOpacity={0.8}>
              <Play size={24} color="white" fill="white" style={{ marginLeft: 4 }} />
            </TouchableOpacity>
            <Text style={styles.overlayText}>点击播放动画</Text>
          </View>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    marginVertical: 4,
  },
  blockContainer: {
    marginVertical: 12,
    width: '100%',
  },
  inlineContainer: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  webview: {
    flex: 1,
  },
  blockWebview: {
    height: 80, // 默认高度，可根据内容调整
  },
  inlineWebview: {
    height: 30, // 行内公式高度
    width: Dimensions.get('window').width * 0.8,
  },
  svgContainer: {
    marginVertical: 12,
    width: '100%',
    borderRadius: 12,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: 'transparent', // 占位
  },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.1)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  playButton: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: 'rgba(0,0,0,0.6)',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  overlayText: {
    color: 'rgba(0,0,0,0.6)',
    fontSize: 12,
    fontWeight: '600',
    backgroundColor: 'rgba(255,255,255,0.8)',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
    overflow: 'hidden',
  },
  controlButton: {
    position: 'absolute',
    top: 8,
    right: 8,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 20,
    gap: 6,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  controlText: {
    color: 'white',
    fontSize: 12,
    fontWeight: '600',
  },
});

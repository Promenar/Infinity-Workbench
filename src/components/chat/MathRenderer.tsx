import React, { useState, useEffect, useRef } from 'react';
import { View, StyleSheet, Dimensions, TouchableOpacity, Text, Platform, Modal, ActivityIndicator } from 'react-native';
import { WebView } from 'react-native-webview';
import { SvgXml } from 'react-native-svg';
import { Play, Square, Eye, Maximize2, X, Minimize2 } from 'lucide-react-native';
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
  const [dimensions, setDimensions] = useState({ height: isBlock ? 80 : 30, width: isBlock ? '100%' : 100 });

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
    <View style={[styles.container, isBlock ? styles.blockContainer : styles.inlineContainer, !isBlock && { width: dimensions.width as number, height: dimensions.height }]}>
      <WebView
        source={{ html }}
        originWhitelist={['*']}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        startInLoadingState={false} // Avoid flashing loading spinner for small math
        style={[
          styles.webview,
          { backgroundColor },
          isBlock ? styles.blockWebview : { height: dimensions.height, width: dimensions.width as number },
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
                    setTimeout(() => {
                        const height = document.body.scrollHeight;
                        const width = document.body.scrollWidth;
                        window.ReactNativeWebView.postMessage(JSON.stringify({ height, width }));
                    }, 100);
                    true;
                `}
        onMessage={(event) => {
          try {
            const { height, width } = JSON.parse(event.nativeEvent.data);
            if (!isBlock) {
              const currentWidth = dimensions.width as number;
              if (Math.abs(width - currentWidth) > 5 || Math.abs(height - dimensions.height) > 5) {
                setDimensions({ height: height + 10, width: width + 10 }); // Add padding
              }
            } else {
              // For block math, update height if significantly different
              if (Math.abs(height - dimensions.height) > 10) {
                setDimensions({ ...dimensions, height: height + 24 });
              }
            }
          } catch (e) { }
        }}
      />
    </View>
  );
};

interface AnimatedSVGRendererProps {
  svgContent: string;
  width?: number | string;
  minHeight?: number;
  initialHeight?: number;
  onHeightChange?: (height: number) => void;
  enableZoom?: boolean;
}

/**
 * SVG 动画渲染组件（支持 CSS 动画和 SMIL）
 * 使用 WebView 渲染，支持完整的 SVG 特性
 * 支持自动高度检测
 */
export const AnimatedSVGRenderer: React.FC<AnimatedSVGRendererProps> = ({
  svgContent,
  width = '100%',
  minHeight = 200,
  initialHeight = 300,
  onHeightChange,
  enableZoom = false,
}) => {
  const { isDark } = useTheme();
  const backgroundColor = isDark ? '#000000' : '#ffffff';
  const [webViewHeight, setWebViewHeight] = useState(initialHeight);

  // 注入脚本以获取内容高度
  const injectedJS = `
    function postHeight() {
      const svg = document.querySelector('svg');
      let height = 0;
      if (svg) {
         // 尝试获取 viewBox 的高度比例
         if (svg.viewBox && svg.viewBox.baseVal) {
             const ratio = svg.viewBox.baseVal.height / svg.viewBox.baseVal.width;
             height = document.body.clientWidth * ratio;
         } else {
             height = svg.getBoundingClientRect().height;
         }
      } 
      // Fallback
      if(!height || height < 50) height = document.body.scrollHeight;
      
      window.ReactNativeWebView.postMessage(JSON.stringify({ height: height }));
    }
    // 监听加载和调整大小
    window.addEventListener('load', postHeight);
    window.addEventListener('resize', postHeight);
    setTimeout(postHeight, 500); // 延迟再次检查
    true;
  `;

  const html = `
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=${enableZoom ? 'yes' : 'no'}">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            background-color: ${backgroundColor};
            display: ${enableZoom ? 'block' : 'flex'};
            flex-direction: column;
            align-items: center;
            justify-content: center;
            margin: 0;
            padding: 0;
            width: ${enableZoom ? 'auto' : '100vw'}; 
            height: ${enableZoom ? 'auto' : '100%'}; /* 100% for flex centering */
            overflow: ${enableZoom ? 'auto' : 'hidden'}; /* Allow scrolling in both axes when zoomed */
        }
        svg {
            width: 100% !important;
            height: auto !important;
            min-width: ${enableZoom ? '100vw' : '0'}; /* Ensure it fills screen width at minimum */
            max-width: none !important; /* Allow growing */
            margin: 0 auto;
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
    <View style={[styles.svgContainer, { height: webViewHeight, borderColor: 'transparent', marginVertical: 0 }]}>
      <WebView
        source={{ html }}
        style={[styles.webview, { backgroundColor }]}
        scrollEnabled={enableZoom}
        showsVerticalScrollIndicator={enableZoom}
        showsHorizontalScrollIndicator={enableZoom}
        androidLayerType="software"
        javaScriptEnabled={true}
        domStorageEnabled={true}
        injectedJavaScript={injectedJS}
        onMessage={(event) => {
          try {
            const data = JSON.parse(event.nativeEvent.data);
            if (data.height) {
              const newHeight = Math.max(minHeight, Math.ceil(data.height) + 20); // Add padding
              if (Math.abs(newHeight - webViewHeight) > 10) {
                setWebViewHeight(newHeight);
                onHeightChange?.(newHeight);
              }
            }
          } catch (e) { }
        }}
        // 允许缩放
        scalesPageToFit={Platform.OS === 'android'}
      />
    </View>
  );
};

// 全屏 SVG 查看模态框
const SVGViewerModal: React.FC<{
  visible: boolean;
  svgContent: string;
  onClose: () => void;
}> = ({ visible, svgContent, onClose }) => {
  const { isDark } = useTheme();

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent={false}
      onRequestClose={onClose}
    >
      <View style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff' }}>
        <View style={{
          flexDirection: 'row',
          justifyContent: 'flex-end',
          padding: 16,
          paddingTop: Platform.OS === 'android' ? 40 : 16,
          backgroundColor: isDark ? '#18181b' : '#f4f4f5',
          borderBottomWidth: 1,
          borderBottomColor: isDark ? '#27272a' : '#e4e4e7',
        }}>
          <TouchableOpacity onPress={onClose} style={{ padding: 8 }}>
            <X size={28} color={isDark ? '#fff' : '#000'} />
          </TouchableOpacity>
        </View>
        <View style={{ flex: 1 }}>
          <AnimatedSVGRenderer
            svgContent={svgContent}
            initialHeight={Dimensions.get('window').height}
            enableZoom={true}
            minHeight={Dimensions.get('window').height * 0.8}
          />
        </View>
      </View>
    </Modal>
  );
};

interface LazySVGRendererProps {
  svgContent: string;
  isDark?: boolean;
}

/**
 * 懒加载 SVG 渲染器
 * 默认隐藏，点击眼睛显示，支持全屏
 */
export const LazySVGRenderer: React.FC<LazySVGRendererProps> = ({ svgContent, isDark }) => { // Removed isDark explicit prop usage for theme hook
  const { isDark: themeIsDark } = useTheme(); // Use hook for consistency
  const dark = isDark ?? themeIsDark;

  const [isVisible, setIsVisible] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);

  return (
    <View style={[styles.svgContainer, {
      borderColor: dark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)',
      backgroundColor: dark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.02)',
    }]}>
      {/* Toolbar */}
      <View style={{
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: 12,
        borderBottomWidth: isVisible ? 1 : 0,
        borderBottomColor: dark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)'
      }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{
            fontSize: 12,
            fontWeight: '600',
            color: dark ? '#a1a1aa' : '#71717a'
          }}>
            SVG 图表
          </Text>
        </View>

        <View style={{ flexDirection: 'row', gap: 12 }}>
          <TouchableOpacity
            onPress={() => {
              Haptics.selectionAsync();
              setIsVisible(!isVisible);
            }}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}
          >
            <Eye size={16} color={isVisible ? '#6366f1' : (dark ? '#a1a1aa' : '#71717a')} />
            <Text style={{
              fontSize: 12,
              color: isVisible ? '#6366f1' : (dark ? '#a1a1aa' : '#71717a')
            }}>
              {isVisible ? '隐藏' : '查看'}
            </Text>
          </TouchableOpacity>

          {isVisible && (
            <TouchableOpacity
              onPress={() => {
                Haptics.selectionAsync();
                setIsFullscreen(true);
              }}
            >
              <Maximize2 size={16} color={dark ? '#a1a1aa' : '#71717a'} />
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* Content Area */}
      {isVisible ? (
        <View style={{ minHeight: 100 }}>
          <AnimatedSVGRenderer svgContent={svgContent} />
        </View>
      ) : (
        <View style={{
          height: 60,
          alignItems: 'center',
          justifyContent: 'center',
          borderStyle: 'dashed',
        }}>
          <Text style={{ fontSize: 12, color: dark ? '#52525b' : '#a1a1aa' }}>
            点击上方“查看”按钮加载图表
          </Text>
        </View>
      )}

      {/* Fullscreen Modal */}
      <SVGViewerModal
        visible={isFullscreen}
        svgContent={svgContent}
        onClose={() => setIsFullscreen(false)}
      />
    </View>
  );
};

export const InteractiveSVGRenderer = LazySVGRenderer; // Re-export as InteractiveSVGRenderer for backward compatibility if needed, but we will update call sites.

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
    borderColor: 'transparent',
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

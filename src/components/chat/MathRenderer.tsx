import React, { useMemo } from 'react';
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

// 预估行内公式的宽度（基于内容长度）
const estimateInlineWidth = (content: string): number => {
  // 🔑 大幅增加系数以避免裁剪
  const baseWidth = content.length * 14; // 从 12 增加到 14
  // LaTeX 命令如 \frac, \sqrt 等占用更多空间
  const commandCount = (content.match(/\\[a-zA-Z]+/g) || []).length;
  const commandExtra = commandCount * 35; // 从 25 增加到 35
  // 上下标占用额外空间
  const superSubCount = (content.match(/[_^]/g) || []).length;
  const superSubExtra = superSubCount * 15; // 从 10 增加到 15
  // 花括号内的内容
  const braceCount = (content.match(/\{[^}]+\}/g) || []).length;
  const braceExtra = braceCount * 20;

  const total = baseWidth + commandExtra + superSubExtra + braceExtra;
  // 最小 60px，最大屏幕宽度的 95%
  return Math.min(Math.max(total, 60), Dimensions.get('window').width * 0.95);
};

// 预估行内公式高度
const estimateInlineHeight = (content: string): number => {
  // 基础高度
  let height = 30;
  // 如果有分数、根号、求和、积分等，增加高度
  if (content.includes('\\frac') || content.includes('\\sqrt') ||
    content.includes('\\sum') || content.includes('\\int') ||
    content.includes('\\lim') || content.includes('\\prod')) {
    height = 55;
  }
  // 如果有上下标，增加高度
  if (content.includes('_') || content.includes('^')) {
    height = Math.max(height, 36);
  }
  return height;
};


/**
 * LaTeX 数学公式渲染组件
 * 使用 KaTeX 通过 WebView 渲染
 * 
 * 🔑 关键设计决策：
 * - 行内公式使用预估的固定尺寸，彻底避免动态测量导致的抖动
 * - 块级公式允许有限的高度自适应
 */
/**
 * 全局尺寸缓存
 * key: content (formula string)
 * value: { width, height }
 */
const sizeCache = new Map<string, { width: number; height: number }>();

/**
 * LaTeX 数学公式渲染组件
 * 使用 KaTeX 通过 WebView 渲染
 * 
 * 🔑 关键设计决策 (v4.0 - Global Cache):
 * - 引入 sizeCache 防止布局抖动导致的无限重试
 * - 分离 layout size 和 visual visibility
 */
export const MathRenderer: React.FC<MathRendererProps> = React.memo(({ content, isBlock = false }) => {
  const { isDark } = useTheme();
  const webViewRef = React.useRef<WebView>(null);
  const [isWebViewReady, setIsWebViewReady] = React.useState(false);

  // 1. 尝试从缓存获取初始尺寸
  const cachedSize = sizeCache.get(content);
  const [size, setSize] = React.useState<{ width: number; height: number } | null>(cachedSize || null);

  const measuredRef = React.useRef(!!cachedSize);

  const backgroundColor = isDark ? '#000000' : '#ffffff';
  const textColor = isDark ? '#e4e4e7' : '#27272a';

  // 渲染函数：通过 injectJavaScript 调用
  const injectRender = React.useCallback((latex: string, block: boolean) => {
    if (!webViewRef.current || !isWebViewReady) return;

    const js = `
      if (window.updateMath) {
        window.updateMath(${JSON.stringify(latex)}, ${block});
      }
    `;
    webViewRef.current.injectJavaScript(js);
  }, [isWebViewReady]);

  React.useEffect(() => {
    // 内容变化时，如果缓存没有，重置状态并尝试注入渲染
    const newCached = sizeCache.get(content);
    if (newCached) {
      setSize(newCached);
      measuredRef.current = true;
    } else {
      measuredRef.current = false;
      setSize(null);
    }

    // 如果 WebView 已经准备好，立即尝试渲染新内容
    if (isWebViewReady) {
      injectRender(content, isBlock);
    }
  }, [content, isBlock, isWebViewReady, injectRender]);

  // 预估尺寸作为兜底
  const initialEstimate = useMemo(() => {
    if (isBlock) return { width: Dimensions.get('window').width - 32, height: 80 };
    return {
      width: Math.min(content.length * 12 + 20, Dimensions.get('window').width * 0.8),
      height: 30
    };
  }, [content, isBlock]);

  // 最终使用的布局尺寸
  const currentSize = size || initialEstimate;

  // 基础环境 HTML：只在主题变化时重载，不随内容变化
  const baseHtml = useMemo(() => `
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"></script>
    <style>
        body {
            margin: 0;
            padding: 0;
            background-color: ${backgroundColor};
            color: ${textColor};
            display: flex;
            justify-content: ${isBlock ? 'center' : 'flex-start'};
            align-items: center;
            overflow: hidden;
            height: 100vh;
        }
        .katex { font-size: 15px !important; }
        .katex-display { margin: 0 !important; }
        #math-container {
            display: inline-block;
            white-space: nowrap;
        }
    </style>
</head>
<body>
    <div id="math-container"></div>
    <script>
        window.updateMath = function(latex, block) {
            try {
                const container = document.getElementById('math-container');
                document.body.style.justifyContent = block ? 'center' : 'flex-start';
                
                katex.render(latex, container, {
                    throwOnError: false,
                    displayMode: block,
                    trust: true,
                    strict: false
                });
                
                // 测量并回传
                setTimeout(() => {
                    const rect = container.getBoundingClientRect();
                    const width = Math.ceil(rect.width + 4);
                    const height = Math.ceil(rect.height + 2);
                    window.ReactNativeWebView.postMessage(JSON.stringify({ 
                      type: 'size', 
                      content: latex,
                      width, 
                      height 
                    }));
                }, 10);
            } catch (e) {
                window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'error', message: e.message }));
            }
        };
        
        // 标记环境就绪
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'ready' }));
    </script>
</body>
</html>
  `, [backgroundColor, textColor, isBlock]); // 注意：isBlock 虽影响对齐，但尽量保持 baseHtml 稳定

  return (
    <View
      style={[
        isBlock ? styles.blockContainer : styles.inlineContainer,
        {
          width: isBlock ? '100%' : currentSize.width,
          height: currentSize.height,
          // 只有当有真实尺寸时才显示，避免预估尺寸导致的布局跳动
          opacity: size ? 1 : 0,
        }
      ]}
    >
      <WebView
        ref={webViewRef}
        source={{ html: baseHtml }}
        originWhitelist={['*']}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        scrollEnabled={false}
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        androidLayerType="hardware"
        style={{ backgroundColor: 'transparent' }}
        onMessage={(event) => {
          try {
            const data = JSON.parse(event.nativeEvent.data);

            if (data.type === 'ready') {
              setIsWebViewReady(true);
            } else if (data.type === 'size') {
              // 只有当内容匹配且尚未锁定尺寸时才更新
              if (data.content === content && !measuredRef.current) {
                sizeCache.set(content, { width: data.width, height: data.height });
                measuredRef.current = true;
                setSize({ width: data.width, height: data.height });
              }
            }
          } catch (e) { }
        }}
      />
    </View>
  );
});


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
  const [webViewHeight, setWebViewHeight] = React.useState(initialHeight);

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
export const LazySVGRenderer: React.FC<LazySVGRendererProps> = ({ svgContent, isDark }) => {
  const { isDark: themeIsDark, colors } = useTheme();
  const dark = isDark ?? themeIsDark;

  const [isVisible, setIsVisible] = React.useState(false);
  const [isFullscreen, setIsFullscreen] = React.useState(false);

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
            <Eye size={16} color={isVisible ? (colors?.[500] || '#6366f1') : (dark ? '#a1a1aa' : '#71717a')} />
            <Text style={{
              fontSize: 12,
              color: isVisible ? (colors?.[500] || '#6366f1') : (dark ? '#a1a1aa' : '#71717a')
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
            点击上方"查看"按钮加载图表
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

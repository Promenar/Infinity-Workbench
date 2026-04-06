import React, { useState, useEffect } from 'react';
import { View, StyleSheet, TouchableOpacity, Text, Modal, SafeAreaView, StatusBar, Platform, Dimensions, ActivityIndicator } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { Maximize2, X, Share2, Network } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import Svg, { Path, Rect, G } from 'react-native-svg';
import * as ScreenOrientation from 'expo-screen-orientation';
import { resolveLocalLibUri, scriptTagWithFallback } from '../../lib/webview-assets';

const PhoneRotateIcon = ({ size, color }: { size: number; color: string }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Path d="M3.5 12C3.5 7.30558 7.30558 3.5 12 3.5" stroke={color} strokeWidth="2" strokeLinecap="round" />
    <Path d="M20.5 12C20.5 16.6944 16.6944 20.5 12 20.5" stroke={color} strokeWidth="2" strokeLinecap="round" />
    <Path d="M12 3.5H15M12 3.5V6.5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    <Path d="M12 20.5H9M12 20.5V17.5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    <G transform="rotate(45, 12, 12)">
      <Rect x="8" y="5" width="8" height="14" rx="1.5" stroke={color} strokeWidth="2" />
      <Path d="M11 16H13" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
    </G>
  </Svg>
);

interface MermaidRendererProps {
  content: string;
}

/**
 * Mermaid 图表渲染组件
 * 支持懒加载卡片模式、全屏交互及物理横屏旋转
 */
export const MermaidRenderer: React.FC<MermaidRendererProps> = ({ content }) => {
  const { isDark, colors } = useTheme();
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isLandscape, setIsLandscape] = useState(false);
  const [loading, setLoading] = useState(true);
  const [localMermaidUri, setLocalMermaidUri] = useState<string | null>(null);

  // 预加载本地 mermaid 资源
  useEffect(() => {
    resolveLocalLibUri('mermaid').then(uri => setLocalMermaidUri(uri));
  }, []);

  // 清洗内容
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
      ${scriptTagWithFallback('mermaid', localMermaidUri, 'https://cdn.jsdelivr.net/npm/mermaid@10.9.0/dist/mermaid.min.js')}
      <style>
        body {
          margin: 0;
          padding: ${isFull ? '20px' : '10px'};
          background-color: ${isDark ? '#000000' : '#ffffff'};
          color: ${isDark ? '#e4e4e7' : '#27272a'};
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          display: flex;
          justify-content: center;
          align-items: center;
          ${isFull ? 'min-height: 100vh;' : 'height: 120px; overflow: hidden; font-size: 12px;'}
        }
        #mermaid-container {
          width: 100%;
          display: flex;
          justify-content: center;
        }
        ${isFull ? `/* 针对全屏模式的滚动条美化 */
        ::-webkit-scrollbar {
          width: 4px;
          height: 4px;
        }
        ::-webkit-scrollbar-thumb {
          background: ${isDark ? '#3f3f46' : '#d4d4d8'};
          border-radius: 2px;
        }` : ''}
      </style>
    </head>
    <body>
      <div id="mermaid-container" class="mermaid">
        ${cleanContent}
      </div>
      <script>
        mermaid.initialize({
          startOnLoad: true,
          theme: '${isDark ? 'dark' : 'default'}',
          securityLevel: '${isFull ? 'loose' : 'strict'}',
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        });
      </script>
    </body>
    </html>
  `;

  const toggleOrientation = async () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    const nextLandscape = !isLandscape;
    setIsLandscape(nextLandscape);

    if (nextLandscape) {
      await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE_LEFT);
    } else {
      await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP);
    }
  };

  const handleClose = async () => {
    await ScreenOrientation.unlockAsync();
    setIsFullscreen(false);
    setIsLandscape(false);
  };

  const accentColor = colors?.[500] || (isDark ? '#a78bfa' : '#7c3aed');

  return (
    <>
      <View style={[styles.cardWrapper, {
        backgroundColor: isDark ? '#1c1c1e' : '#f9fafb',
        borderColor: isDark ? '#2c2c2e' : '#e5e7eb'
      }]}>
        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            setLoading(true);
            setIsFullscreen(true);
          }}
          style={styles.card}
        >
          <View style={[styles.iconContainer, { backgroundColor: isDark ? '#2c2c2e' : (colors?.opacity20 || '#ede9fe') }]}>
            <Network size={22} color={accentColor} />
          </View>

          <View style={styles.contentContainer}>
            <Text style={[styles.cardTitle, { color: isDark ? '#f4f4f5' : '#111827' }]} numberOfLines={1}>
              Mermaid 流程图
            </Text>
            <View style={styles.badgeContainer}>
              <View style={[styles.badge, { backgroundColor: isDark ? '#334155' : (colors?.opacity30 || '#e2e8f0') }]}>
                <Text style={[styles.badgeText, { color: isDark ? '#cbd5e1' : (colors?.[500] || '#475569') }]}>
                  DIAGRAM
                </Text>
              </View>
              <Text style={[styles.hintText, { color: isDark ? '#71717a' : '#9ca3af' }]}>
                点击全屏交互
              </Text>
            </View>
          </View>

          <View style={styles.actionIcon}>
            <Maximize2 size={18} color={isDark ? '#52525b' : '#9ca3af'} />
          </View>
        </TouchableOpacity>

        <View style={{ height: 120, overflow: 'hidden', borderBottomLeftRadius: 16, borderBottomRightRadius: 16 }}>
          <WebView
            key="mermaid_preview"
            source={{ html: generateHtml(false) }}
            style={{ flex: 1, backgroundColor: 'transparent' }}
            javaScriptEnabled={true}
            androidLayerType="hardware"
            bounces={false}
            scrollEnabled={false}
          />
        </View>
      </View>

      <Modal
        visible={isFullscreen}
        animationType="fade"
        presentationStyle="fullScreen"
        onRequestClose={handleClose}
      >
        <SafeAreaView style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff', paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight || 0) : 0 }}>
          <View style={[styles.modalHeader, { borderBottomColor: isDark ? '#1c1c1e' : '#f3f4f6' }]}>
            <Text style={[styles.modalTitle, { color: isDark ? '#fff' : '#000' }]} numberOfLines={1}>
              Mermaid 流程图
            </Text>
            <TouchableOpacity
              style={[styles.closeIconButton, { backgroundColor: isDark ? '#1c1c1e' : '#f3f4f6' }]}
              onPress={handleClose}
            >
              <X size={20} color={isDark ? '#fff' : '#666'} />
            </TouchableOpacity>
          </View>

          <View style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff' }}>
            <WebView
              key={`mermaid_webview_full_${isLandscape}_${isFullscreen}`}
              source={{ html: generateHtml(true) }}
              style={{ flex: 1, backgroundColor: 'transparent' }}
              javaScriptEnabled={true}
              androidLayerType="hardware"
              bounces={true}
              onLoad={() => setLoading(false)}
            />
            {loading && (
              <View style={styles.loadingOverlay}>
                <ActivityIndicator size="large" color={accentColor} />
              </View>
            )}
          </View>

          {/* 🔄 Landscape Toggle FAB */}
          <TouchableOpacity
            style={[styles.fab, {
              backgroundColor: colors?.[500] || (isDark ? '#2c2c2e' : '#7c3aed'),
              shadowColor: colors?.[500] || '#000'
            }]}
            onPress={toggleOrientation}
          >
            <PhoneRotateIcon size={28} color="#fff" />
          </TouchableOpacity>
        </SafeAreaView>
      </Modal>
    </>
  );
};

const styles = StyleSheet.create({
  outerContainer: {
    width: '100%',
    marginVertical: 4,
  },
  cardWrapper: {
    borderRadius: 16,
    borderWidth: 1,
    width: '100%',
    alignSelf: 'center',
    overflow: 'hidden',
  },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 12,
  },
  iconContainer: {
    width: 44,
    height: 44,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  contentContainer: {
    flex: 1,
    justifyContent: 'center',
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: '700',
    marginBottom: 4,
  },
  badgeContainer: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  badge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
    marginRight: 8,
  },
  badgeText: {
    fontSize: 10,
    fontWeight: '800',
  },
  hintText: {
    fontSize: 11,
  },
  actionIcon: {
    padding: 4,
  },
  modalHeader: {
    height: 56,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    borderBottomWidth: 1,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '700',
    flex: 1,
    marginRight: 40,
  },
  closeIconButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    justifyContent: 'center',
    alignItems: 'center',
  },
  fab: {
    position: 'absolute',
    bottom: 24,
    right: 24,
    width: 56,
    height: 56,
    borderRadius: 28,
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 6,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.3,
    shadowRadius: 4.65,
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.3)',
  }
});

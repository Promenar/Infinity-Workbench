import React, { useState, useEffect, useCallback } from 'react';
import { View, StyleSheet, TouchableOpacity, Text, ActivityIndicator } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { Maximize2, Network, RefreshCw, Download, Copy } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { resolveLocalLibUri, scriptTagWithFallback } from '../../lib/webview-assets';
import { renderMermaidPreviewHtml, renderMermaidFullscreenHtml } from '../../lib/artifact-templates/mermaid-templates';
import { artifactColors } from '../../lib/artifact-theme';
import { PhoneRotateIcon } from './shared/PhoneRotateIcon';
import { FullscreenModal } from './shared/FullscreenModal';
import { useFullscreenOrientation } from './shared/useFullscreenOrientation';
import { ArtifactActionSheet, ActionSheetAction } from './shared/ArtifactActionSheet';

interface MermaidRendererProps {
  content: string;
}

/**
 * Mermaid 图表渲染组件
 * 支持懒加载卡片模式、全屏交互及物理横屏旋转
 */
export const MermaidRenderer: React.FC<MermaidRendererProps> = ({ content }) => {
  const { isDark, colors } = useTheme();
  const ac = artifactColors(isDark, colors);
  const { isFullscreen, isLandscape, enterFullscreen, toggleOrientation, exitFullscreen } = useFullscreenOrientation();
  const [loading, setLoading] = useState(true);
  const [localMermaidUri, setLocalMermaidUri] = useState<string | null>(null);
  const [previewHeight, setPreviewHeight] = useState(120);
  const [renderError, setRenderError] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [isExporting, setIsExporting] = useState(false);
  const [showContextMenu, setShowContextMenu] = useState(false);
  const MAX_RETRIES = 3;

  // 基于内容生成稳定的 key
  const contentHash = content.length ^ content.charCodeAt(0) ^ content.charCodeAt(Math.max(0, content.length - 1));

  // 预加载本地 mermaid 资源
  useEffect(() => {
    resolveLocalLibUri('mermaid').then(uri => setLocalMermaidUri(uri));
  }, []);

  // 清洗内容
  const cleanContent = content
    .replace(/^```mermaid\n?/, '')
    .replace(/```$/, '')
    .trim();

  // 检查 mermaid 内容有效性
  const isValidContent = cleanContent.length > 0 && /[a-zA-Z]/.test(cleanContent);

  // 重试处理
  const handleRetry = useCallback(() => {
    if (retryCount < MAX_RETRIES) {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      setRetryCount(prev => prev + 1);
      setRenderError(false);
    }
  }, [retryCount]);

  // 重置渲染错误状态当内容变化
  useEffect(() => {
    setRenderError(false);
    setRetryCount(0);
  }, [content]);

  // 根据主题生成 HTML（委托外部模板）
  const generateHtml = (isFull = false) => {
    const commonOpts = {
      cleanContent,
      isDark,
      localMermaidUri,
      cdnUrl: 'https://cdn.jsdelivr.net/npm/mermaid@10.9.0/dist/mermaid.min.js',
      scriptTagWithFallback,
    };
    return isFull
      ? renderMermaidFullscreenHtml(commonOpts)
      : renderMermaidPreviewHtml(commonOpts);
  };

  const handleClose = exitFullscreen;

  const accentColor = ac.accent;

  // 导出 Mermaid 为 HTML
  const handleExport = useCallback(async () => {
    if (isExporting || !isValidContent) return;
    setIsExporting(true);
    try {
      const exportHtml = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.0/dist/mermaid.min.js"></script>
<style>body{margin:0;padding:20px;display:flex;justify-content:center;align-items:center;min-height:100vh;background:${isDark ? '#000' : '#fff'};color:${isDark ? '#e4e4e7' : '#27272a'};font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}</style></head>
<body><div class="mermaid">${cleanContent}</div>
<script>mermaid.initialize({startOnLoad:true,theme:'${isDark ? 'dark' : 'default'}',securityLevel:'loose'});</script></body></html>`;

      const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      const fileName = `mermaid-${timestamp}.html`;
      const filePath = `${FileSystem.cacheDirectory}${fileName}`;

      await FileSystem.writeAsStringAsync(filePath, exportHtml, { encoding: FileSystem.EncodingType.UTF8 });

      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(filePath, {
          mimeType: 'text/html',
          dialogTitle: 'Mermaid 流程图',
          UTI: 'public.html',
        });
      }
    } catch (e: any) {
      console.warn('[MermaidRenderer] Export failed:', e?.message);
    } finally {
      setIsExporting(false);
    }
  }, [cleanContent, isDark, isExporting, isValidContent]);

  return (
    <>
      <View style={[styles.cardWrapper, {
        backgroundColor: ac.card.background,
        borderColor: ac.card.border
      }]}>
        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() => {
            setLoading(true);
            enterFullscreen();
          }}
          onLongPress={() => setShowContextMenu(true)}
          delayLongPress={400}
          style={styles.card}
          accessibilityRole="button"
          accessibilityLabel="流程图, 点击查看全屏"
          accessibilityHint="点击查看全屏流程图，长按打开菜单"
        >
          <View style={[styles.iconContainer, { backgroundColor: ac.icon.background }]}>
            <Network size={22} color={accentColor} />
          </View>

          <View style={styles.contentContainer}>
            <Text style={[styles.cardTitle, { color: ac.text.primary }]} numberOfLines={1}>
              Mermaid 流程图
            </Text>
            <View style={styles.badgeContainer}>
              <View style={[styles.badge, { backgroundColor: ac.badge.background }]}>
                <Text style={[styles.badgeText, { color: ac.badge.text }]}>
                  DIAGRAM
                </Text>
              </View>
              <Text style={[styles.hintText, { color: ac.text.hint }]}>
                点击全屏交互
              </Text>
            </View>
          </View>

          <View style={styles.actionIcon}>
            <Maximize2 size={18} color={ac.button.icon} />
          </View>
        </TouchableOpacity>

        <View style={{ height: loading ? 120 : previewHeight, overflow: 'hidden', borderBottomLeftRadius: 16, borderBottomRightRadius: 16 }}>
          <WebView
            key={`mermaid_preview_${retryCount}_${contentHash}`}
            source={{ html: generateHtml(false) }}
            style={{ flex: 1, backgroundColor: 'transparent' }}
            javaScriptEnabled={true}
            androidLayerType="hardware"
            bounces={false}
            scrollEnabled={false}
            onMessage={(event) => {
              try {
                const data = JSON.parse(event.nativeEvent.data);
                if (data.type === 'height' && !isFullscreen) {
                  const newHeight = Math.min(Math.max(data.value, 80), 240);
                  setPreviewHeight(newHeight);
                  setLoading(false);
                  setRenderError(false);
                } else if (data.type === 'error') {
                  console.warn('[Mermaid] Render error:', data.message);
                  setRenderError(true);
                }
              } catch (e) {
                console.warn('[Mermaid] Height update error:', e);
              }
            }}
            onError={() => {
              setRenderError(true);
            }}
          />
          {renderError && (
            <View style={[styles.loadingOverlay, { backgroundColor: ac.overlay.background }]}>
              <Text style={{ color: ac.text.errorMuted, fontSize: 13, textAlign: 'center' }}>
                流程图渲染失败
              </Text>
              {retryCount < MAX_RETRIES ? (
                <TouchableOpacity
                  onPress={handleRetry}
                  style={{
                    marginTop: 8,
                    flexDirection: 'row',
                    alignItems: 'center',
                    paddingHorizontal: 12,
                    paddingVertical: 6,
                    borderRadius: 8,
                    backgroundColor: ac.overlay.retryBackground,
                  }}
                  accessibilityRole="button"
                  accessibilityLabel="重试渲染流程图"
                  accessibilityHint="重新尝试渲染流程图"
                >
                  <RefreshCw size={14} color={ac.text.retryCount} />
                  <Text style={{ color: ac.text.retryCount, fontSize: 12, marginLeft: 4 }}>
                    重试 ({MAX_RETRIES - retryCount}/{MAX_RETRIES})
                  </Text>
                </TouchableOpacity>
              ) : (
                <Text style={{ color: ac.text.disabled, fontSize: 11, marginTop: 4 }}>
                  数据可能无效，请重新生成
                </Text>
              )}
            </View>
          )}
        </View>
      </View>

      <FullscreenModal
        visible={isFullscreen}
        title="Mermaid 流程图"
        isDark={isDark}
        isLandscape={isLandscape}
        accentColor={accentColor}
        onClose={handleClose}
        onToggleOrientation={toggleOrientation}
        headerRight={
          <TouchableOpacity
            style={[styles.closeIconButton, { backgroundColor: ac.button.background }]}
            onPress={handleExport}
            disabled={isExporting}
            accessibilityRole="button"
            accessibilityLabel="导出流程图"
            accessibilityHint="将流程图导出为HTML文件并分享"
          >
            <Download size={18} color={ac.button.closeIcon} />
          </TouchableOpacity>
        }
      >
        <View style={{ flex: 1, backgroundColor: ac.webview.background }}>
          <WebView
            key={`mermaid_full_${isLandscape}_${isFullscreen}_${contentHash}`}
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
      </FullscreenModal>

      <ArtifactActionSheet
        visible={showContextMenu}
        title="Mermaid 流程图"
        isDark={isDark}
        onClose={() => setShowContextMenu(false)}
        actions={[
          {
            key: 'fullscreen',
            label: '全屏查看',
            icon: <Maximize2 size={20} color={isDark ? '#f4f4f5' : '#111827'} />,
            onPress: () => { setLoading(true); enterFullscreen(); },
          },
          {
            key: 'export',
            label: '导出流程图',
            icon: <Download size={20} color={isDark ? '#f4f4f5' : '#111827'} />,
            onPress: handleExport,
          },
          {
            key: 'copy',
            label: '复制数据',
            icon: <Copy size={20} color={isDark ? '#f4f4f5' : '#111827'} />,
            onPress: () => {
              if (cleanContent) {
                const Clipboard = require('react-native').Clipboard;
                Clipboard.setString(cleanContent);
              }
            },
          },
        ] as ActionSheetAction[]}
      />
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

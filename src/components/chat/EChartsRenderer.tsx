import React, { useState, useEffect, useCallback } from 'react';
import { View, StyleSheet, TouchableOpacity, Text } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { Maximize2, BarChart3, PieChart, LineChart, Activity, RefreshCw, Download, Copy } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { resolveLocalLibUri, scriptTagWithFallback } from '../../lib/webview-assets';
import { renderEChartsPreviewHtml, renderEChartsFullscreenHtml } from '../../lib/artifact-templates/echarts-templates';
import { artifactColors } from '../../lib/artifact-theme';
import { PhoneRotateIcon } from './shared/PhoneRotateIcon';
import { FullscreenModal } from './shared/FullscreenModal';
import { useFullscreenOrientation } from './shared/useFullscreenOrientation';
import { ArtifactActionSheet, ActionSheetAction } from './shared/ArtifactActionSheet';

interface EChartsRendererProps {
    content: string; // JSON string configuration
}

/**
 * ECharts 图表渲染组件
 * 解析 JSON 配置并显示优质卡片，点击全屏渲染
 */
export const EChartsRenderer: React.FC<EChartsRendererProps> = ({ content }) => {
    const { isDark, colors } = useTheme();
    const ac = artifactColors(isDark, colors);
    const { isFullscreen, isLandscape, enterFullscreen, toggleOrientation, exitFullscreen } = useFullscreenOrientation();
    const [localEchartsUri, setLocalEchartsUri] = useState<string | null>(null);
    const [previewHeight, setPreviewHeight] = useState(120);
    const [loading, setLoading] = useState(true);
    const [retryCount, setRetryCount] = useState(0);
    const [isExporting, setIsExporting] = useState(false);
    const [showContextMenu, setShowContextMenu] = useState(false);
    const MAX_RETRIES = 3;

    // 基于内容生成稳定的 key（避免 title 变化导致 WebView 重建闪烁）
    const contentHash = content.length ^ content.charCodeAt(0) ^ content.charCodeAt(Math.max(0, content.length - 1));

    // 预加载本地 echarts 资源
    useEffect(() => {
      resolveLocalLibUri('echarts').then(uri => setLocalEchartsUri(uri));
    }, []);

    // Derived metadata
    let title = "ECharts Visualization";
    let chartType = "bar";
    let chartOption = null;
    let parseError = false;

    try {
        const cleanContent = content.trim();
        if (cleanContent) {
            // Because ContentSanitizer already ran jsonrepair on the original block,
            // we can try parsing directly. If it's still invalid, it's truly unparseable.
            try {
                chartOption = JSON.parse(cleanContent);
            } catch (innerError) {
                // Secondary check for common JS object literal issues not fixed by repair
                // (Though jsonrepair is very robust)
                console.warn('[EChartsRenderer] JSON.parse failed, content:', cleanContent);
                parseError = true;
            }

            if (chartOption) {
                if (chartOption.title?.text) title = chartOption.title.text;

                if (chartOption.series && Array.isArray(chartOption.series)) {
                    chartType = chartOption.series[0]?.type || 'bar';
                }
            }
        }
    } catch (e: any) {
        parseError = true;
        console.warn('[EChartsRenderer] Critical error during option extraction:', e?.message);
    }

    const generateHtml = (isFull = false) => {
        const commonOpts = {
            chartOption,
            isDark,
            localEchartsUri,
            cdnUrl: 'https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js',
            scriptTagWithFallback,
        };
        return isFull
            ? renderEChartsFullscreenHtml(commonOpts)
            : renderEChartsPreviewHtml(commonOpts);
    };

    if (!chartOption || parseError) {
        const canRetry = retryCount < MAX_RETRIES;
        const handleRetry = () => {
            if (canRetry) {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                setRetryCount(prev => prev + 1);
            }
        };

        return (
            <View style={[styles.errorContainer, { borderColor: ac.card.border }]}>
                <Text style={{ color: ac.text.errorMuted, fontSize: 13, textAlign: 'center' }}>
                    {parseError ? "图表配置解析失败" : (content.length > 0 ? "正在生成图表..." : "等待图表数据...")}
                </Text>
                {parseError && canRetry && (
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
                        accessibilityLabel="重试解析图表"
                        accessibilityHint="重新尝试解析图表配置"
                    >
                        <RefreshCw size={14} color={ac.text.retryCount} />
                        <Text style={{ color: ac.text.retryCount, fontSize: 12, marginLeft: 4 }}>
                            重试 ({MAX_RETRIES - retryCount}/{MAX_RETRIES})
                        </Text>
                    </TouchableOpacity>
                )}
                {parseError && !canRetry && (
                    <Text style={{ color: ac.text.disabled, fontSize: 11, marginTop: 4 }}>
                        数据可能无效，请重新生成
                    </Text>
                )}
            </View>
        );
    }

    const getIcon = () => {
        const iconSize = 22;
        const color = ac.accent;
        switch (chartType) {
            case 'pie': return <PieChart size={iconSize} color={color} />;
            case 'line': return <LineChart size={iconSize} color={color} />;
            case 'bar': return <BarChart3 size={iconSize} color={color} />;
            default: return <Activity size={iconSize} color={color} />;
        }
    };

    const toggleOrientationLocal = toggleOrientation;

    const handleExport = useCallback(async () => {
        if (isExporting || !chartOption) return;
        setIsExporting(true);
        try {
            // Generate a standalone HTML file with the chart
            const exportHtml = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<script src="https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js"></script>
<style>body{margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;background:${isDark ? '#000' : '#fff'}}
#chart{width:800px;height:600px}</style></head>
<body><div id="chart"></div>
<script>var c=echarts.init(document.getElementById('chart'),'${isDark ? 'dark' : 'light'}');
c.setOption(${JSON.stringify(chartOption)});</script></body></html>`;

            const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
            const fileName = `echarts-${timestamp}.html`;
            const filePath = `${FileSystem.cacheDirectory}${fileName}`;

            await FileSystem.writeAsStringAsync(filePath, exportHtml, { encoding: FileSystem.EncodingType.UTF8 });

            if (await Sharing.isAvailableAsync()) {
                await Sharing.shareAsync(filePath, {
                    mimeType: 'text/html',
                    dialogTitle: title || 'ECharts 图表',
                    UTI: 'public.html',
                });
            }
        } catch (e: any) {
            console.warn('[EChartsRenderer] Export failed:', e?.message);
        } finally {
            setIsExporting(false);
        }
    }, [chartOption, isDark, title, isExporting]);

    const handleClose = exitFullscreen;

    return (
        <View style={styles.outerContainer}>
            <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => enterFullscreen()}
                onLongPress={() => setShowContextMenu(true)}
                delayLongPress={400}
                style={[styles.card, {
                    backgroundColor: ac.card.background,
                    borderColor: ac.card.border
                }]}
                accessibilityRole="button"
                accessibilityLabel={`图表: ${title}, ${chartType}类型`}
                accessibilityHint="点击查看全屏图表，长按打开菜单"
            >
                <View style={styles.cardHeader}>
                    <View style={[styles.iconContainer, { backgroundColor: ac.icon.background }]}>
                        {getIcon()}
                    </View>

                    <View style={styles.contentContainer}>
                        <Text style={[styles.cardTitle, { color: ac.text.primary }]} numberOfLines={1}>
                            {title}
                        </Text>
                        <View style={styles.badgeContainer}>
                            <View style={[styles.badge, { backgroundColor: ac.badge.background }]}>
                                <Text style={[styles.badgeText, { color: ac.badge.text }]}>
                                    {chartType.toUpperCase()}
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
                </View>

                {/* WebView 缩略预览 */}
                <View style={{ height: loading ? 120 : previewHeight, overflow: 'hidden', borderBottomLeftRadius: 16, borderBottomRightRadius: 16 }}>
                    <WebView
                        key={`echarts_preview_${contentHash}`}
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
                                    setPreviewHeight(Math.min(Math.max(data.value, 80), 240));
                                    setLoading(false);
                                }
                            } catch {}
                        }}
                    />
                </View>
            </TouchableOpacity>

            <FullscreenModal
                visible={isFullscreen}
                title={title}
                isDark={isDark}
                isLandscape={isLandscape}
                accentColor={ac.accent}
                onClose={handleClose}
                onToggleOrientation={toggleOrientationLocal}
                headerRight={
                    <TouchableOpacity
                        style={[styles.closeIconButton, { backgroundColor: ac.button.background }]}
                        onPress={handleExport}
                        disabled={isExporting}
                        accessibilityRole="button"
                        accessibilityLabel="导出图表"
                        accessibilityHint="将图表导出为HTML文件并分享"
                    >
                        <Download size={18} color={ac.button.closeIcon} />
                    </TouchableOpacity>
                }
            >
                <View style={{ flex: 1, backgroundColor: ac.webview.background }}>
                    <WebView
                        key={`echarts_full_${isLandscape}_${isFullscreen}_${contentHash}`}
                        source={{ html: generateHtml(true) }}
                        style={{ flex: 1, backgroundColor: 'transparent' }}
                        javaScriptEnabled={true}
                        androidLayerType="hardware"
                        bounces={false}
                    />
                </View>
            </FullscreenModal>

            <ArtifactActionSheet
                visible={showContextMenu}
                title={title}
                isDark={isDark}
                onClose={() => setShowContextMenu(false)}
                actions={[
                    {
                        key: 'fullscreen',
                        label: '全屏查看',
                        icon: <Maximize2 size={20} color={isDark ? '#f4f4f5' : '#111827'} />,
                        onPress: () => enterFullscreen(),
                    },
                    {
                        key: 'export',
                        label: '导出图表',
                        icon: <Download size={20} color={isDark ? '#f4f4f5' : '#111827'} />,
                        onPress: handleExport,
                    },
                    {
                        key: 'copy',
                        label: '复制数据',
                        icon: <Copy size={20} color={isDark ? '#f4f4f5' : '#111827'} />,
                        onPress: () => {
                            if (chartOption) {
                                const Clipboard = require('react-native').Clipboard;
                                Clipboard.setString(JSON.stringify(chartOption, null, 2));
                            }
                        },
                    },
                ] as ActionSheetAction[]}
            />
        </View>
    );
};

const styles = StyleSheet.create({
    outerContainer: {
        width: '100%',
        marginVertical: 4,
    },
    card: {
        flexDirection: 'column',
        alignItems: 'stretch',
        paddingVertical: 8,
        paddingHorizontal: 12,
        borderRadius: 16,
        borderWidth: 1,
        width: '100%',
        alignSelf: 'center',
        overflow: 'hidden',
    },
    cardHeader: {
        flexDirection: 'row',
        alignItems: 'center',
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
    errorContainer: {
        padding: 16,
        borderWidth: 1,
        borderRadius: 12,
        alignItems: 'center',
        borderStyle: 'dashed',
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
    }
});

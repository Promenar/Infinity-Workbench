import React, { useState } from 'react';
import { View, StyleSheet, TouchableOpacity, Text, Modal, SafeAreaView, StatusBar, Platform, Dimensions } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { Maximize2, X, BarChart3, PieChart, LineChart, Activity } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import Svg, { Path, Rect, G } from 'react-native-svg';
import * as ScreenOrientation from 'expo-screen-orientation';

const PhoneRotateIcon = ({ size, color }: { size: number; color: string }) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
        {/* Curved arrows representing rotation */}
        <Path d="M3.5 12C3.5 7.30558 7.30558 3.5 12 3.5" stroke={color} strokeWidth="2" strokeLinecap="round" />
        <Path d="M20.5 12C20.5 16.6944 16.6944 20.5 12 20.5" stroke={color} strokeWidth="2" strokeLinecap="round" />
        <Path d="M12 3.5H15M12 3.5V6.5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        <Path d="M12 20.5H9M12 20.5V17.5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />

        {/* Smartphone shape at an angle */}
        <G transform="rotate(45, 12, 12)">
            <Rect x="8" y="5" width="8" height="14" rx="1.5" stroke={color} strokeWidth="2" />
            <Path d="M11 16H13" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
        </G>
    </Svg>
);

interface EChartsRendererProps {
    content: string; // JSON string configuration
}

/**
 * ECharts 图表渲染组件
 * 解析 JSON 配置并显示优质卡片，点击全屏渲染
 */
export const EChartsRenderer: React.FC<EChartsRendererProps> = ({ content }) => {
    const { isDark, colors } = useTheme();
    const [isFullscreen, setIsFullscreen] = useState(false);
    const [isLandscape, setIsLandscape] = useState(false);

    // Derived metadata
    let title = "ECharts Visualization";
    let chartType = "bar";
    let chartOption = null;
    let parseError = false;

    try {
        const cleanContent = content
            .replace(/^```echarts\n?/, '')
            .replace(/```$/, '')
            .trim();
        if (cleanContent) {
            const parseLoose = (str: string) => new Function('return ' + str)();
            chartOption = parseLoose(cleanContent);

            if (chartOption) {
                if (chartOption.title?.text) title = chartOption.title.text;

                if (chartOption.series && Array.isArray(chartOption.series)) {
                    chartType = chartOption.series[0]?.type || 'bar';
                }
            }
        }
    } catch (e) {
        parseError = true;
    }

    const generateHtml = (isFull = false) => `
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=${isFull ? '5.0' : '1.0'}, user-scalable=${isFull ? 'yes' : 'no'}">
      <script src="https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js"></script>
      <style>
        body {
          margin: 0;
          padding: 0;
          background-color: ${isDark ? '#000000' : '#ffffff'};
          height: 100vh;
          display: flex;
          flex-direction: column;
        }
        #chart-container {
          flex: 1;
          width: 100%;
          min-height: 100%;
        }
      </style>
    </head>
    <body>
      <div id="chart-container"></div>
      <script>
        const chartDom = document.getElementById('chart-container');
        const myChart = echarts.init(chartDom, '${isDark ? 'dark' : 'light'}');
        const option = ${JSON.stringify(chartOption)};
        
        if (option.title) {
             if (typeof option.title === 'object' && !Array.isArray(option.title)) {
                 option.title.show = false; 
             } else if (Array.isArray(option.title)) {
                 option.title.forEach(t => t.show = false);
             }
        }

        if (option.toolbox && typeof option.toolbox === 'object' && !Array.isArray(option.toolbox)) {
             option.toolbox.show = true;
             option.toolbox.top = 0; 
             option.toolbox.right = 10;
        }
        
        if (option.legend && typeof option.legend === 'object' && !Array.isArray(option.legend)) {
             option.legend.top = 60; // Deeper to avoid native title bubble
        }

        if (option.grid) {
            if (typeof option.grid === 'object' && !Array.isArray(option.grid)) {
                option.grid.top = option.grid.top || 130;
            } else if (Array.isArray(option.grid)) {
                option.grid.forEach(g => g.top = g.top || 130);
            }
        } else {
            option.grid = { top: 130, left: '10%', right: '10%', bottom: '12%', containLabel: true };
        }

        option.backgroundColor = 'transparent';
        myChart.setOption(option);
        window.addEventListener('resize', () => myChart.resize());
      </script>
    </body>
    </html>
  `;

    if (!chartOption || parseError) {
        return (
            <View style={[styles.errorContainer, { borderColor: isDark ? '#333' : '#e5e7eb' }]}>
                <Text style={{ color: isDark ? '#888' : '#666', fontSize: 13 }}>
                    {content.length > 20 ? "数据生成中..." : "加载中..."}
                </Text>
            </View>
        );
    }

    const getIcon = () => {
        const iconSize = 22;
        const color = colors?.[500] || (isDark ? '#a78bfa' : '#7c3aed');
        switch (chartType) {
            case 'pie': return <PieChart size={iconSize} color={color} />;
            case 'line': return <LineChart size={iconSize} color={color} />;
            case 'bar': return <BarChart3 size={iconSize} color={color} />;
            default: return <Activity size={iconSize} color={color} />;
        }
    };

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

    return (
        <View style={styles.outerContainer}>
            <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    setIsFullscreen(true);
                }}
                style={[styles.card, {
                    backgroundColor: isDark ? '#1c1c1e' : '#f9fafb',
                    borderColor: isDark ? '#2c2c2e' : '#e5e7eb'
                }]}
            >
                <View style={[styles.iconContainer, { backgroundColor: isDark ? '#2c2c2e' : (colors?.opacity20 || '#ede9fe') }]}>
                    {getIcon()}
                </View>

                <View style={styles.contentContainer}>
                    <Text style={[styles.cardTitle, { color: isDark ? '#f4f4f5' : '#111827' }]} numberOfLines={1}>
                        {title}
                    </Text>
                    <View style={styles.badgeContainer}>
                        <View style={[styles.badge, { backgroundColor: isDark ? '#334155' : (colors?.opacity30 || '#e2e8f0') }]}>
                            <Text style={[styles.badgeText, { color: isDark ? '#cbd5e1' : (colors?.[500] || '#475569') }]}>
                                {chartType.toUpperCase()}
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

            <Modal
                visible={isFullscreen}
                animationType="fade"
                presentationStyle="fullScreen"
                onRequestClose={handleClose}
            >
                <SafeAreaView style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff', paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight || 0) : 0 }}>
                    <View style={[styles.modalHeader, { borderBottomColor: isDark ? '#1c1c1e' : '#f3f4f6' }]}>
                        <Text style={[styles.modalTitle, { color: isDark ? '#fff' : '#000' }]} numberOfLines={1}>
                            {title}
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
                            key={`webview_${isLandscape}_${isFullscreen}`}
                            source={{ html: generateHtml(true) }}
                            style={{ flex: 1, backgroundColor: 'transparent' }}
                            javaScriptEnabled={true}
                            androidLayerType="hardware"
                            bounces={false}
                        />
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
        </View>
    );
};

const styles = StyleSheet.create({
    outerContainer: {
        width: '100%',
        marginVertical: 4,
    },
    card: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        borderRadius: 16,
        borderWidth: 1,
        width: '100%',
        alignSelf: 'center',
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

import React, { useState } from 'react';
import { View, StyleSheet, Dimensions, TouchableOpacity, Text, Modal } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { Maximize2, X, AlertCircle } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';

interface EChartsRendererProps {
    content: string; // JSON string configuration
}

/**
 * ECharts 图表渲染组件
 * 解析 JSON 配置并通过 WebView 使用 ECharts 渲染
 */
export const EChartsRenderer: React.FC<EChartsRendererProps> = ({ content }) => {
    const { isDark } = useTheme();
    const [title, setTitle] = useState("ECharts Visualization");
    const [chartType, setChartType] = useState<string>("bar");

    try {
        const cleanContent = content
            .replace(/^```echarts\n?/, '')
            .replace(/```$/, '')
            .trim();
        // 只有当有内容时才尝试解析
        if (cleanContent) {
            // 使用 new Function 来支持宽松的 JSON (JS Object) 解析，
            // 因为模型经常输出不带引号的键名 (如 { title: ... })
            // 注意：这是运行在 RN JS 线程，相对安全
            const parseLoose = (str: string) => new Function('return ' + str)();
            chartOption = parseLoose(cleanContent);

            // Extract Metadata for Card
            if (chartOption) {
                if (chartOption.title?.text) setTitle(chartOption.title.text);

                // Infer type
                if (chartOption.series && chartOption.series[0]?.type) {
                    setChartType(chartOption.series[0].type);
                } else if (chartOption.series && chartOption.series.length > 0) {
                    setChartType(chartOption.series[0].type || 'mixed');
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
          min-height: ${isFull ? '100%' : '300px'};
        }
      </style>
    </head>
    <body>
      <div id="chart-container"></div>
      <script>
        const chartDom = document.getElementById('chart-container');
        const myChart = echarts.init(chartDom, '${isDark ? 'dark' : 'light'}');
        const option = ${JSON.stringify(chartOption)};
        
        option.backgroundColor = 'transparent'; // 让背景透明以适配父容器

        myChart.setOption(option);

        // 监听窗口大小变化
        window.addEventListener('resize', () => {
          myChart.resize();
        });
      </script>
    </body>
    </html>
  `;

    // 如果解析失败或没有 Option，显示加载状态或错误
    if (!chartOption || parseError) {
        return (
            <View style={[styles.container, styles.errorContainer, { borderColor: isDark ? '#3f3f46' : '#e4e4e7', minHeight: 100 }]}>
                {/* 在流式传输过程中，JSON 解析失败是正常的，显示加载指示器 */}
                <Text style={[styles.errorDetail, { color: isDark ? '#a1a1aa' : '#71717a' }]}>
                    {content.length > 20 ? "正在生成图表数据..." : "..."}
                </Text>
            </View>
        );
    }

    // 🎨 Card UI Render (Lightweight Placeholder)
    const getIcon = () => {
        // Simple mapping, can be expanded
        return <Maximize2 size={24} color={isDark ? '#a1a1aa' : '#71717a'} />;
    };

    return (
        <View style={{ width: '100%', alignItems: 'flex-start', marginVertical: 8 }}>
            <TouchableOpacity
                activeOpacity={0.7}
                onPress={() => {
                    Haptics.selectionAsync();
                    setIsFullscreen(true);
                }}
                style={[styles.card, {
                    backgroundColor: isDark ? '#27272a' : '#fff',
                    borderColor: isDark ? '#3f3f46' : '#e4e4e7'
                }]}
            >
                {/* Left: Icon/Preview Placeholder */}
                <View style={[styles.iconBox, { backgroundColor: isDark ? '#3f3f46' : '#f4f4f5' }]}>
                    {getIcon()}
                </View>

                {/* Right: Info */}
                <View style={styles.infoBox}>
                    <Text style={[styles.cardTitle, { color: isDark ? '#fff' : '#18181b' }]} numberOfLines={1}>
                        {title}
                    </Text>
                    <Text style={[styles.cardSubtitle, { color: isDark ? '#a1a1aa' : '#71717a' }]}>
                        交互式 {chartType} 图表 • 点击查看详情
                    </Text>
                </View>

                {/* Arrow */}
                <Maximize2 size={16} color={isDark ? '#52525b' : '#d4d4d8'} style={{ marginLeft: 'auto', marginRight: 4 }} />
            </TouchableOpacity>

            {/* 全屏模态框 (Only mount heavy WebView here) */}
            <Modal visible={isFullscreen} animationType="slide" onRequestClose={() => setIsFullscreen(false)}>
                <View style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff' }}>
                    <View style={[styles.modalHeader, { borderBottomColor: isDark ? '#27272a' : '#e4e4e7' }]}>
                        <Text style={[styles.modalTitle, { color: isDark ? '#fff' : '#000' }]}>{title}</Text>
                        <TouchableOpacity
                            style={[styles.closeButton, { backgroundColor: isDark ? '#27272a' : '#f4f4f5' }]}
                            onPress={() => setIsFullscreen(false)}
                        >
                            <X size={20} color={isDark ? '#fff' : '#000'} />
                        </TouchableOpacity>
                    </View>

                    <WebView
                        source={{ html: generateHtml(true) }}
                        style={{ flex: 1, backgroundColor: 'transparent' }}
                        javaScriptEnabled={true}
                        androidLayerType="software"
                    />
                </View>
            </Modal>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
    },
    card: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        borderRadius: 12,
        borderWidth: 1,
        width: '100%', // Ensure typical message width logic applies or full width
        maxWidth: 320, // Limit width to look like a card attachment
        shadowColor: "#000",
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.05,
        shadowRadius: 2,
        elevation: 2,
    },
    iconBox: {
        width: 48,
        height: 48,
        borderRadius: 8,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
    },
    infoBox: {
        flex: 1,
        justifyContent: 'center',
    },
    cardTitle: {
        fontSize: 14,
        fontWeight: '600',
        marginBottom: 2,
    },
    cardSubtitle: {
        fontSize: 12,
    },
    modalHeader: {
        height: 60,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        borderBottomWidth: StyleSheet.hairlineWidth,
        paddingHorizontal: 16,
    },
    modalTitle: {
        fontSize: 16,
        fontWeight: '600',
    },
    closeButton: {
        position: 'absolute',
        right: 16,
        padding: 8,
        borderRadius: 20,
    },
    errorContainer: {
        padding: 20,
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        borderRadius: 12,
        borderWidth: 1,
    },
    errorText: {
        fontSize: 14,
        fontWeight: 'bold',
    },
    errorDetail: {
        fontSize: 12,
    }
});

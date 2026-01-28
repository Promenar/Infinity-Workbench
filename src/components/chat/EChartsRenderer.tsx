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
    const [height, setHeight] = useState(350); // 默认高度
    const [isFullscreen, setIsFullscreen] = useState(false);
    // 解析 JSON (每次渲染重新计算，不使用 state 以避免流式传输时的中间态锁定)
    let chartOption = null;
    let parseError = false;

    try {
        const cleanContent = content
            .replace(/^```echarts\n?/, '')
            .replace(/```$/, '')
            .trim();
        // 只有当有内容时才尝试解析
        if (cleanContent) {
            chartOption = JSON.parse(cleanContent);
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

    return (
        <View style={[styles.container, { borderColor: isDark ? '#3f3f46' : '#e4e4e7' }]}>
            {/* 标题栏 */}
            <View style={[styles.header, { borderBottomColor: isDark ? '#3f3f46' : '#e4e4e7' }]}>
                <Text style={[styles.title, { color: isDark ? '#a1a1aa' : '#71717a' }]}>ECharts 数据图表</Text>
                <TouchableOpacity onPress={() => {
                    Haptics.selectionAsync();
                    setIsFullscreen(true);
                }}>
                    <Maximize2 size={16} color={isDark ? '#a1a1aa' : '#71717a'} />
                </TouchableOpacity>
            </View>

            <WebView
                source={{ html: generateHtml(false) }}
                style={{ height, backgroundColor: 'transparent' }}
                scrollEnabled={false} // 禁止 WebView 自身滚动，专注于手势交互
                javaScriptEnabled={true}
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
    errorContainer: {
        padding: 20,
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
    },
    errorText: {
        fontSize: 14,
        fontWeight: 'bold',
    },
    errorDetail: {
        fontSize: 12,
    }
});

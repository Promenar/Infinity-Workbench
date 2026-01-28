import React from 'react';
import { View, StyleSheet, Platform } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { EChartsRenderer } from '../../../components/chat/EChartsRenderer';
import { MermaidRenderer } from '../../../components/chat/MermaidRenderer';
import { Typography } from '../../../components/ui';
import { Wrench } from 'lucide-react-native';

interface ToolArtifactsProps {
    artifacts?: { type: 'echarts' | 'mermaid' | 'math' | 'image' | 'text'; content: string; name?: string }[];
}

/**
 * ToolArtifacts - 专用工具执行产物渲染组件
 * 
 * 按照用户要求，将图表等产物从正文中剥离，
 * 在消息气泡中以独立组件形式呈现，避免正文布局畸形。
 */
export const ToolArtifacts: React.FC<ToolArtifactsProps> = ({ artifacts }) => {
    const { isDark, colors } = useTheme();

    if (!artifacts || artifacts.length === 0) return null;

    return (
        <View style={styles.container}>
            {artifacts.map((artifact, index) => {
                const key = `artifact-${index}`;

                // 渲染图表
                if (artifact.type === 'echarts') {
                    // 提取 JSON 配置
                    const configMatch = artifact.content.match(/```echarts\n([\s\S]*?)\n```/);
                    const configStr = configMatch ? configMatch[1] : '';

                    return (
                        <View key={key} style={styles.artifactWrapper}>
                            <View style={styles.badge}>
                                <Wrench size={10} color={colors?.[500] || '#6366f1'} />
                                <Typography className="text-[9px] font-bold uppercase ml-1" style={{ color: colors?.[500] || '#6366f1' }}>
                                    ECharts Result
                                </Typography>
                            </View>
                            <EChartsRenderer content={configStr} />
                        </View>
                    );
                }

                // 渲染 Mermaid (预留)
                if (artifact.type === 'mermaid') {
                    const mermaidCode = artifact.content.replace(/```mermaid\n?([\s\S]*?)```/, '$1').trim();
                    return (
                        <View key={key} style={styles.artifactWrapper}>
                            <MermaidRenderer content={mermaidCode} />
                        </View>
                    );
                }

                return null;
            })}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        gap: 12,
        marginVertical: 8,
    },
    artifactWrapper: {
        borderRadius: 16,
        overflow: 'hidden',
        borderWidth: 1,
        borderColor: Platform.OS === 'ios' ? 'rgba(0,0,0,0.05)' : 'rgba(0,0,0,0.08)',
        backgroundColor: 'rgba(0,0,0,0.02)',
    },
    badge: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 10,
        paddingVertical: 4,
        backgroundColor: 'rgba(99, 102, 241, 0.05)',
        alignSelf: 'flex-start',
        borderBottomRightRadius: 10,
    }
});

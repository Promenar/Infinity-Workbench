import React from 'react';
import { View, StyleSheet, Platform } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { EChartsRenderer } from '../../../components/chat/EChartsRenderer';
import { MermaidRenderer } from '../../../components/chat/MermaidRenderer';
import { Typography } from '../../../components/ui';
import { BarChart3, Network } from 'lucide-react-native';
import { ToolResultArtifact } from '../../../types/chat';

interface ToolArtifactsProps {
    artifacts?: ToolResultArtifact[];
}

/**
 * ToolArtifacts - 专用工具执行产物渲染组件
 * 
 * 按照用户要求，将图表等产物从正文中剥离，
 * 在消息气泡中以独立组件形式呈现，避免正文布局畸形。
 */
export const ToolArtifacts: React.FC<ToolArtifactsProps> = ({ artifacts }) => {
    const { isDark, colors } = useTheme();

    // 动态样式：暗色模式适配
    const artifactWrapperStyle = {
        borderRadius: 16,
        overflow: 'hidden' as const,
        borderWidth: 1,
        borderColor: isDark ? 'rgba(255,255,255,0.08)' : (Platform.OS === 'ios' ? 'rgba(0,0,0,0.05)' : 'rgba(0,0,0,0.08)'),
        backgroundColor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.02)',
    };

    const badgeStyle = {
        flexDirection: 'row' as const,
        alignItems: 'center' as const,
        paddingHorizontal: 10,
        paddingVertical: 4,
        backgroundColor: isDark ? 'rgba(99, 102, 241, 0.1)' : 'rgba(99, 102, 241, 0.05)',
        alignSelf: 'flex-start' as const,
        borderBottomRightRadius: 10,
    };

    if (!artifacts || artifacts.length === 0) return null;

    return (
        <View style={styles.container}>
            {artifacts.map((artifact, index) => {
                const key = `artifact-${artifact.type}-${artifact.name || index}`;

                // 渲染图表
                if (artifact.type === 'echarts') {
                    // 提取 JSON 配置
                    const configMatch = artifact.content.match(/```echarts\s*\n([\s\S]*?)\n?\s*```/);
                    const configStr = configMatch ? configMatch[1] : '';

                    return (
                        <View key={key} style={artifactWrapperStyle}>
                            <View style={badgeStyle}>
                                <BarChart3 size={10} color={colors?.[500] || '#6366f1'} />
                                <Typography className="text-[9px] font-bold uppercase ml-1" style={{ color: colors?.[500] || '#6366f1' }}>
                                    Chart
                                </Typography>
                            </View>
                            <EChartsRenderer content={configStr} />
                        </View>
                    );
                }

                // 渲染 Mermaid (预留)
                if (artifact.type === 'mermaid') {
                    const mermaidCode = artifact.content.replace(/```mermaid\s*\n?([\s\S]*?)\n?\s*```/, '$1').trim();
                    return (
                        <View key={key} style={artifactWrapperStyle}>
                            <View style={badgeStyle}>
                                <Network size={10} color={colors?.[500] || '#6366f1'} />
                                <Typography className="text-[9px] font-bold uppercase ml-1" style={{ color: colors?.[500] || '#6366f1' }}>
                                    Diagram
                                </Typography>
                            </View>
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
});

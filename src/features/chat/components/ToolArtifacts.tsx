import React from 'react';
import { View, StyleSheet, Platform } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { Typography } from '../../../components/ui';
import { ToolResultArtifact } from '../../../types/chat';
import { RendererRegistry } from '../../../components/chat/renderers';

// 确保渲染器已注册（副作用导入）
import '../../../components/chat/renderers';

interface ToolArtifactsProps {
    artifacts?: ToolResultArtifact[];
}

/**
 * ToolArtifacts - 专用工具执行产物渲染组件
 *
 * 按照用户要求，将图表等产物从正文中剥离，
 * 在消息气泡中以独立组件形式呈现，避免正文布局畸形。
 *
 * 使用 RendererRegistry 注册表模式分发渲染：
 * - 支持的 artifact 类型通过注册表查找对应渲染器配置
 * - 每种渲染器独立管理解析、元数据和渲染逻辑
 * - 新增渲染器类型只需实现 ArtifactRendererConfig 并注册
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

                // 通过注册表查找渲染器配置
                const rendererConfig = RendererRegistry.get(artifact.type);
                if (!rendererConfig) return null;

                // 使用渲染器配置解析内容
                const parsed = rendererConfig.parseContent(artifact.content);
                const contentStr = parsed.data
                    ? (typeof parsed.data === 'string' ? parsed.data : JSON.stringify(parsed.data))
                    : parsed.raw;

                return (
                    <View key={key} style={artifactWrapperStyle}>
                        <View style={badgeStyle}>
                            {rendererConfig.renderBadgeIcon(10, colors?.[500] || '#6366f1')}
                            <Typography className="text-[9px] font-bold uppercase ml-1" style={{ color: colors?.[500] || '#6366f1' }}>
                                {rendererConfig.badgeLabel}
                            </Typography>
                        </View>
                        {rendererConfig.renderContent({
                            content: contentStr || artifact.content,
                            colors,
                            isDark,
                        })}
                    </View>
                );
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

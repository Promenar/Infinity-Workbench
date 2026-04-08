/**
 * ChatInputTopBar Component
 * 独立的TopBar组件，管理模型选择器和工作区按钮
 * 
 * 职责:
 * - 渲染模型选择器按钮
 * - 渲染上下文Token统计（当前上下文/上限）
 * - 渲染工作区按钮
 * - 管理SessionSettingsSheet和WorkspaceSheet的显示状态
 * 
 * 注意: 此组件独立于ChatInput，保持ChatInput的轻量
 */
import React, { useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Cpu, Calculator, FolderOpen, Settings } from 'lucide-react-native';

import * as Haptics from '../../../../lib/haptics';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui';
import { ThinkingLevelButton } from './ThinkingLevelButton';
import { getTopBarStyles } from './ChatInputTopBar.styles';
import { useTopBarSheets } from './hooks/useTopBarSheets';
import { useContextTokens } from '../../hooks/useContextTokens';

// Sheet组件懒加载导入
import { SessionSettingsSheet } from '../SessionSettingsSheet';
import { WorkspaceSheet } from '../WorkspaceSheet';

export interface ChatInputTopBarProps {
    sessionId: string;
    /** 当前模型显示名称 */
    currentModel?: string;
    /** 模型按钮点击回调（可选，如果提供则调用父组件逻辑，否则打开设置Sheet） */
    onModelPress?: () => void;
    /** Token按钮点击回调（可选） */
    onTokenPress?: () => void;
    /** Agent颜色 */
    agentColor?: string;
    /** 当前活跃模型ID */
    activeModelId?: string;
}

/**
 * ChatInputTopBar - 输入框顶部的工具栏组件
 * 
 * 使用示例:
 * ```tsx
 * <ChatInputTopBar
 *   sessionId={sessionId}
 *   currentModel="GPT-4"
 *   agentColor="#6366f1"
 * />
 * ```
 */
export const ChatInputTopBar: React.FC<ChatInputTopBarProps> = ({
    sessionId,
    currentModel,
    onModelPress,
    onTokenPress,
    agentColor = '#6366f1',
    activeModelId,
}) => {
    const { isDark, colors } = useTheme();
    const styles = useMemo(() => getTopBarStyles(isDark, colors), [isDark, colors]);

    // 获取上下文Token使用情况
    const contextInfo = useContextTokens(sessionId);

    // Sheet状态管理
    const {
        showSettingsSheet,
        showWorkspaceSheet,
        openSettings,
        closeSettings,
        toggleSettings,
        openWorkspace,
        closeWorkspace,
    } = useTopBarSheets();

    // 模型按钮点击处理
    const handleModelPress = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        if (onModelPress) {
            // 如果提供了外部回调，使用外部回调
            onModelPress();
        } else {
            // 否则打开设置Sheet
            openSettings();
        }
    };

    // Token按钮点击处理
    const handleTokenPress = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        if (onTokenPress) {
            onTokenPress();
        } else {
            // 默认行为：打开设置Sheet到统计页
            openSettings();
        }
    };

    // 工作区按钮点击处理
    const handleWorkspacePress = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        openWorkspace();
    };


    return (
        <>
            <View style={styles.topBar}>
                {/* 模型选择器 */}
                {currentModel && (
                    <TouchableOpacity onPress={handleModelPress} activeOpacity={0.6} style={styles.modelBar}>
                        <Cpu size={10} color={agentColor} />
                        <Typography numberOfLines={1} style={styles.topBarText}>{currentModel}</Typography>
                    </TouchableOpacity>
                )}

                {/* 上下文Token统计 - 显示当前上下文/上限 */}
                <TouchableOpacity onPress={handleTokenPress} activeOpacity={0.6} style={styles.tokenBar}>
                    <Calculator size={10} color={contextInfo.color} />
                    <Typography style={[styles.topBarText, { color: contextInfo.color }]}>
                        {contextInfo.display}
                    </Typography>
                    {/* 进度条 */}
                    <View style={styles.contextProgressBar}>
                        <View
                            style={[
                                styles.contextProgressFill,
                                {
                                    width: `${Math.min(100, contextInfo.usagePercent)}%`,
                                    backgroundColor: contextInfo.color,
                                }
                            ]}
                        />
                    </View>
                </TouchableOpacity>

                {/* 弹性空间 */}
                <View style={styles.spacer} />

                {/* 右侧按钮组 */}
                <View style={styles.modeSelectors}>
                    {/* 思考级别按钮 */}
                    <ThinkingLevelButton
                        sessionId={sessionId}
                        isDark={isDark}
                        activeModelId={activeModelId}
                        displayName={currentModel}
                    />

                    {/* 工作区按钮 */}
                    <TouchableOpacity
                        onPress={handleWorkspacePress}
                        activeOpacity={0.6}
                        style={styles.workspaceButton}
                    >
                        <FolderOpen size={12} color={isDark ? '#a1a1aa' : '#71717a'} />
                        <Typography style={styles.workspaceButtonText}>工作区</Typography>
                    </TouchableOpacity>

                </View>
            </View>

            {/* SessionSettingsSheet */}
            <SessionSettingsSheet
                visible={showSettingsSheet}
                onClose={closeSettings}
                sessionId={sessionId}
            />

            {/* WorkspaceSheet */}
            <WorkspaceSheet
                visible={showWorkspaceSheet}
                onClose={closeWorkspace}
                sessionId={sessionId}
            />
        </>
    );
};

export default ChatInputTopBar;

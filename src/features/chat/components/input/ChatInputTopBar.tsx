/**
 * ChatInputTopBar Component
 * 独立的TopBar组件，管理模型选择器和工作区按钮
 * 
 * 职责:
 * - 渲染模型选择器按钮
 * - 渲染Token统计
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
import { formatTokenCount } from '../../utils/token-counter';
import { ThinkingLevelButton } from './ThinkingLevelButton';
import { getTopBarStyles } from './ChatInputTopBar.styles';
import { useTopBarSheets } from './hooks/useTopBarSheets';

// Sheet组件懒加载导入
import { SessionSettingsSheet } from '../SessionSettingsSheet';
import { WorkspaceSheet } from '../WorkspaceSheet';

export interface ChatInputTopBarProps {
    sessionId: string;
    /** 当前模型显示名称 */
    currentModel?: string;
    /** 模型按钮点击回调（可选，如果提供则调用父组件逻辑，否则打开设置Sheet） */
    onModelPress?: () => void;
    /** Token使用量 */
    tokenUsage?: {
        total: number;
        last?: any;
    };
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
 *   tokenUsage={{ total: 1500 }}
 * />
 * ```
 */
export const ChatInputTopBar: React.FC<ChatInputTopBarProps> = ({
    sessionId,
    currentModel,
    onModelPress,
    tokenUsage,
    onTokenPress,
    agentColor = '#6366f1',
    activeModelId,
}) => {
    const { isDark, colors } = useTheme();
    const styles = useMemo(() => getTopBarStyles(isDark, colors), [isDark, colors]);

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

    // 设置按钮点击处理
    const handleSettingsPress = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        toggleSettings();
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

                {/* Token统计 */}
                {tokenUsage && (
                    <TouchableOpacity onPress={handleTokenPress} activeOpacity={0.6} style={styles.tokenBar}>
                        <Calculator size={10} color={isDark ? '#52525b' : '#a1a1aa'} />
                        <Typography style={styles.topBarText}>{formatTokenCount(tokenUsage.total)} TOK</Typography>
                    </TouchableOpacity>
                )}

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

                    {/* 设置按钮 */}
                    <TouchableOpacity
                        onPress={handleSettingsPress}
                        activeOpacity={0.6}
                        style={styles.settingsButton}
                    >
                        <Settings size={12} color={colors[500]} />
                        <Typography style={styles.settingsButtonText}>设置</Typography>
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

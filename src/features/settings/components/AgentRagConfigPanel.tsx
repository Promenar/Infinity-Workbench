import React, { useState } from 'react';
import { View, TouchableOpacity, TextInput } from 'react-native';
import { Typography, ConfirmDialog } from '../../../components/ui';
import { useSettingsStore } from '../../../store/settings-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { Agent, RagConfiguration } from '../../../types/chat';
import Slider from '@react-native-community/slider';
import { RefreshCw, Zap, BookOpen, Code } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';

interface Props {
    agent: Agent;
    onUpdate: (updates: Partial<Agent>) => void;
}

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => (
    <View style={{ marginTop: mt }} className="flex-row items-center mb-4 px-1">
        <View className="w-1.5 h-4 bg-indigo-500 rounded-full mr-3" />
        <Typography className="text-sm font-bold text-gray-900 dark:text-white tracking-tight uppercase">
            {title}
        </Typography>
    </View>
);

// 预设配置
const PRESETS = {
    balanced: {
        name: '平衡',
        icon: Zap,
        color: '#6366f1',
        config: {
            contextWindow: 20,
            summaryThreshold: 10,
            memoryLimit: 5,
            memoryThreshold: 0.7,
            docLimit: 8,
            docThreshold: 0.45,
        }
    },
    writing: {
        name: '写作',
        icon: BookOpen,
        color: '#8b5cf6',
        config: {
            contextWindow: 30,
            summaryThreshold: 15,
            memoryLimit: 7,
            memoryThreshold: 0.75,
            docLimit: 10,
            docThreshold: 0.5,
        }
    },
    coding: {
        name: '代码',
        icon: Code,
        color: '#06b6d4',
        config: {
            contextWindow: 15,
            summaryThreshold: 8,
            memoryLimit: 4,
            memoryThreshold: 0.65,
            docLimit: 6,
            docThreshold: 0.4,
        }
    }
};

export const AgentRagConfigPanel: React.FC<Props> = ({ agent, onUpdate }) => {
    const { isDark } = useTheme();
    const globalConfig = useSettingsStore(s => s.globalRagConfig);
    const [showResetDialog, setShowResetDialog] = useState(false);

    // 当前配置：优先使用助手级，否则使用全局
    const currentConfig = agent.ragConfig || globalConfig;
    const isUsingGlobal = !agent.ragConfig;

    // 应用预设
    const applyPreset = (presetKey: keyof typeof PRESETS) => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            const newConfig = { ...(agent.ragConfig || globalConfig), ...PRESETS[presetKey].config };
            onUpdate({ ragConfig: newConfig });
        }, 10);
    };

    // 修改配置
    const handleChange = (updates: Partial<RagConfiguration>) => {
        // 确保创建副本并强制转换为助手配置
        const baseConfig = agent.ragConfig || { ...globalConfig };
        const newConfig = { ...baseConfig, ...updates };
        console.log('[AgentRagConfigPanel] handleChange:', {
            isUsingGlobal,
            hasAgentConfig: !!agent.ragConfig,
            updates,
            newConfig
        });
        onUpdate({ ragConfig: newConfig });
    };

    return (
        <View>
            {/* 状态标签 */}
            <SectionHeader title="配置状态" mt={0} />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                <View className="flex-row items-center justify-between">
                    <View>
                        <Typography className="text-base font-bold text-gray-900 dark:text-white mb-1">
                            配置模式
                        </Typography>
                        <Typography
                            className="text-sm font-medium"
                            style={{ color: isUsingGlobal ? '#10b981' : '#f59e0b' }}
                        >
                            {isUsingGlobal ? '✓ 继承全局设置' : '✏️ 助手自定义配置'}
                        </Typography>
                    </View>
                    {!isUsingGlobal && (
                        <TouchableOpacity
                            onPress={() => {
                                setTimeout(() => {
                                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                    setShowResetDialog(true);
                                }, 10);
                            }}
                            activeOpacity={0.7}
                            className="flex-row items-center bg-green-50 dark:bg-green-900/10 px-4 py-2 rounded-2xl border border-green-100 dark:border-green-900/20"
                        >
                            <RefreshCw size={14} color="#10b981" />
                            <Typography className="ml-2 text-sm font-bold text-green-600 dark:text-green-400">
                                重置
                            </Typography>
                        </TouchableOpacity>
                    )}
                </View>
            </View>

            {/* 预设快捷选择 */}
            <SectionHeader title="快速预设" />
            <View className="flex-row mb-8 gap-3">
                {(Object.keys(PRESETS) as Array<keyof typeof PRESETS>).map((key) => {
                    const preset = PRESETS[key];
                    const Icon = preset.icon;
                    return (
                        <TouchableOpacity
                            key={key}
                            onPress={() => applyPreset(key)}
                            activeOpacity={0.7}
                            className="flex-1 bg-white dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800 items-center shadow-sm"
                        >
                            <Icon size={22} color={preset.color} />
                            <Typography className="text-xs font-bold mt-2 text-gray-900 dark:text-white">
                                {preset.name}
                            </Typography>
                        </TouchableOpacity>
                    );
                })}
            </View>

            {/* 自动摘要设置 */}
            <SectionHeader title="自动摘要设置" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                <View className="mb-4">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        活跃窗口
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        保留在上下文中的最近消息数
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">10</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {currentConfig.contextWindow} 条消息
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">50</Typography>
                    </View>
                    <Slider
                        value={currentConfig.contextWindow}
                        onValueChange={(val) => handleChange({ contextWindow: Math.round(val) })}
                        minimumValue={10}
                        maximumValue={50}
                        step={5}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

                <View className="mb-4">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        触发阈值
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        累积消息数达到此值时自动生成摘要
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {currentConfig.summaryThreshold} 条消息
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">30</Typography>
                    </View>
                    <Slider
                        value={currentConfig.summaryThreshold}
                        onValueChange={(val) => handleChange({ summaryThreshold: Math.round(val) })}
                        minimumValue={5}
                        maximumValue={30}
                        step={5}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

                <View>
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        摘要模板
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        自定义助手生成摘要时的语气和重点
                    </Typography>
                    <TextInput
                        value={currentConfig.summaryPrompt}
                        onChangeText={(text) => handleChange({ summaryPrompt: text })}
                        multiline
                        numberOfLines={4}
                        className="text-gray-600 dark:text-gray-300 bg-gray-50/50 dark:bg-black p-4 rounded-2xl border border-gray-100 dark:border-zinc-800"
                        style={{ textAlignVertical: 'top', minHeight: 120 }}
                        placeholderTextColor="#94a3b8"
                    />
                </View>
            </View>

            {/* 检索配置 */}
            <SectionHeader title="检索配置" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                <Typography className="text-sm font-bold text-gray-700 dark:text-gray-300 mb-4">记忆检索</Typography>

                <View className="mb-4">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        检索数量
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        每次检索返回的最大记忆片段数
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">3</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {currentConfig.memoryLimit} 条
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">10</Typography>
                    </View>
                    <Slider
                        value={currentConfig.memoryLimit}
                        onValueChange={(val) => handleChange({ memoryLimit: Math.round(val) })}
                        minimumValue={3}
                        maximumValue={10}
                        step={1}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#e5e7eb'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="mb-4">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        相似度阈值
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        低于此分数的记忆将被过滤
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">50%</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {Math.round(currentConfig.memoryThreshold * 100)}%
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">95%</Typography>
                    </View>
                    <Slider
                        value={currentConfig.memoryThreshold}
                        onValueChange={(val) => handleChange({ memoryThreshold: val })}
                        minimumValue={0.5}
                        maximumValue={0.95}
                        step={0.05}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="h-[1px] bg-gray-200 dark:bg-zinc-800 my-4" />

                <Typography className="text-sm font-bold text-gray-700 dark:text-gray-300 mb-4">文档检索</Typography>

                <View className="mb-4">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        检索数量
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        每次检索返回的最大文档片段数
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {currentConfig.docLimit} 条
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">15</Typography>
                    </View>
                    <Slider
                        value={currentConfig.docLimit}
                        onValueChange={(val) => handleChange({ docLimit: Math.round(val) })}
                        minimumValue={5}
                        maximumValue={15}
                        step={1}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#e5e7eb'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View>
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        相似度阈值
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        文档检索的最低相似度要求
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">30%</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {Math.round(currentConfig.docThreshold * 100)}%
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">80%</Typography>
                    </View>
                    <Slider
                        value={currentConfig.docThreshold}
                        onValueChange={(val) => handleChange({ docThreshold: val })}
                        minimumValue={0.3}
                        maximumValue={0.8}
                        step={0.05}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#e5e7eb'}
                        thumbTintColor="#6366f1"
                    />
                </View>
            </View>

            {/* 重置确认对话框 */}
            <ConfirmDialog
                visible={showResetDialog}
                title="重置为全局设置"
                message="这将清除此助手的自定义RAG配置，改为使用当前的全局设置。"
                confirmText="确认"
                cancelText="取消"
                onConfirm={() => {
                    setTimeout(() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                        onUpdate({ ragConfig: undefined });
                        setShowResetDialog(false);
                    }, 10);
                }}
                onCancel={() => setShowResetDialog(false)}
                isDestructive
            />
        </View>
    );
};

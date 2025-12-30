import React from 'react';
import { View, TouchableOpacity, TextInput } from 'react-native';
import { Typography } from '../../../components/ui';
import { useSettingsStore } from '../../../store/settings-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import Slider from '@react-native-community/slider';
import { Database, Zap, BookOpen, Code } from 'lucide-react-native';
import { useRouter } from 'expo-router';
import * as Haptics from '../../../lib/haptics';

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
            docChunkSize: 800,
            chunkOverlap: 100,
            memoryChunkSize: 1000,
            contextWindow: 20,
            summaryThreshold: 10,
        }
    },
    writing: {
        name: '写作',
        icon: BookOpen,
        color: '#8b5cf6',
        config: {
            docChunkSize: 1200,
            chunkOverlap: 200,
            memoryChunkSize: 1500,
            contextWindow: 30,
            summaryThreshold: 15,
        }
    },
    coding: {
        name: '代码',
        icon: Code,
        color: '#06b6d4',
        config: {
            docChunkSize: 600,
            chunkOverlap: 50,
            memoryChunkSize: 800,
            contextWindow: 15,
            summaryThreshold: 8,
        }
    }
};

export const GlobalRagConfigPanel: React.FC = () => {
    const { isDark } = useTheme();
    const { t } = useI18n();
    const router = useRouter();
    const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();

    const handleNavigateToDebug = () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            router.push('/settings/rag-debug' as any);
        }, 10);
    };

    const applyPreset = (presetKey: keyof typeof PRESETS) => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            updateGlobalRagConfig(PRESETS[presetKey].config);
        }, 10);
    };

    return (
        <View>
            {/* 快速预设 */}
            <SectionHeader title="快速预设" mt={0} />
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

            {/* 文档切块设置 */}
            <SectionHeader title="文档切块设置" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                <View className="mb-4">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        切块大小 (Chunk Size)
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        将长文档切分为更小段落的字符数
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">400</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {globalRagConfig.docChunkSize} 字符
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">2000</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.docChunkSize}
                        onValueChange={(val) => updateGlobalRagConfig({ docChunkSize: Math.round(val) })}
                        minimumValue={400}
                        maximumValue={2000}
                        step={100}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

                <View className="mt-2">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        切块重叠 (Overlap)
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        相邻切块之间重合的字符数，用于保持语义完整
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">0</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {globalRagConfig.chunkOverlap} 字符
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">500</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.chunkOverlap}
                        onValueChange={(val) => updateGlobalRagConfig({ chunkOverlap: Math.round(val) })}
                        minimumValue={0}
                        maximumValue={500}
                        step={10}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>
            </View>

            {/* 对话记忆设置 */}
            <SectionHeader title="对话记忆设置" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                <View>
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        对话切块大小
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        存入长期记忆时的对话片段长度
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">500</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {globalRagConfig.memoryChunkSize} 字符
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">2000</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.memoryChunkSize}
                        onValueChange={(val) => updateGlobalRagConfig({ memoryChunkSize: Math.round(val) })}
                        minimumValue={500}
                        maximumValue={2000}
                        step={100}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>
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
                            {globalRagConfig.contextWindow} 条消息
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">50</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.contextWindow}
                        onValueChange={(val) => updateGlobalRagConfig({ contextWindow: Math.round(val) })}
                        minimumValue={10}
                        maximumValue={50}
                        step={5}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

                <View className="mt-2">
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        触发阈值
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        累积消息数达到此值时自动生成摘要
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {globalRagConfig.summaryThreshold} 条消息
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">30</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.summaryThreshold}
                        onValueChange={(val) => updateGlobalRagConfig({ summaryThreshold: Math.round(val) })}
                        minimumValue={5}
                        maximumValue={30}
                        step={5}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                        thumbTintColor="#6366f1"
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-5" />

                <View>
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                        摘要模板
                    </Typography>
                    <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                        自定义生成摘要时的语气和重点，支持变量
                    </Typography>
                    <TextInput
                        value={globalRagConfig.summaryPrompt}
                        onChangeText={(text) => updateGlobalRagConfig({ summaryPrompt: text })}
                        multiline
                        numberOfLines={4}
                        className="text-gray-600 dark:text-gray-300 bg-gray-50 dark:bg-black p-4 rounded-2xl border border-gray-100 dark:border-zinc-800"
                        style={{ textAlignVertical: 'top', minHeight: 100 }}
                        placeholderTextColor="#94a3b8"
                    />
                </View>
            </View>

            {/* 检索配置 */}
            <Typography variant="label" className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mt-8 mb-3">
                检索配置
            </Typography>
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
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
                            {globalRagConfig.memoryLimit} 条
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">10</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.memoryLimit}
                        onValueChange={(val) => updateGlobalRagConfig({ memoryLimit: Math.round(val) })}
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
                        低于此分数的记忆将被过滤，避免引入无关信息
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">50%</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {Math.round(globalRagConfig.memoryThreshold * 100)}%
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">95%</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.memoryThreshold}
                        onValueChange={(val) => updateGlobalRagConfig({ memoryThreshold: val })}
                        minimumValue={0.5}
                        maximumValue={0.95}
                        step={0.05}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#e5e7eb'}
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
                            {globalRagConfig.docLimit} 条
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">15</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.docLimit}
                        onValueChange={(val) => updateGlobalRagConfig({ docLimit: Math.round(val) })}
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
                        文档检索的最低相似度要求，较低值返回更多结果
                    </Typography>
                    <View className="flex-row justify-between mb-2">
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">30%</Typography>
                        <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                            {Math.round(globalRagConfig.docThreshold * 100)}%
                        </Typography>
                        <Typography className="text-sm text-gray-600 dark:text-gray-400">80%</Typography>
                    </View>
                    <Slider
                        value={globalRagConfig.docThreshold}
                        onValueChange={(val) => updateGlobalRagConfig({ docThreshold: val })}
                        minimumValue={0.3}
                        maximumValue={0.8}
                        step={0.05}
                        minimumTrackTintColor="#6366f1"
                        maximumTrackTintColor={isDark ? '#27272a' : '#e5e7eb'}
                        thumbTintColor="#6366f1"
                    />
                </View>
            </View>

            {/* 开发者选项 */}
            <Typography variant="label" className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mt-8 mb-3">
                开发者选项
            </Typography>
            <TouchableOpacity
                activeOpacity={0.7}
                onPress={handleNavigateToDebug}
                className="flex-row items-center justify-center py-4 bg-indigo-50 dark:bg-indigo-500/10 rounded-2xl mb-8 border border-indigo-100 dark:border-indigo-500/20"
            >
                <Database size={18} color="#6366f1" className="mr-2" />
                <Typography className="text-indigo-600 dark:text-indigo-400 font-bold">
                    查看向量库统计
                </Typography>
            </TouchableOpacity>
        </View>
    );
};

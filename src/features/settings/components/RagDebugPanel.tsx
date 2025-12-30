import React, { useEffect, useState } from 'react';
import { View, ScrollView, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import { Typography } from '../../../components/ui';
import { VectorStatsService, VectorStats } from '../../../lib/rag/vector-stats';
import { vectorStore } from '../../../lib/rag/vector-store';
import { Trash2, AlertCircle, RefreshCw, Database, FileText, ChevronRight, PieChart, Activity } from 'lucide-react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import * as Haptics from '../../../lib/haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => (
    <View style={{ marginTop: mt }} className="flex-row items-center mb-4 px-1">
        <View className="w-1.5 h-4 bg-indigo-500 rounded-full mr-3" />
        <Typography className="text-sm font-bold text-gray-900 dark:text-white tracking-tight uppercase">
            {title}
        </Typography>
    </View>
);

export const RagDebugPanel: React.FC = () => {
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const [stats, setStats] = useState<VectorStats | null>(null);
    const [loading, setLoading] = useState(true);
    const [isCleaning, setIsCleaning] = useState(false); // Renamed 'cleaning' to 'isCleaning' for consistency

    const loadStats = async () => {
        setLoading(true);
        try {
            const data = await VectorStatsService.getStats();
            setStats(data);
        } catch (error) {
            console.error('Failed to load vector stats:', error);
            Alert.alert('错误', '加载统计信息失败');
        } finally {
            setLoading(false);
        }
    };

    const handleCleanup = async () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        }, 10);
        Alert.alert(
            '确认清理',
            '这将删除所有已被摘要覆盖的旧向量，是否继续？',
            [
                { text: '取消', style: 'cancel' },
                {
                    text: '确认',
                    style: 'destructive',
                    onPress: async () => {
                        setIsCleaning(true);
                        try {
                            const result = await vectorStore.cleanupRedundantMemoryVectors();
                            Alert.alert(
                                '清理完成',
                                `检查了 ${result.checked} 个摘要，删除了 ${result.deleted} 个冗余向量`
                            );
                            loadStats();
                        } catch (error) {
                            Alert.alert('清理失败', (error as Error).message);
                        } finally {
                            setIsCleaning(false);
                        }
                    }
                }
            ]
        );
    };

    useEffect(() => {
        loadStats();
    }, []);

    if (loading) {
        return (
            <View className="flex-1 items-center justify-center">
                <ActivityIndicator size="large" color="#6366f1" />
            </View>
        );
    }

    return (
        <ScrollView
            className="flex-1 px-6"
            contentContainerStyle={{
                paddingTop: 74 + insets.top,
                paddingBottom: 40
            }}
            showsVerticalScrollIndicator={false}
        >
            {/* 标题和刷新 */}
            <View className="flex-row justify-between items-center mb-6">
                <View className="flex-row items-center">
                    <Database size={24} color={isDark ? '#a78bfa' : '#8b5cf6'} />
                    <Typography className="text-2xl font-bold ml-3 text-gray-900 dark:text-white">
                        向量库统计
                    </Typography>
                </View>
                <TouchableOpacity
                    onPress={loadStats}
                    disabled={loading}
                    className="bg-gray-100 dark:bg-zinc-800 p-2 rounded-xl"
                >
                    <RefreshCw size={20} color="#6366f1" />
                </TouchableOpacity>
            </View>

            {stats && (
                <>
                    {/* 总览卡片 */}
                    <SectionHeader title="总览" mt={0} />
                    <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                        <Typography className="text-sm font-bold text-purple-600 dark:text-purple-300 mb-2">
                            总向量数
                        </Typography>
                        <Typography className="text-4xl font-bold text-purple-900 dark:text-purple-100">
                            {stats.total.toLocaleString()}
                        </Typography>
                        <Typography className="text-xs text-purple-600 dark:text-purple-400 mt-2">
                            占用约 {stats.storageSize.toFixed(1)} MB
                        </Typography>
                    </View>

                    {/* 类型分布 */}
                    <SectionHeader title="维度分析" mt={0} />
                    <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                        <View className="flex-row items-center justify-around py-2">
                            <View className="items-center">
                                <Typography className="text-2xl font-bold text-gray-900 dark:text-white">{stats.byType.doc || 0}</Typography>
                                <Typography className="text-xs text-gray-400 mt-1">文档向量</Typography>
                            </View>
                            <View className="w-[1px] h-10 bg-gray-100 dark:bg-zinc-800" />
                            <View className="items-center">
                                <Typography className="text-2xl font-bold text-gray-900 dark:text-white">{stats.byType.memory + stats.byType.summary || 0}</Typography>
                                <Typography className="text-xs text-gray-400 mt-1">记忆向量</Typography>
                            </View>
                        </View>
                    </View>

                    {/* 存储健康度 */}
                    <SectionHeader title="存储健康度" />
                    <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                        <View className="flex-row items-center justify-between mb-4">
                            <View>
                                <Typography className="text-sm font-bold text-gray-900 dark:text-white items-center flex-row">
                                    冗余率
                                </Typography>
                                <Typography className="text-xs text-gray-500 mt-0.5">检测失效或重复的引用数据</Typography>
                            </View>
                            <Typography className={`text-lg font-bold ${stats.redundancyRate > 0.2 ? 'text-red-500' : 'text-green-500'}`}>
                                {(stats.redundancyRate * 100).toFixed(1)}%
                            </Typography>
                        </View>

                        {stats.redundancyRate > 0.1 && (
                            <TouchableOpacity
                                onPress={handleCleanup}
                                disabled={isCleaning}
                                className="bg-red-50 dark:bg-red-900/10 p-4 rounded-2xl flex-row items-center justify-center border border-red-100 dark:border-red-900/20"
                            >
                                {isCleaning ? (
                                    <ActivityIndicator color="#ef4444" />
                                ) : (
                                    <>
                                        <Trash2 size={16} color="#ef4444" className="mr-2" />
                                        <Typography className="text-red-600 dark:text-red-400 font-bold">
                                            立即清理冗余数据
                                        </Typography>
                                    </>
                                )}
                            </TouchableOpacity>
                        )}
                    </View>

                    {/* 按会话统计 */}
                    {stats.bySession.length > 0 && (
                        <>
                            <SectionHeader title="Top 会话分布" />
                            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                                {stats.bySession.map((item, idx) => (
                                    <View
                                        key={idx}
                                        className="flex-row justify-between items-center py-3"
                                        style={{
                                            borderBottomWidth: idx < stats.bySession.length - 1 ? 1 : 0,
                                            borderBottomColor: isDark ? '#18181b' : '#f1f5f9'
                                        }}
                                    >
                                        <View className="flex-1 mr-3">
                                            <Typography
                                                className="font-mono text-[10px] text-gray-500 dark:text-gray-400"
                                                numberOfLines={1}
                                            >
                                                Session ID
                                            </Typography>
                                            <Typography
                                                className="font-mono text-xs text-gray-800 dark:text-gray-200"
                                                numberOfLines={1}
                                            >
                                                {item.sessionId}
                                            </Typography>
                                        </View>
                                        <View className="bg-indigo-50 dark:bg-indigo-900/20 px-3 py-1 rounded-full border border-indigo-100 dark:border-indigo-900/30">
                                            <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
                                                {item.count}
                                            </Typography>
                                        </View>
                                    </View>
                                ))}
                            </View>
                        </>
                    )}
                </>
            )}
        </ScrollView>
    );
};

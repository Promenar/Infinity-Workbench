import React, { useEffect, useState } from 'react';
import { View, ScrollView, TouchableOpacity, ActivityIndicator } from 'react-native';
import { Typography, ConfirmDialog } from '../../../components/ui';
import { useToast } from '../../../components/ui/Toast';
import { VectorStatsService, VectorStats } from '../../../lib/rag/vector-stats';
import { vectorStore } from '../../../lib/rag/vector-store';
import { Trash2, RefreshCw, Database } from 'lucide-react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useI18n } from '../../../lib/i18n';

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
    const { t } = useI18n();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const { showToast } = useToast();
    const [stats, setStats] = useState<VectorStats | null>(null);
    const [loading, setLoading] = useState(true);
    const [isCleaning, setIsCleaning] = useState(false);
    const [showCleanupConfirm, setShowCleanupConfirm] = useState(false);

    const loadStats = async () => {
        setLoading(true);
        try {
            const data = await VectorStatsService.getStats();
            setStats(data);
        } catch (error) {
            console.error('Failed to load vector stats:', error);
            showToast(t.settings.vectorStats.loadError, 'error');
        } finally {
            setLoading(false);
        }
    };

    const handleCleanup = async () => {
        setIsCleaning(true);
        setShowCleanupConfirm(false);
        try {
            const result = await vectorStore.cleanupRedundantMemoryVectors();
            showToast(t.settings.vectorStats.cleanupSuccess.replace('{count}', result.deleted.toString()), 'success');
            loadStats();
        } catch (error) {
            showToast(`${t.settings.vectorStats.cleanupError}: ${(error as Error).message}`, 'error');
        } finally {
            setIsCleaning(false);
        }
    };

    const triggerCleanupPrompt = () => {
        setShowCleanupConfirm(true);
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
                        {t.settings.vectorStats.title}
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
                    <SectionHeader title={t.settings.vectorStats.overview} mt={0} />
                    <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                        <Typography className="text-sm font-bold text-purple-600 dark:text-purple-300 mb-2">
                            {t.settings.vectorStats.totalVectors}
                        </Typography>
                        <Typography className="text-4xl font-bold text-purple-900 dark:text-purple-100">
                            {stats.total.toLocaleString()}
                        </Typography>
                        <Typography className="text-xs text-purple-600 dark:text-purple-400 mt-2">
                            {t.settings.vectorStats.storageOccupied.replace('{size}', stats.storageSize.toFixed(1))}
                        </Typography>
                    </View>

                    {/* 类型分布 */}
                    <SectionHeader title={t.settings.vectorStats.dimensionAnalysis} mt={0} />
                    <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                        <View className="flex-row items-center justify-around py-2">
                            <View className="items-center">
                                <Typography className="text-2xl font-bold text-gray-900 dark:text-white">{stats.byType.doc || 0}</Typography>
                                <Typography className="text-xs text-gray-400 mt-1">{t.settings.vectorStats.docVectors}</Typography>
                            </View>
                            <View className="w-[1px] h-10 bg-gray-100 dark:bg-zinc-800" />
                            <View className="items-center">
                                <Typography className="text-2xl font-bold text-gray-900 dark:text-white">{(stats.byType.memory || 0) + (stats.byType.summary || 0)}</Typography>
                                <Typography className="text-xs text-gray-400 mt-1">{t.settings.vectorStats.memoryVectors}</Typography>
                            </View>
                        </View>
                    </View>

                    {/* 存储健康度 */}
                    <SectionHeader title={t.settings.vectorStats.storageHealth} />
                    <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                        <View className="flex-row items-center justify-between mb-4">
                            <View>
                                <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                                    {t.settings.vectorStats.redundancyRate}
                                </Typography>
                                <Typography className="text-xs text-gray-500 mt-0.5">{t.settings.vectorStats.redundancyDesc}</Typography>
                            </View>
                            <Typography className={`text-lg font-bold ${stats.redundancyRate > 0.2 ? 'text-red-500' : 'text-green-500'}`}>
                                {(stats.redundancyRate * 100).toFixed(1)}%
                            </Typography>
                        </View>

                        {stats.redundancyRate > 0.01 && (
                            <TouchableOpacity
                                onPress={triggerCleanupPrompt}
                                disabled={isCleaning}
                                className="bg-red-50 dark:bg-red-900/10 p-4 rounded-2xl flex-row items-center justify-center border border-red-100 dark:border-red-900/20"
                            >
                                {isCleaning ? (
                                    <ActivityIndicator color="#ef4444" />
                                ) : (
                                    <>
                                        <Trash2 size={16} color="#ef4444" className="mr-2" />
                                        <Typography className="text-red-600 dark:text-red-400 font-bold">
                                            {t.settings.vectorStats.cleanupNow}
                                        </Typography>
                                    </>
                                )}
                            </TouchableOpacity>
                        )}
                    </View>

                    {/* 按会话统计 */}
                    {stats.bySession.length > 0 && (
                        <>
                            <SectionHeader title={t.settings.vectorStats.topSessions} />
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

            <ConfirmDialog
                visible={showCleanupConfirm}
                title={t.settings.vectorStats.cleanupConfirmTitle}
                message={t.settings.vectorStats.cleanupConfirmDesc}
                confirmText={t.settings.vectorStats.cleanupConfirmBtn}
                cancelText={t.settings.vectorStats.cleanupCancelBtn}
                onConfirm={handleCleanup}
                onCancel={() => setShowCleanupConfirm(false)}
                isDestructive
            />
        </ScrollView>
    );
};

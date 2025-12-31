import React from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, Switch } from '../../../components/ui';
import { useSettingsStore } from '../../../store/settings-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import Slider from '@react-native-community/slider';
import { ArrowUpDown, Sparkles, GitMerge, BarChart3 } from 'lucide-react-native';

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => (
    <View style={{ marginTop: mt }} className="flex-row items-center mb-4 px-1">
        <View className="w-1.5 h-4 bg-purple-500 rounded-full mr-3" />
        <Typography className="text-sm font-bold text-gray-900 dark:text-white tracking-tight uppercase">
            {title}
        </Typography>
    </View>
);

export const AdvancedRetrievalPanel: React.FC = () => {
    const { isDark } = useTheme();
    const { t } = useI18n();
    const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();

    return (
        <View>
            {/* Rerank配置 */}
            <SectionHeader title="Rerank 二次精排" mt={0} />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                {/* 启用Rerank */}
                <View className="flex-row items-center justify-between mb-6">
                    <View className="flex-1 mr-4">
                        <View className="flex-row items-center mb-1">
                            <ArrowUpDown size={16} color="#a855f7" className="mr-2" />
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                                启用Rerank
                            </Typography>
                        </View>
                        <Typography className="text-xs text-gray-500 dark:text-gray-400">
                            使用专门的重排序模型对检索结果进行二次精排
                        </Typography>
                    </View>
                    <Switch
                        value={globalRagConfig.enableRerank ?? false}
                        onValueChange={(val) => updateGlobalRagConfig({ enableRerank: val })}
                    />
                </View>

                {globalRagConfig.enableRerank && (
                    <>
                        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-4" />

                        {/* 初召回数量 */}
                        <View className="mb-4">
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                                初召回数量
                            </Typography>
                            <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                                Rerank前召回的文档数量（建议20-50）
                            </Typography>
                            <View className="flex-row justify-between mb-2">
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">10</Typography>
                                <Typography className="text-sm font-bold text-purple-600 dark:text-purple-400">
                                    {globalRagConfig.rerankTopK ?? 30} 条
                                </Typography>
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">100</Typography>
                            </View>
                            <Slider
                                value={globalRagConfig.rerankTopK ?? 30}
                                onValueChange={(val) => updateGlobalRagConfig({ rerankTopK: Math.round(val) })}
                                minimumValue={10}
                                maximumValue={100}
                                step={5}
                                minimumTrackTintColor="#a855f7"
                                maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                                thumbTintColor="#a855f7"
                            />
                        </View>

                        {/* 精排后返回数量 */}
                        <View>
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                                精排后返回
                            </Typography>
                            <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                                Rerank后实际使用的文档数量（建议5-10）
                            </Typography>
                            <View className="flex-row justify-between mb-2">
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">3</Typography>
                                <Typography className="text-sm font-bold text-purple-600 dark:text-purple-400">
                                    {globalRagConfig.rerankFinalK ?? 8} 条
                                </Typography>
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">20</Typography>
                            </View>
                            <Slider
                                value={globalRagConfig.rerankFinalK ?? 8}
                                onValueChange={(val) => updateGlobalRagConfig({ rerankFinalK: Math.round(val) })}
                                minimumValue={3}
                                maximumValue={20}
                                step={1}
                                minimumTrackTintColor="#a855f7"
                                maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                                thumbTintColor="#a855f7"
                            />
                        </View>
                    </>
                )}
            </View>

            {/* 查询重写配置 */}
            <SectionHeader title="查询重写" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                {/* 启用查询重写 */}
                <View className="flex-row items-center justify-between mb-6">
                    <View className="flex-1 mr-4">
                        <View className="flex-row items-center mb-1">
                            <Sparkles size={16} color="#f59e0b" className="mr-2" />
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                                启用查询重写
                            </Typography>
                        </View>
                        <Typography className="text-xs text-gray-500 dark:text-gray-400">
                            生成多个查询变体以提升召回率
                        </Typography>
                    </View>
                    <Switch
                        value={globalRagConfig.enableQueryRewrite ?? false}
                        onValueChange={(val) => updateGlobalRagConfig({ enableQueryRewrite: val })}
                    />
                </View>

                {globalRagConfig.enableQueryRewrite && (
                    <>
                        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-4" />

                        {/* 重写策略 */}
                        <View className="mb-4">
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-3">
                                重写策略
                            </Typography>
                            <View className="flex-row gap-2">
                                {(['hyde', 'multi-query', 'expansion'] as const).map((strategy) => (
                                    <TouchableOpacity
                                        key={strategy}
                                        onPress={() => updateGlobalRagConfig({ queryRewriteStrategy: strategy })}
                                        className={`flex-1 py-3 px-3 rounded-xl border ${(globalRagConfig.queryRewriteStrategy ?? 'multi-query') === strategy
                                            ? 'bg-amber-50 dark:bg-amber-500/10 border-amber-500'
                                            : 'bg-gray-50 dark:bg-zinc-800 border-gray-200 dark:border-zinc-700'
                                            }`}
                                    >
                                        <Typography className={`text-xs font-bold text-center ${(globalRagConfig.queryRewriteStrategy ?? 'multi-query') === strategy
                                            ? 'text-amber-600 dark:text-amber-400'
                                            : 'text-gray-600 dark:text-gray-400'
                                            }`}>
                                            {strategy === 'hyde' ? 'HyDE' : strategy === 'multi-query' ? '多查询' : '扩展'}
                                        </Typography>
                                    </TouchableOpacity>
                                ))}
                            </View>
                        </View>

                        {/* 变体数量 */}
                        <View>
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                                变体数量
                            </Typography>
                            <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                                生成的查询变体数量（2-5个）
                            </Typography>
                            <View className="flex-row justify-between mb-2">
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">2</Typography>
                                <Typography className="text-sm font-bold text-amber-600 dark:text-amber-400">
                                    {globalRagConfig.queryRewriteCount ?? 3} 个
                                </Typography>
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
                            </View>
                            <Slider
                                value={globalRagConfig.queryRewriteCount ?? 3}
                                onValueChange={(val) => updateGlobalRagConfig({ queryRewriteCount: Math.round(val) })}
                                minimumValue={2}
                                maximumValue={5}
                                step={1}
                                minimumTrackTintColor="#f59e0b"
                                maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                                thumbTintColor="#f59e0b"
                            />
                        </View>
                    </>
                )}
            </View>

            {/* 混合检索配置 */}
            <SectionHeader title="混合检索" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                {/* 启用混合检索 */}
                <View className="flex-row items-center justify-between mb-6">
                    <View className="flex-1 mr-4">
                        <View className="flex-row items-center mb-1">
                            <GitMerge size={16} color="#06b6d4" className="mr-2" />
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                                启用混合检索
                            </Typography>
                        </View>
                        <Typography className="text-xs text-gray-500 dark:text-gray-400">
                            结合向量检索和关键词检索（BM25）
                        </Typography>
                    </View>
                    <Switch
                        value={globalRagConfig.enableHybridSearch ?? false}
                        onValueChange={(val) => updateGlobalRagConfig({ enableHybridSearch: val })}
                    />
                </View>

                {globalRagConfig.enableHybridSearch && (
                    <>
                        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-4" />

                        {/* 向量权重 */}
                        <View className="mb-4">
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                                向量检索权重
                            </Typography>
                            <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                                向量检索在混合检索中的权重（0.5为均衡）
                            </Typography>
                            <View className="flex-row justify-between mb-2">
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">0</Typography>
                                <Typography className="text-sm font-bold text-cyan-600 dark:text-cyan-400">
                                    {((globalRagConfig.hybridAlpha ?? 0.6) * 100).toFixed(0)}%
                                </Typography>
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">100%</Typography>
                            </View>
                            <Slider
                                value={globalRagConfig.hybridAlpha ?? 0.6}
                                onValueChange={(val) => updateGlobalRagConfig({ hybridAlpha: val })}
                                minimumValue={0}
                                maximumValue={1}
                                step={0.1}
                                minimumTrackTintColor="#06b6d4"
                                maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                                thumbTintColor="#06b6d4"
                            />
                        </View>

                        {/* BM25权重增益 */}
                        <View>
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                                BM25权重增益
                            </Typography>
                            <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
                                BM25分数的放大倍数（默认1.0）
                            </Typography>
                            <View className="flex-row justify-between mb-2">
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">0.5x</Typography>
                                <Typography className="text-sm font-bold text-cyan-600 dark:text-cyan-400">
                                    {(globalRagConfig.hybridBM25Boost ?? 1.0).toFixed(1)}x
                                </Typography>
                                <Typography className="text-sm text-gray-600 dark:text-gray-400">2.0x</Typography>
                            </View>
                            <Slider
                                value={globalRagConfig.hybridBM25Boost ?? 1.0}
                                onValueChange={(val) => updateGlobalRagConfig({ hybridBM25Boost: val })}
                                minimumValue={0.5}
                                maximumValue={2.0}
                                step={0.1}
                                minimumTrackTintColor="#06b6d4"
                                maximumTrackTintColor={isDark ? '#27272a' : '#f1f5f9'}
                                thumbTintColor="#06b6d4"
                            />
                        </View>
                    </>
                )}
            </View>

            {/* 可观测性配置 */}
            <SectionHeader title="可观测性" />
            <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
                {/* 显示检索进度 */}
                <View className="flex-row items-center justify-between mb-4">
                    <View className="flex-1 mr-4">
                        <View className="flex-row items-center mb-1">
                            <BarChart3 size={16} color="#10b981" className="mr-2" />
                            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                                显示检索进度
                            </Typography>
                        </View>
                        <Typography className="text-xs text-gray-500 dark:text-gray-400">
                            显示实时检索进度条
                        </Typography>
                    </View>
                    <Switch
                        value={globalRagConfig.showRetrievalProgress ?? true}
                        onValueChange={(val) => updateGlobalRagConfig({ showRetrievalProgress: val })}
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-4" />

                {/* 显示检索详情 */}
                <View className="flex-row items-center justify-between mb-4">
                    <View className="flex-1 mr-4">
                        <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                            显示检索详情
                        </Typography>
                        <Typography className="text-xs text-gray-500 dark:text-gray-400">
                            显示详细的检索统计面板
                        </Typography>
                    </View>
                    <Switch
                        value={globalRagConfig.showRetrievalDetails ?? false}
                        onValueChange={(val) => updateGlobalRagConfig({ showRetrievalDetails: val })}
                    />
                </View>

                <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-4" />

                {/* 记录检索指标 */}
                <View className="flex-row items-center justify-between">
                    <View className="flex-1 mr-4">
                        <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
                            记录检索指标
                        </Typography>
                        <Typography className="text-xs text-gray-500 dark:text-gray-400">
                            记录耗时、召回率等指标用于后续分析
                        </Typography>
                    </View>
                    <Switch
                        value={globalRagConfig.trackRetrievalMetrics ?? false}
                        onValueChange={(val) => updateGlobalRagConfig({ trackRetrievalMetrics: val })}
                    />
                </View>
            </View>
        </View>
    );
};

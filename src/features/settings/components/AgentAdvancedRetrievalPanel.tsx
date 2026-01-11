import React, { useState } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, ConfirmDialog, Switch } from '../../../components/ui';
import { ThemedSlider as Slider } from '../../../components/ui/Slider';
import { useSettingsStore } from '../../../store/settings-store';
import { useI18n } from '../../../lib/i18n';
import { useTheme } from '../../../theme/ThemeProvider';
import { Agent, RagConfiguration } from '../../../types/chat';
import { RefreshCw } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';

interface Props {
  agent: Agent;
  onUpdate: (updates: Partial<Agent>) => void;
}

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => {
  const { colors } = useTheme();
  return (
    <View style={{ marginTop: mt }} className="flex-row items-center mb-4 px-1">
      <View style={{ backgroundColor: colors[500] }} className="w-1.5 h-4 rounded-full mr-3" />
      <Typography className="text-sm font-bold text-gray-900 dark:text-white tracking-tight uppercase">
        {title}
      </Typography>
    </View>
  );
};

export const AgentAdvancedRetrievalPanel: React.FC<Props> = ({ agent, onUpdate }) => {
  const { t } = useI18n();
  const { colors, isDark } = useTheme();
  const globalConfig = useSettingsStore((s) => s.globalRagConfig);
  const [showResetDialog, setShowResetDialog] = useState(false);

  // 当前配置：优先使用助手级，否则使用全局
  const currentConfig = agent.ragConfig || globalConfig;
  const isUsingGlobal = !agent.ragConfig;

  // 修改配置
  const handleChange = (updates: Partial<RagConfiguration>) => {
    const baseConfig = agent.ragConfig || { ...globalConfig };
    const newConfig = { ...baseConfig, ...updates };
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
              style={{ color: isUsingGlobal ? (isDark ? '#34d399' : '#059669') : colors[500] }}
            >
              {isUsingGlobal ? '继承全局配置' : '自定义配置'}
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
              style={{ backgroundColor: colors.opacity10, borderColor: colors.opacity20 }}
              className="flex-row items-center px-4 py-2 rounded-2xl border"
            >
              <RefreshCw size={14} color={colors[600]} />
              <Typography style={{ color: colors[600] }} className="ml-2 text-sm font-bold">
                重置
              </Typography>
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* 检索配置 (从 RAG 设置页面移入) */}
      <SectionHeader title={t.rag.retrievalSettings} />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        <Typography className="text-sm font-bold text-gray-700 dark:text-gray-300 mb-4">
          {t.rag.memoryRetrieval}
        </Typography>

        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.memoryLimit}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.memoryLimitDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">3</Typography>
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {t.rag.items.replace('{count}', (currentConfig.memoryLimit ?? 5).toString())}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">10</Typography>
          </View>
          <Slider
            value={currentConfig.memoryLimit ?? 5}
            onValueChange={(val) => handleChange({ memoryLimit: Math.round(val) })}
            minimumValue={3}
            maximumValue={10}
            step={1}
          />
        </View>

        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.similarityThreshold}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.agentMemorySimilarityThresholdDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">50%</Typography>
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {Math.round((currentConfig.memoryThreshold ?? 0.7) * 100)}%
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">95%</Typography>
          </View>
          <Slider
            value={currentConfig.memoryThreshold ?? 0.7}
            onValueChange={(val) => handleChange({ memoryThreshold: val })}
            minimumValue={0.5}
            maximumValue={0.95}
            step={0.05}
          />
        </View>

        <View className="h-[1px] bg-gray-200 dark:bg-zinc-800 my-4" />

        <Typography className="text-sm font-bold text-gray-700 dark:text-gray-300 mb-4">
          {t.rag.docRetrieval}
        </Typography>

        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.docLimit}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.docLimitDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {t.rag.items.replace('{count}', (currentConfig.docLimit ?? 8).toString())}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">15</Typography>
          </View>
          <Slider
            value={currentConfig.docLimit ?? 8}
            onValueChange={(val) => handleChange({ docLimit: Math.round(val) })}
            minimumValue={5}
            maximumValue={15}
            step={1}
          />
        </View>

        <View>
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.similarityThreshold}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.agentDocSimilarityThresholdDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">30%</Typography>
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {Math.round((currentConfig.docThreshold ?? 0.45) * 100)}%
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">80%</Typography>
          </View>
          <Slider
            value={currentConfig.docThreshold ?? 0.45}
            onValueChange={(val) => handleChange({ docThreshold: val })}
            minimumValue={0.3}
            maximumValue={0.8}
            step={0.05}
          />
        </View>
      </View>

      {/* Rerank配置 */}
      <SectionHeader title="Rerank 二次精排" />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        {/* 启用Rerank */}
        <View className="flex-row items-center justify-between mb-6">
          <View className="flex-1 mr-4">
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
              启用Rerank
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              使用专门的重排序模型对检索结果进行二次精排
            </Typography>
          </View>
          <Switch
            value={currentConfig.enableRerank ?? false}
            onValueChange={(val) => handleChange({ enableRerank: val })}
          />
        </View>

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
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {currentConfig.rerankTopK ?? 30} 条
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">100</Typography>
          </View>
          <Slider
            value={currentConfig.rerankTopK ?? 30}
            onValueChange={(val) => handleChange({ rerankTopK: Math.round(val) })}
            minimumValue={10}
            maximumValue={100}
            step={5}
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
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {currentConfig.rerankFinalK ?? 8} 条
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">20</Typography>
          </View>
          <Slider
            value={currentConfig.rerankFinalK ?? 8}
            onValueChange={(val) => handleChange({ rerankFinalK: Math.round(val) })}
            minimumValue={3}
            maximumValue={20}
            step={1}
          />
        </View>
      </View>

      {/* 查询重写配置 */}
      <SectionHeader title="查询重写" />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        {/* 启用查询重写 */}
        <View className="flex-row items-center justify-between mb-6">
          <View className="flex-1 mr-4">
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
              启用查询重写
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              生成多个查询变体以提升召回率
            </Typography>
          </View>
          <Switch
            value={currentConfig.enableQueryRewrite ?? false}
            onValueChange={(val) => handleChange({ enableQueryRewrite: val })}
          />
        </View>

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
                onPress={() => handleChange({ queryRewriteStrategy: strategy })}
                style={{
                  backgroundColor: (currentConfig.queryRewriteStrategy ?? 'multi-query') === strategy ? colors.opacity10 : 'transparent',
                  borderColor: (currentConfig.queryRewriteStrategy ?? 'multi-query') === strategy ? colors[500] : 'rgba(156, 163, 175, 0.2)'
                }}
                className="flex-1 py-3 px-3 rounded-xl border"
              >
                <Typography
                  style={{
                    color: (currentConfig.queryRewriteStrategy ?? 'multi-query') === strategy ? colors[600] : '#6b7280'
                  }}
                  className="text-xs font-bold text-center"
                >
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
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {currentConfig.queryRewriteCount ?? 3} 个
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
          </View>
          <Slider
            value={currentConfig.queryRewriteCount ?? 3}
            onValueChange={(val) => handleChange({ queryRewriteCount: Math.round(val) })}
            minimumValue={2}
            maximumValue={5}
            step={1}
          />
        </View>
      </View>

      {/* 混合检索配置 */}
      <SectionHeader title="混合检索" />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        {/* 启用混合检索 */}
        <View className="flex-row items-center justify-between mb-6">
          <View className="flex-1 mr-4">
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
              启用混合检索
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              结合向量检索和关键词检索（BM25）
            </Typography>
          </View>
          <Switch
            value={currentConfig.enableHybridSearch ?? false}
            onValueChange={(val) => handleChange({ enableHybridSearch: val })}
          />
        </View>

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
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {((currentConfig.hybridAlpha ?? 0.6) * 100).toFixed(0)}%
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">100%</Typography>
          </View>
          <Slider
            value={currentConfig.hybridAlpha ?? 0.6}
            onValueChange={(val) => handleChange({ hybridAlpha: val })}
            minimumValue={0}
            maximumValue={1}
            step={0.1}
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
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {(currentConfig.hybridBM25Boost ?? 1.0).toFixed(1)}x
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">2.0x</Typography>
          </View>
          <Slider
            value={currentConfig.hybridBM25Boost ?? 1.0}
            onValueChange={(val) => handleChange({ hybridBM25Boost: val })}
            minimumValue={0.5}
            maximumValue={2.0}
            step={0.1}
          />
        </View>
      </View>

      {/* 可观测性配置 */}
      <SectionHeader title="可观测性" />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        {/* 显示检索进度 */}
        <View className="flex-row items-center justify-between mb-4">
          <View className="flex-1 mr-4">
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
              显示检索进度
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              显示实时检索进度条
            </Typography>
          </View>
          <Switch
            value={currentConfig.showRetrievalProgress ?? true}
            onValueChange={(val) => handleChange({ showRetrievalProgress: val })}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-4" />

        {/* 显示检索详情 */}
        <View className="flex-row items-center justify-between">
          <View className="flex-1 mr-4">
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
              显示检索详情
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              显示详细的检索统计面板
            </Typography>
          </View>
          <Switch
            value={currentConfig.showRetrievalDetails ?? false}
            onValueChange={(val) => handleChange({ showRetrievalDetails: val })}
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
            value={currentConfig.trackRetrievalMetrics ?? false}
            onValueChange={(val) => handleChange({ trackRetrievalMetrics: val })}
          />
        </View>
      </View>

      {/* 重置确认对话框 */}
      <ConfirmDialog
        visible={showResetDialog}
        title="重置配置"
        message="重置后将继承全局配置,当前自定义配置将丢失。确认重置?"
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

import React from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, Switch } from '../../../components/ui';
import { useSettingsStore } from '../../../store/settings-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import { ThemedSlider } from '../../../components/ui/Slider';

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => {
  const { colors } = useTheme();
  return (
    <View style={{ marginTop: mt }} className="flex-row items-center mb-4 px-1">
      <View style={{ backgroundColor: colors[500] }} className="w-1 h-3 rounded-full mr-2" />
      <Typography className="text-xs font-bold text-gray-900 dark:text-white tracking-tight uppercase">
        {title}
      </Typography>
    </View>
  );
};

export const AdvancedRetrievalPanel: React.FC = () => {
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();

  return (
    <View>
      {/* 检索配置 */}
      <SectionHeader title={t.rag.retrievalSettings} mt={0} />
      <View className="bg-gray-50/50 dark:bg-zinc-900/60 rounded-[32px] p-4 border border-indigo-50 dark:border-indigo-500/10 mb-4">
        <Typography className="text-sm font-bold text-gray-700 dark:text-gray-300 mb-4">
          {t.rag.memoryRetrieval}
        </Typography>

        <View className="mb-2" style={{ opacity: globalRagConfig.enableRerank ? 0.5 : 1 }}>
          <View className="flex-row items-center mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.memoryLimit}
            </Typography>
            {globalRagConfig.enableRerank && (
              <View
                className="px-1.5 py-0.5 rounded ml-2"
                style={{ backgroundColor: isDark ? `${colors[500]}30` : `${colors[500]}15` }}
              >
                <Typography
                  className="text-[9px] font-bold"
                  style={{ color: colors[500] }}
                >
                  Rerank
                </Typography>
              </View>
            )}
            <View className="flex-1" />
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {t.rag.unitItems.replace('{count}', (globalRagConfig.memoryLimit ?? 5).toString())}
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.memoryLimitDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">3</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">10</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.memoryLimit ?? 5}
            onValueChange={(val) => updateGlobalRagConfig({ memoryLimit: Math.round(val) })}
            minimumValue={3}
            maximumValue={10}
            step={1}
            disabled={globalRagConfig.enableRerank}
          />
        </View>

        <View className="mb-2">
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.similarityThreshold}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {Math.round((globalRagConfig.memoryThreshold ?? 0.7) * 100)}%
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.memoryThresholdDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">50%</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">95%</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.memoryThreshold ?? 0.7}
            onValueChange={(val) => updateGlobalRagConfig({ memoryThreshold: val })}
            minimumValue={0.5}
            maximumValue={0.95}
            step={0.05}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-6" />

        <Typography className="text-sm font-bold text-gray-700 dark:text-gray-300 mb-4">
          {t.rag.docRetrieval}
        </Typography>

        <View className="mb-2" style={{ opacity: globalRagConfig.enableRerank ? 0.5 : 1 }}>
          <View className="flex-row items-center mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.docLimit}
            </Typography>
            {globalRagConfig.enableRerank && (
              <View
                className="px-1.5 py-0.5 rounded ml-2"
                style={{ backgroundColor: isDark ? `${colors[500]}30` : `${colors[500]}15` }}
              >
                <Typography
                  className="text-[9px] font-bold"
                  style={{ color: colors[500] }}
                >
                  Rerank
                </Typography>
              </View>
            )}
            <View className="flex-1" />
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {t.rag.unitItems.replace('{count}', (globalRagConfig.docLimit ?? 8).toString())}
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.docLimitDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">5</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">15</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.docLimit ?? 8}
            onValueChange={(val) => updateGlobalRagConfig({ docLimit: Math.round(val) })}
            minimumValue={5}
            maximumValue={15}
            step={1}
            disabled={globalRagConfig.enableRerank}
          />
        </View>

        <View>
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.similarityThreshold}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {Math.round((globalRagConfig.docThreshold ?? 0.45) * 100)}%
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.docThresholdDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">30%</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">80%</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.docThreshold ?? 0.45}
            onValueChange={(val) => updateGlobalRagConfig({ docThreshold: val })}
            minimumValue={0.3}
            maximumValue={0.8}
            step={0.05}
          />
        </View>
      </View>

      {/* Rerank配置 */}
      <SectionHeader title={t.rag.rerankSection} />
      <View className="bg-gray-50/50 dark:bg-zinc-900/60 rounded-[32px] p-4 border border-indigo-50 dark:border-indigo-500/10 mb-4">
        {/* 启用Rerank */}
        <View className="flex-row items-center justify-between mb-2">
          <View className="flex-1 mr-4">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100 mb-1">
              {t.rag.enableRerank}
            </Typography>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400">
              {t.rag.rerankEnabledDesc}
            </Typography>
          </View>
          <Switch
            value={globalRagConfig.enableRerank ?? false}
            onValueChange={(val) => updateGlobalRagConfig({ enableRerank: val })}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-6" />

        {/* 初召回数量 */}
        <View className="mb-2">
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.rerankRecallCount}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {t.rag.unitItems.replace('{count}', (globalRagConfig.rerankTopK ?? 30).toString())}
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.rerankRecallCountDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">10</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">100</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.rerankTopK ?? 30}
            onValueChange={(val) => updateGlobalRagConfig({ rerankTopK: Math.round(val) })}
            minimumValue={10}
            maximumValue={100}
            step={5}
          />
        </View>

        {/* 精排后返回数量 */}
        <View>
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.rerankResultCount}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {t.rag.unitItems.replace('{count}', (globalRagConfig.rerankFinalK ?? 8).toString())}
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.rerankFinalKDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">3</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">20</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.rerankFinalK ?? 8}
            onValueChange={(val) => updateGlobalRagConfig({ rerankFinalK: Math.round(val) })}
            minimumValue={3}
            maximumValue={20}
            step={1}
          />
        </View>
      </View>

      {/* 查询重写配置 */}
      <SectionHeader title={t.rag.queryRewrite} />
      <View className="bg-gray-50/50 dark:bg-zinc-900/60 rounded-[32px] p-4 border border-indigo-50 dark:border-indigo-500/10 mb-4">
        {/* 启用查询重写 */}
        <View className="flex-row items-center justify-between mb-2">
          <View className="flex-1 mr-4">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100 mb-1">
              {t.rag.queryRewriteEnabled}
            </Typography>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400">
              {t.rag.queryRewriteEnabledDesc}
            </Typography>
          </View>
          <Switch
            value={globalRagConfig.enableQueryRewrite ?? false}
            onValueChange={(val) => updateGlobalRagConfig({ enableQueryRewrite: val })}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-6" />

        {/* 重写策略 */}
        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-3">
            {t.rag.queryRewriteStrategy}
          </Typography>
          <View className="flex-row gap-2">
            {(['hyde', 'multi-query', 'expansion'] as const).map((strategy) => (
              <TouchableOpacity
                key={strategy}
                onPress={() => updateGlobalRagConfig({ queryRewriteStrategy: strategy })}
                style={{
                  backgroundColor: (globalRagConfig.queryRewriteStrategy ?? 'multi-query') === strategy ? colors.opacity10 : 'transparent',
                  borderColor: (globalRagConfig.queryRewriteStrategy ?? 'multi-query') === strategy ? colors[500] : (isDark ? 'rgba(255,255,255,0.1)' : 'rgba(156, 163, 175, 0.2)')
                }}
                className="flex-1 py-3 px-3 rounded-xl border"
              >
                <Typography
                  style={{
                    color: (globalRagConfig.queryRewriteStrategy ?? 'multi-query') === strategy ? colors[600] : '#6b7280'
                  }}
                  className="text-xs font-bold text-center"
                >
                  {strategy === 'hyde' ? t.rag.strategyHyde : strategy === 'multi-query' ? t.rag.strategyMultiQuery : t.rag.strategyExpansion}
                </Typography>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        {/* 变体数量 */}
        <View>
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.queryRewriteCount}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {t.rag.unitItems.replace('{count}', (globalRagConfig.queryRewriteCount ?? 3).toString())}
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.queryRewriteCountDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">2</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">5</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.queryRewriteCount ?? 3}
            onValueChange={(val) => updateGlobalRagConfig({ queryRewriteCount: Math.round(val) })}
            minimumValue={2}
            maximumValue={5}
            step={1}
          />
        </View>
      </View>

      {/* 混合检索配置 */}
      <SectionHeader title={t.rag.hybridSearch} />
      <View className="bg-gray-50/50 dark:bg-zinc-900/60 rounded-[32px] p-4 border border-indigo-50 dark:border-indigo-500/10 mb-4">
        {/* 启用混合检索 */}
        <View className="flex-row items-center justify-between mb-2">
          <View className="flex-1 mr-4">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100 mb-1">
              {t.rag.hybridSearchEnabled}
            </Typography>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400">
              {t.rag.hybridSearchEnabledDesc}
            </Typography>
          </View>
          <Switch
            value={globalRagConfig.enableHybridSearch ?? false}
            onValueChange={(val) => updateGlobalRagConfig({ enableHybridSearch: val })}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-6" />

        {/* 向量权重 */}
        <View className="mb-2">
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.vectorWeight}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {((globalRagConfig.hybridAlpha ?? 0.6) * 100).toFixed(0)}%
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.vectorWeightDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">0</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">100%</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.hybridAlpha ?? 0.6}
            onValueChange={(val) => updateGlobalRagConfig({ hybridAlpha: val })}
            minimumValue={0}
            maximumValue={1}
            step={0.1}
          />
        </View>

        {/* BM25权重增益 */}
        <View>
          <View className="flex-row items-center justify-between mb-1">
            <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
              {t.rag.bm25Boost}
            </Typography>
            <Typography style={{ color: colors[500] }} className="text-xs font-bold">
              {(globalRagConfig.hybridBM25Boost ?? 1.0).toFixed(1)}x
            </Typography>
          </View>
          <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
            {t.rag.bm25BoostDesc}
          </Typography>
          <View className="flex-row justify-between mb-0">
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">0.5x</Typography>
            <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">2.0x</Typography>
          </View>
          <ThemedSlider
            value={globalRagConfig.hybridBM25Boost ?? 1.0}
            onValueChange={(val) => updateGlobalRagConfig({ hybridBM25Boost: val })}
            minimumValue={0.5}
            maximumValue={2.0}
            step={0.1}
          />
        </View>
      </View>

      {/* 可观测性配置 */}
      <SectionHeader title={t.rag.observability} />
      <View className="bg-gray-50/50 dark:bg-zinc-900/60 rounded-[32px] p-4 border border-indigo-50 dark:border-indigo-500/10 mb-4">
        {/* 显示检索进度 */}
        <View className="flex-row items-center justify-between mb-4">
          <View className="flex-1 mr-4">
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
              {t.rag.showProgress}
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              {t.rag.showProgressDesc}
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
              {t.rag.showDetails}
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              {t.rag.showDetailsDesc}
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
              {t.rag.trackMetrics}
            </Typography>
            <Typography className="text-xs text-gray-500 dark:text-gray-400">
              {t.rag.trackMetricsDesc}
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

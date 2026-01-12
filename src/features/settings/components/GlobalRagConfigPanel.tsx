import React, { useState, useMemo } from 'react';
import { View, TouchableOpacity, TextInput, StyleSheet, Alert } from 'react-native';
import { Typography, Switch } from '../../../components/ui';
import { ThemedSlider as Slider } from '../../../components/ui/Slider';
import { useSettingsStore } from '../../../store/settings-store';
import { useRagStore } from '../../../store/rag-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import { Database, Zap, BookOpen, Code, Trash2, Edit3, RotateCcw } from 'lucide-react-native';
import { useRouter } from 'expo-router';
import * as Haptics from '../../../lib/haptics';
import { vectorStore } from '../../../lib/rag/vector-store';
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog';
import { FloatingTextEditorModal } from '../../../components/ui/FloatingTextEditorModal';

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => {
  const { colors } = useTheme();
  return (
    <View
      style={{
        marginTop: mt,
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 16,
        paddingHorizontal: 4,
      }}
    >
      <View
        style={{
          width: 6,
          height: 16,
          borderRadius: 999,
          marginRight: 12,
          backgroundColor: colors[500],
        }}
      />
      <Typography className="text-sm font-bold uppercase tracking-widest text-gray-900 dark:text-white">
        {title}
      </Typography>
    </View>
  );
};

// 预设配置
const PRESETS = {
  balanced: {
    name: '平衡',
    icon: Zap,
    config: {
      docChunkSize: 800,
      chunkOverlap: 100,
      memoryChunkSize: 1000,
      contextWindow: 20,
      summaryThreshold: 10,
    },
  },
  writing: {
    name: '写作',
    icon: BookOpen,
    config: {
      docChunkSize: 1200,
      chunkOverlap: 200,
      memoryChunkSize: 1500,
      contextWindow: 30,
      summaryThreshold: 15,
    },
  },
  coding: {
    name: '代码',
    icon: Code,
    config: {
      docChunkSize: 600,
      chunkOverlap: 50,
      memoryChunkSize: 800,
      contextWindow: 15,
      summaryThreshold: 8,
    },
  },
};

export const GlobalRagConfigPanel: React.FC = () => {
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const router = useRouter();
  const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();
  const { getVectorStats } = useRagStore();
  const vectorStats = getVectorStats();

  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [isClearing, setIsClearing] = useState(false);

  // Local state for prompt editing
  const [promptText, setPromptText] = useState(globalRagConfig.summaryPrompt || '');
  const [isEditorVisible, setIsEditorVisible] = useState(false);

  // Sync prompt text
  React.useEffect(() => {
    if (globalRagConfig.summaryPrompt !== undefined) {
      setPromptText(globalRagConfig.summaryPrompt);
    }
  }, [globalRagConfig.summaryPrompt]);

  const handleSavePrompt = (content: string) => {
    updateGlobalRagConfig({ summaryPrompt: content });
    setPromptText(content);
    setIsEditorVisible(false);
  };

  // 这里的路由跳转到调试页
  const handleNavigateToDebug = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      router.push('/settings/rag-debug' as any);
    }, 10);
  };

  // 应用预设
  const applyPreset = (presetKey: keyof typeof PRESETS) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      updateGlobalRagConfig(PRESETS[presetKey].config);
    }, 10);
  };

  // 清空所有向量
  const handleClearAllVectors = async () => {
    setIsClearing(true);
    try {
      await vectorStore.clearAllVectors();
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      Alert.alert(t.common.success, t.rag.vectorStats.clearDataSuccess);
    } catch (error) {
      console.error('[GlobalRagConfigPanel] Clear Vectors Error:', error);
      Alert.alert(t.common.error, t.common.fail);
    } finally {
      setIsClearing(false);
      setShowClearConfirm(false);
    }
  };

  return (
    <View>
      {/* 预设选择 */}
      <SectionHeader title={t.rag.quickPresets} mt={0} />
      <View className="flex-row mb-8 gap-3">
        {(Object.keys(PRESETS) as Array<keyof typeof PRESETS>).map((key) => {
          const preset = PRESETS[key];
          const Icon = preset.icon;
          // 这里的名称也要国际化 (虽然目前PRESETS是硬编码中文)
          const name =
            key === 'balanced'
              ? t.rag.presetBalanced
              : key === 'writing'
                ? t.rag.presetWriting
                : t.rag.presetCode;

          return (
            <TouchableOpacity
              key={key}
              onPress={() => applyPreset(key)}
              activeOpacity={0.7}
              className="flex-1 rounded-2xl p-4 border items-center shadow-sm"
              style={{ backgroundColor: isDark ? 'rgba(26, 28, 46, 0.4)' : '#f9fafb', borderColor: isDark ? 'rgba(99, 102, 241, 0.15)' : '#e5e7eb' }}
            >
              <Icon size={22} color={colors[500]} />
              <Typography className="text-xs font-bold mt-2 text-gray-900 dark:text-white">
                {name}
              </Typography>
            </TouchableOpacity>
          );
        })}
      </View>

      {/* 文档分块设置 */}
      <SectionHeader title={t.rag.docChunkSettings} />
      <View
        className="bg-white/80 dark:bg-zinc-900/60 rounded-[24px] p-6 border border-indigo-50 dark:border-indigo-500/10 mb-8 shadow-sm"
      >
        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.chunkSize}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.chunkSizeDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">200</Typography>
            <Typography className="text-sm font-bold" style={{ color: colors[500] }}>
              {t.rag.chars.replace('{count}', (globalRagConfig.docChunkSize ?? 800).toString())}
            </Typography>
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">2000</Typography>
          </View>
          <Slider
            value={globalRagConfig.docChunkSize ?? 800}
            onValueChange={(val) => updateGlobalRagConfig({ docChunkSize: Math.round(val) })}
            minimumValue={200}
            maximumValue={2000}
            step={100}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

        <View>
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.chunkOverlap}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.chunkOverlapDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">0</Typography>
            <Typography className="text-sm font-bold" style={{ color: colors[500] }}>
              {t.rag.chars.replace('{count}', (globalRagConfig.chunkOverlap ?? 100).toString())}
            </Typography>
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">500</Typography>
          </View>
          <Slider
            value={globalRagConfig.chunkOverlap ?? 100}
            onValueChange={(val) => updateGlobalRagConfig({ chunkOverlap: Math.round(val) })}
            minimumValue={0}
            maximumValue={500}
            step={50}
          />
        </View>
      </View>

      {/* 对话记忆设置 */}
      <SectionHeader title={t.rag.memorySettings} />
      <View
        className="bg-white/80 dark:bg-zinc-900/60 rounded-[24px] p-6 border border-indigo-50 dark:border-indigo-500/10 mb-8 shadow-sm"
      >
        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.memoryChunkSize}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.memoryChunkSizeDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">500</Typography>
            <Typography className="text-sm font-bold" style={{ color: colors[500] }}>
              {t.rag.chars.replace('{count}', (globalRagConfig.memoryChunkSize ?? 1000).toString())}
            </Typography>
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">2000</Typography>
          </View>
          <Slider
            value={globalRagConfig.memoryChunkSize ?? 1000}
            onValueChange={(val) => updateGlobalRagConfig({ memoryChunkSize: Math.round(val) })}
            minimumValue={500}
            maximumValue={2000}
            step={100}
          />
        </View>
      </View>

      {/* 自动摘要设置 */}
      <SectionHeader title={t.rag.summarySettings} />
      <View
        className="bg-white/80 dark:bg-zinc-900/60 rounded-[24px] p-6 border border-indigo-50 dark:border-indigo-500/10 mb-8 shadow-sm"
      >
        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.activeWindow}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.activeWindowDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">10</Typography>
            <Typography className="text-sm font-bold" style={{ color: colors[500] }}>
              {t.rag.messageCount.replace(
                '{count}',
                (globalRagConfig.contextWindow ?? 20).toString(),
              )}
            </Typography>
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">50</Typography>
          </View>
          <Slider
            value={globalRagConfig.contextWindow ?? 20}
            onValueChange={(val) => updateGlobalRagConfig({ contextWindow: Math.round(val) })}
            minimumValue={10}
            maximumValue={50}
            step={5}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.triggerThreshold}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.triggerThresholdDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">5</Typography>
            <Typography className="text-sm font-bold" style={{ color: colors[500] }}>
              {t.rag.messageCount.replace(
                '{count}',
                (globalRagConfig.summaryThreshold ?? 10).toString(),
              )}
            </Typography>
            <Typography className="text-sm text-gray-400 dark:text-zinc-500">30</Typography>
          </View>
          <Slider
            value={globalRagConfig.summaryThreshold ?? 10}
            onValueChange={(val) => updateGlobalRagConfig({ summaryThreshold: Math.round(val) })}
            minimumValue={5}
            maximumValue={30}
            step={5}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-5" />

        <View>
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.summaryTemplate}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.summaryTemplateDesc}
          </Typography>
          <View className={`rounded-xl border border-dashed p-4 ${isDark ? 'bg-zinc-900/50 border-zinc-700' : 'bg-gray-50 border-gray-300'}`}>
            <View className="flex-row items-center justify-between mb-2">
              <View className="flex-row items-center">
                <Edit3 size={16} color={isDark ? '#a1a1aa' : '#64748b'} className="mr-2" />
                <Typography className="font-bold text-gray-700 dark:text-gray-300">
                  {t.rag.clickToEditSummary}
                </Typography>
              </View>
              {promptText ? (
                <View className="bg-green-100 dark:bg-green-900/30 px-2 py-0.5 rounded">
                  <Typography className="text-[10px] text-green-700 dark:text-green-400">{t.rag.configured}</Typography>
                </View>
              ) : (
                <View className="bg-gray-200 dark:bg-gray-800 px-2 py-0.5 rounded">
                  <Typography className="text-[10px] text-gray-500">{t.rag.usingDefault}</Typography>
                </View>
              )}
            </View>

            <TouchableOpacity
              onPress={() => setIsEditorVisible(true)}
              activeOpacity={0.7}
            >
              <Typography
                numberOfLines={3}
                className="text-xs text-gray-500 dark:text-gray-400 leading-5"
              >
                {promptText || t.rag.promptPlaceholder}
              </Typography>
            </TouchableOpacity>
          </View>
        </View>

        <FloatingTextEditorModal
          visible={isEditorVisible}
          initialContent={promptText}
          title={t.rag.summaryTemplate}
          placeholder={t.rag.promptPlaceholder}
          onClose={() => setIsEditorVisible(false)}
          onSave={handleSavePrompt}
        />
      </View>


      {/* 知识图谱与高级配置 (Phase 8) */}
      <SectionHeader title={t.rag.advancedRagConfig} />
      <TouchableOpacity
        onPress={() => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            router.push('/settings/rag-advanced' as any);
          }, 10);
        }}
        className="bg-white/80 dark:bg-zinc-900/60 rounded-[24px] p-4 border border-indigo-50 dark:border-indigo-500/10 mb-8 flex-row items-center justify-between shadow-sm"
      >
        <View className="flex-row items-center gap-3">
          <View
            style={{ backgroundColor: colors.opacity10 }}
            className="w-10 h-10 rounded-full items-center justify-center"
          >
            <Zap size={20} color={colors[500]} />
          </View>
          <View>
            <Typography className="text-sm font-bold text-gray-900 dark:text-white">
              {t.rag.kgCostStrategy}
            </Typography>
            <Typography className="text-xs text-gray-500">
              {t.rag.kgStrategyDesc}
            </Typography>
          </View>
        </View>
        <Typography style={{ color: colors[600] }} className="text-xs font-bold">
          {t.rag.details} &gt;
        </Typography>
      </TouchableOpacity>

      {/* 统计信息看板 */}
      <SectionHeader title={t.rag.viewVectorStats} />
      <View className="bg-white/80 dark:bg-zinc-900/60 rounded-[24px] p-6 border border-indigo-50 dark:border-indigo-500/10 mb-8 shadow-sm">
        <View className="flex-row justify-between items-center mb-6">
          <View className="flex-row items-center gap-3">
            <View
              style={{ backgroundColor: colors.opacity10 }}
              className="w-10 h-10 rounded-full items-center justify-center"
            >
              <Database size={20} color={colors[500]} />
            </View>
            <View>
              <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                {t.rag.vectorStats.title}
              </Typography>
              <Typography className="text-[10px] text-gray-500 font-medium">
                {t.rag.vectorStats.localStore}
              </Typography>
            </View>
          </View>
          <TouchableOpacity onPress={handleNavigateToDebug}>
            <Typography
              style={{ color: colors[600] }}
              className="text-xs font-bold"
            >
              {t.rag.moreDetails} &gt;
            </Typography>
          </TouchableOpacity>
        </View>

        {/* 3列数据网格 */}
        <View className="flex-row gap-4">
          {/* 文档数 */}
          <View className="flex-1 bg-gray-50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center">
            <Typography className="text-2xl font-black text-gray-900 dark:text-white mb-1">
              {vectorStats.totalDocs}
            </Typography>
            <Typography className="text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider">
              {t.rag.vectorStats.totalDocs}
            </Typography>
          </View>

          {/* 向量数 */}
          <View className="flex-1 bg-gray-50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center">
            <Typography
              style={{ color: colors[600] }}
              className="text-2xl font-black mb-1"
            >
              {vectorStats.totalVectors}
            </Typography>
            <Typography className="text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider">
              {t.rag.vectorStats.totalChunks}
            </Typography>
          </View>

          {/* 存储占用 */}
          <View className="flex-1 bg-gray-50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center">
            <Typography className="text-2xl font-black text-gray-900 dark:text-white mb-1">
              {(vectorStats.totalSize / 1024 / 1024).toFixed(1)}
            </Typography>
            <Typography className="text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider">
              {t.rag.vectorStats.storageUsage}
            </Typography>
          </View>
        </View>
        <View className="mt-4">
          <TouchableOpacity
            onPress={() => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                setShowClearConfirm(true);
              }, 10);
            }}
            disabled={isClearing}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              paddingVertical: 14,
              backgroundColor: isDark ? 'rgba(239, 68, 68, 0.1)' : '#FEF2F2',
              borderRadius: 16,
              marginBottom: 0,
              borderWidth: 1,
              borderColor: isDark ? 'rgba(239, 68, 68, 0.2)' : '#FECACA',
              opacity: isClearing ? 0.5 : 1,
            }}
          >
            <Trash2 size={18} color="#EF4444" style={{ marginRight: 8 }} />
            <Typography style={{ fontWeight: 'bold', color: '#EF4444' }}>
              {isClearing ? t.common.processing : t.rag.vectorStats.clearData}
            </Typography>
          </TouchableOpacity>

          {/* 确认对话框 */}
          <ConfirmDialog
            visible={showClearConfirm}
            title={t.rag.vectorStats.clearDataConfirmTitle}
            message={t.rag.vectorStats.clearDataConfirmMsg}
            confirmText={t.common.confirm}
            cancelText={t.common.cancel}
            onConfirm={handleClearAllVectors}
            onCancel={() => setShowClearConfirm(false)}
          />
        </View>
      </View>
    </View >
  );
};

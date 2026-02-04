import React, { useState, useMemo, useCallback } from 'react';
import { View, TouchableOpacity, TextInput, StyleSheet, Alert } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Typography, Switch, useToast, SettingsCard, SettingsSectionHeader } from '../../../components/ui';
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


// 预设配置
const PRESETS = {
  balanced: {
    name: '平衡',
    icon: Zap,
    color: '#06b6d4',
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
    color: '#f59e0b',
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
    color: '#6366f1',
    config: {
      docChunkSize: 600,
      chunkOverlap: 50,
      memoryChunkSize: 800,
      contextWindow: 15,
      summaryThreshold: 8,
    },
  },
};

import { RAG_PRESETS } from '../../../lib/rag/constants';

export const GlobalRagConfigPanel: React.FC = () => {
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const router = useRouter();
  const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();
  const { showToast } = useToast();
  const { getVectorStats, loadDocuments } = useRagStore();
  const vectorStats = getVectorStats();

  // 🛡️ 修复：进入页面时自动刷新数据，确保二级页面操作能同步回来
  useFocusEffect(
    useCallback(() => {
      loadDocuments();
      refreshKgStats();
    }, [loadDocuments])
  );

  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [showKgLinkedConfirm, setShowKgLinkedConfirm] = useState(false); // 联动提示
  const [isClearing, setIsClearing] = useState(false);
  const [kgStats, setKgStats] = useState<{ nodeCount: number; edgeCount: number }>({ nodeCount: 0, edgeCount: 0 });

  // Local state for prompt editing
  const [promptText, setPromptText] = useState(globalRagConfig.summaryPrompt || '');
  const [isEditorVisible, setIsEditorVisible] = useState(false);

  // 刷新知识图谱统计
  const refreshKgStats = async () => {
    try {
      const stats = await vectorStore.getKnowledgeGraphStats();
      setKgStats(stats);
    } catch (e) {
      console.error('[GlobalRagConfigPanel] Failed to get KG stats:', e);
    }
  };

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

  // 这里的路由导航到高级图谱配置
  const handleNavigateToAdvanced = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      router.push('/settings/rag-advanced' as any);
    }, 10);
  };

  // 这里的路由跳转到统计页
  const handleNavigateToDebug = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      router.push('/settings/rag-debug' as any);
    }, 10);
  };

  // 应用预设
  const applyPreset = (presetKey: string) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      const preset = RAG_PRESETS[presetKey];
      if (preset) {
        updateGlobalRagConfig(preset.config);
      }
    }, 10);
  };

  // 第一步：点击清空向量按钮时检查图谱状态
  const handleClearVectorRequest = async () => {
    setShowClearConfirm(false);

    // 检查是否存在知识图谱数据
    await refreshKgStats();
    if (kgStats.nodeCount > 0) {
      // 存在图谱数据，询问是否联动清理
      setShowKgLinkedConfirm(true);
    } else {
      // 无图谱数据，直接清理向量
      await doClearVectors(false);
    }
  };

  // 执行清理操作
  const doClearVectors = async (includeKg: boolean) => {
    setIsClearing(true);
    setShowKgLinkedConfirm(false);
    try {
      await vectorStore.clearAllVectors();
      if (includeKg) {
        await vectorStore.clearKnowledgeGraph();
      }
      await loadDocuments();
      await refreshKgStats();
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      showToast(
        includeKg
          ? `${t.rag.vectorStats.clearDataSuccess}；${t.rag.clearKgSuccess}`
          : t.rag.vectorStats.clearDataSuccess,
        'success'
      );
    } catch (error) {
      console.error('[GlobalRagConfigPanel] Clear Error:', error);
      showToast(t.common.fail, 'error');
    } finally {
      setIsClearing(false);
    }
  };

  return (
    <View>
      {/* 预设选择 */}
      <SettingsSectionHeader title={t.rag.quickPresets} className="mt-0" />
      <View className="flex-row mb-6 gap-3">
        {Object.entries(RAG_PRESETS).map(([key, preset]) => {
          const Icon = preset.icon;
          const isActive =
            globalRagConfig.docChunkSize === preset.config.docChunkSize &&
            globalRagConfig.memoryChunkSize === preset.config.memoryChunkSize;

          const name = (t.rag[preset.name.split('.')[1] as keyof typeof t.rag] as any) || preset.name;

          return (
            <SettingsCard
              key={key}
              className="flex-1 mb-0"
              style={
                isActive
                  ? {
                    borderColor: preset.color,
                    borderWidth: 1.5,
                  }
                  : {}
              }
              noPadding
            >
              <TouchableOpacity
                onPress={() => applyPreset(key)}
                className="p-4 items-center w-full"
                style={{
                  backgroundColor: isActive
                    ? (isDark ? `${preset.color}15` : `${preset.color}08`)
                    : 'transparent',
                }}
              >
                <Icon size={20} color={isActive ? preset.color : colors[500]} />
                <Typography
                  className={`text-xs font-bold mt-2 ${isActive ? '' : 'text-gray-900 dark:text-white'}`}
                  style={isActive ? { color: preset.color } : null}
                >
                  {name}
                </Typography>
              </TouchableOpacity>
            </SettingsCard>
          );
        })}
      </View>

      {/* 文档分块设置 */}
      <SettingsSectionHeader title={t.rag.docChunkSettings} />
      <SettingsCard className="mb-4">
        <View>
          <View className="mb-2">
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.chunkSize}
              </Typography>
              <Typography className="text-xs font-bold" style={{ color: colors[500] }}>
                {t.rag.chars.replace('{count}', (globalRagConfig.docChunkSize ?? 800).toString())}
              </Typography>
            </View>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
              {t.rag.chunkSizeDesc}
            </Typography>
            <View className="flex-row justify-between mb-0">
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">200</Typography>
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">2000</Typography>
            </View>
            <Slider
              value={globalRagConfig.docChunkSize ?? 800}
              onValueChange={(val) => updateGlobalRagConfig({ docChunkSize: Math.round(val) })}
              minimumValue={200}
              maximumValue={2000}
              step={100}
            />
          </View>

          <View className="h-[1px] bg-indigo-500/10 dark:bg-indigo-400/10 my-4" />

          <View>
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.chunkOverlap}
              </Typography>
              <Typography className="text-xs font-bold" style={{ color: colors[500] }}>
                {t.rag.chars.replace('{count}', (globalRagConfig.chunkOverlap ?? 100).toString())}
              </Typography>
            </View>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
              {t.rag.chunkOverlapDesc}
            </Typography>
            <View className="flex-row justify-between mb-0">
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">0</Typography>
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">500</Typography>
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
      </SettingsCard>

      {/* 对话记忆分块设置 */}
      <SettingsSectionHeader title={t.rag.memorySettings} />
      <SettingsCard className="mb-4">
        <View>
          <View className="mb-2">
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.memoryChunkSize}
              </Typography>
              <Typography className="text-xs font-bold" style={{ color: colors[500] }}>
                {t.rag.chars.replace('{count}', (globalRagConfig.memoryChunkSize ?? 1000).toString())}
              </Typography>
            </View>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
              {t.rag.memoryChunkSizeDesc}
            </Typography>
            <View className="flex-row justify-between mb-0">
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">500</Typography>
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">2000</Typography>
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
      </SettingsCard>

      {/* 摘要设置 (上下文窗口) */}
      <SettingsSectionHeader title={t.rag.summarySettings} />
      <SettingsCard className="mb-4">
        <View>
          <View className="mb-2">
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.activeWindow}
              </Typography>
              <Typography className="text-xs font-bold" style={{ color: colors[500] }}>
                {t.rag.messageCount.replace(
                  '{count}',
                  (globalRagConfig.contextWindow ?? 20).toString(),
                )}
              </Typography>
            </View>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
              {t.rag.activeWindowDesc}
            </Typography>
            <View className="flex-row justify-between mb-0">
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">10</Typography>
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">50</Typography>
            </View>
            <Slider
              value={globalRagConfig.contextWindow ?? 20}
              onValueChange={(val) => updateGlobalRagConfig({ contextWindow: Math.round(val) })}
              minimumValue={10}
              maximumValue={50}
              step={5}
            />
          </View>

          <View className="h-[1px] bg-indigo-500/10 dark:bg-indigo-400/10 my-4" />

          <View className="mb-2">
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.triggerThreshold}
              </Typography>
              <Typography className="text-xs font-bold" style={{ color: colors[500] }}>
                {t.rag.messageCount.replace(
                  '{count}',
                  (globalRagConfig.summaryThreshold ?? 10).toString(),
                )}
              </Typography>
            </View>
            <Typography className="text-[10px] text-gray-500 dark:text-gray-400 mb-1">
              {t.rag.triggerThresholdDesc}
            </Typography>
            <View className="flex-row justify-between mb-0">
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">5</Typography>
              <Typography className="text-[10px] text-gray-400 dark:text-zinc-500">30</Typography>
            </View>
            <Slider
              value={globalRagConfig.summaryThreshold ?? 10}
              onValueChange={(val) => updateGlobalRagConfig({ summaryThreshold: Math.round(val) })}
              minimumValue={5}
              maximumValue={30}
              step={5}
            />
          </View>

          <View className="h-[1px] bg-indigo-500/10 dark:bg-indigo-400/10 my-5" />

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
        </View>
      </SettingsCard>

      {/* 知识图谱与高级配置 */}
      <SettingsSectionHeader title={t.rag.advancedRagConfig || '高级配置'} />
      <SettingsCard className="mb-4" noPadding>
        <TouchableOpacity onPress={handleNavigateToAdvanced} className="p-4 flex-row items-center justify-between">
          <View className="flex-row items-center gap-3">
            <View style={{ backgroundColor: colors.opacity10 }} className="w-10 h-10 rounded-full items-center justify-center">
              <Zap size={20} color={colors[500]} />
            </View>
            <View>
              <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                {t.rag.kgCostStrategy || '图谱成本策略'}
              </Typography>
              <Typography className="text-xs text-gray-500">
                {t.rag.kgStrategyDesc || '调整知识抽取与存储的权衡'}
              </Typography>
            </View>
          </View>
          <Typography style={{ color: colors[600] }} className="text-xs font-bold">
            {t.rag.details || '详情'} &gt;
          </Typography>
        </TouchableOpacity>
      </SettingsCard>

      {/* 统计信息看板 */}
      <SettingsSectionHeader title={t.rag.viewVectorStats} />
      <SettingsCard className="mb-6">
        <View>
          <View className="flex-row justify-between items-center mb-6">
            <View className="flex-row items-center gap-3">
              <View style={{ backgroundColor: colors.opacity10 }} className="w-10 h-10 rounded-full items-center justify-center">
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
              <Typography style={{ color: colors[600] }} className="text-xs font-bold">
                {t.rag.moreDetails} &gt;
              </Typography>
            </TouchableOpacity>
          </View>

          <View className="flex-row gap-4 mb-6">
            <View className="flex-1 bg-gray-50/50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center border border-indigo-50 dark:border-indigo-400/10">
              <Typography className="text-2xl font-black text-gray-900 dark:text-white mb-1">
                {vectorStats.totalDocs}
              </Typography>
              <Typography className="text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider">
                {t.rag.vectorStats.totalDocs}
              </Typography>
            </View>
            <View className="flex-1 bg-gray-50/50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center border border-indigo-50 dark:border-indigo-400/10">
              <Typography style={{ color: colors[600] }} className="text-2xl font-black mb-1">
                {vectorStats.totalVectors}
              </Typography>
              <Typography className="text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider">
                {t.rag.vectorStats.totalChunks}
              </Typography>
            </View>
            <View className="flex-1 bg-gray-50/50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center border border-indigo-50 dark:border-indigo-400/10">
              <Typography className="text-2xl font-black text-gray-900 dark:text-white mb-1">
                {(vectorStats.totalSize / 1024 / 1024).toFixed(1)}
              </Typography>
              <Typography className="text-[10px] font-bold text-gray-400 dark:text-zinc-500 uppercase tracking-wider">
                MB
              </Typography>
            </View>
          </View>

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
        </View>
      </SettingsCard>

      {/* Modals */}
      <ConfirmDialog
        visible={showClearConfirm}
        title={t.rag.vectorStats.clearDataConfirmTitle}
        message={t.rag.vectorStats.clearDataConfirmMsg}
        confirmText={t.common.confirm}
        cancelText={t.common.cancel}
        onConfirm={handleClearVectorRequest}
        onCancel={() => setShowClearConfirm(false)}
      />
      {/* 联动清理图谱确认弹窗 */}
      <ConfirmDialog
        visible={showKgLinkedConfirm}
        title={t.rag.clearKgConfirmTitle || '知识图谱'}
        message={(t.rag.kgLinkedPrompt || '检测到知识图谱中有 {nodes} 个节点。是否同时清空？').replace('{nodes}', String(kgStats.nodeCount))}
        confirmText={t.rag.clearBoth || '一并清空'}
        cancelText={t.rag.clearVectorOnly || '仅清空向量'}
        onConfirm={() => doClearVectors(true)}
        onCancel={() => doClearVectors(false)}
      />
      <FloatingTextEditorModal
        visible={isEditorVisible}
        initialContent={promptText}
        title={t.rag.summaryTemplate}
        placeholder={t.rag.promptPlaceholder}
        onClose={() => setIsEditorVisible(false)}
        onSave={handleSavePrompt}
      />
    </View>
  );
};

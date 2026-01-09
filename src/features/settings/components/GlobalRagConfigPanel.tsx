import React, { useState } from 'react';
import { View, TouchableOpacity, TextInput, StyleSheet, Alert } from 'react-native';
import { Typography, Switch } from '../../../components/ui';
import { useSettingsStore } from '../../../store/settings-store';
import { useRagStore } from '../../../store/rag-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import Slider from '@react-native-community/slider';
import { Database, Zap, BookOpen, Code, Trash2 } from 'lucide-react-native';
import { useRouter } from 'expo-router';
import * as Haptics from '../../../lib/haptics';
import { Colors } from '../../../theme/colors';
import { VectorStore } from '../../../lib/rag/vector-store';
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog';

// 装饰性的小标题组件
// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => {
  const { isDark } = useTheme();
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
          backgroundColor: Colors.primary,
        }}
      />
      <Typography
        style={{
          fontSize: 14,
          fontWeight: 'bold',
          textTransform: 'uppercase',
          letterSpacing: 0.5,
          color: isDark ? Colors.dark.textPrimary : Colors.light.textPrimary,
        }}
      >
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
  const { isDark } = useTheme();
  const themeColors = isDark ? Colors.dark : Colors.light;
  const { t } = useI18n();
  const router = useRouter();
  const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();
  const { getVectorStats } = useRagStore();
  const vectorStats = getVectorStats();
  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [isClearing, setIsClearing] = useState(false);

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

  const handleClearAllVectors = async () => {
    try {
      setIsClearing(true);
      const vectorStore = new VectorStore();
      await vectorStore.clearAllVectors();

      setTimeout(() => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        Alert.alert('✅ 清空成功', '所有向量数据已被清除', [{ text: '确定' }]);
      }, 10);
    } catch (error) {
      console.error('Failed to clear vectors:', error);
      setTimeout(() => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        Alert.alert('❌ 清空失败', '清除向量数据时出错，请重试', [{ text: '确定' }]);
      }, 10);
    } finally {
      setIsClearing(false);
      setShowClearConfirm(false);
    }
  };

  return (
    <View>
      {/* 快速预设 */}
      <SectionHeader title={t.rag.quickPresets} mt={0} />
      <View style={{ flexDirection: 'row', marginBottom: 32, gap: 12 }}>
        {(Object.keys(PRESETS) as Array<keyof typeof PRESETS>).map((key) => {
          const preset = PRESETS[key];
          const Icon = preset.icon;
          // Map preset keys to i18n keys
          const presetName =
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
              style={{
                flex: 1,
                backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#fff',
                borderRadius: 16,
                padding: 16,
                borderWidth: 1,
                borderColor: themeColors.borderDefault,
                alignItems: 'center',
                // Shadow for iOS
                shadowColor: '#000',
                shadowOffset: { width: 0, height: 1 },
                shadowOpacity: 0.05,
                shadowRadius: 2,
                // Elevation for Android
                elevation: 2,
              }}
            >
              <Icon size={22} color={Colors.primary} />
              <Typography
                style={{
                  fontSize: 12,
                  fontWeight: 'bold',
                  marginTop: 8,
                  color: themeColors.textPrimary,
                }}
              >
                {presetName}
              </Typography>
            </TouchableOpacity>
          );
        })}
      </View>

      {/* 文档切块设置 */}
      <SectionHeader title={t.rag.docChunkSettings} />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.chunkSize}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.chunkSizeDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">400</Typography>
            <Typography className="text-sm font-bold" style={{ color: Colors.primary }}>
              {t.rag.chars.replace('{count}', (globalRagConfig.docChunkSize ?? 800).toString())}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">2000</Typography>
          </View>
          <Slider
            value={globalRagConfig.docChunkSize ?? 800}
            onValueChange={(val) => updateGlobalRagConfig({ docChunkSize: Math.round(val) })}
            minimumValue={400}
            maximumValue={2000}
            step={100}
            minimumTrackTintColor={Colors.primary}
            maximumTrackTintColor={themeColors.surfaceSecondary}
            thumbTintColor={Colors.primary}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

        <View className="mt-2">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.chunkOverlap}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.chunkOverlapDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">0</Typography>
            <Typography className="text-sm font-bold" style={{ color: Colors.primary }}>
              {t.rag.chars.replace('{count}', (globalRagConfig.chunkOverlap ?? 100).toString())}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">500</Typography>
          </View>
          <Slider
            value={globalRagConfig.chunkOverlap ?? 100}
            onValueChange={(val) => updateGlobalRagConfig({ chunkOverlap: Math.round(val) })}
            minimumValue={0}
            maximumValue={500}
            step={10}
            minimumTrackTintColor={Colors.primary}
            maximumTrackTintColor={themeColors.surfaceSecondary}
            thumbTintColor={Colors.primary}
          />
        </View>
      </View>

      {/* 对话记忆设置 */}
      <SectionHeader title={t.rag.memorySettings} />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        <View>
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.memoryChunkSize}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.memoryChunkSizeDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">500</Typography>
            <Typography className="text-sm font-bold" style={{ color: Colors.primary }}>
              {t.rag.chars.replace('{count}', (globalRagConfig.memoryChunkSize ?? 1000).toString())}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">2000</Typography>
          </View>
          <Slider
            value={globalRagConfig.memoryChunkSize ?? 1000}
            onValueChange={(val) => updateGlobalRagConfig({ memoryChunkSize: Math.round(val) })}
            minimumValue={500}
            maximumValue={2000}
            step={100}
            minimumTrackTintColor={Colors.primary}
            maximumTrackTintColor={themeColors.surfaceSecondary}
            thumbTintColor={Colors.primary}
          />
        </View>
      </View>

      {/* 自动摘要设置 */}
      <SectionHeader title={t.rag.summarySettings} />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        <View className="mb-4">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.activeWindow}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.activeWindowDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">10</Typography>
            <Typography className="text-sm font-bold" style={{ color: Colors.primary }}>
              {t.rag.messageCount.replace(
                '{count}',
                (globalRagConfig.contextWindow ?? 20).toString(),
              )}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">50</Typography>
          </View>
          <Slider
            value={globalRagConfig.contextWindow ?? 20}
            onValueChange={(val) => updateGlobalRagConfig({ contextWindow: Math.round(val) })}
            minimumValue={10}
            maximumValue={50}
            step={5}
            minimumTrackTintColor={Colors.primary}
            maximumTrackTintColor={themeColors.surfaceSecondary}
            thumbTintColor={Colors.primary}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

        <View className="mt-2">
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.triggerThreshold}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.triggerThresholdDesc}
          </Typography>
          <View className="flex-row justify-between mb-2">
            <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
            <Typography className="text-sm font-bold text-indigo-600 dark:text-indigo-400">
              {t.rag.messageCount.replace(
                '{count}',
                (globalRagConfig.summaryThreshold ?? 10).toString(),
              )}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">30</Typography>
          </View>
          <Slider
            value={globalRagConfig.summaryThreshold ?? 10}
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
            {t.rag.summaryTemplate}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.summaryTemplateDesc}
          </Typography>
          <TextInput
            value={globalRagConfig.summaryPrompt}
            onChangeText={(text) => updateGlobalRagConfig({ summaryPrompt: text })}
            multiline
            numberOfLines={4}
            className="text-gray-600 dark:text-gray-300 bg-gray-50 dark:bg-black p-4 rounded-2xl border border-gray-100 dark:border-zinc-800"
            style={{ textAlignVertical: 'top', minHeight: 100 }}
            placeholderTextColor="#94a3b8"
            placeholder={t.rag.promptPlaceholder}
          />
        </View>
      </View>


      {/* 知识图谱与高级配置 (Phase 8) */}
      <SectionHeader title="高级知识配置" />
      <TouchableOpacity
        onPress={() => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            router.push('/settings/rag-advanced' as any);
          }, 10);
        }}
        className="bg-white dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800 mb-8 flex-row items-center justify-between"
      >
        <View className="flex-row items-center gap-3">
          <View className="w-10 h-10 rounded-full bg-purple-50 dark:bg-purple-900/20 items-center justify-center">
            <Zap size={20} color="#a855f7" />
          </View>
          <View>
            <Typography className="text-sm font-bold text-gray-900 dark:text-white">
              知识图谱 & 降本策略
            </Typography>
            <Typography className="text-xs text-gray-500">
              配置实体抽取、提示词与增量更新策略
            </Typography>
          </View>
        </View>
        <Typography className="text-xs font-bold text-purple-600 dark:text-purple-400">
          配置 &gt;
        </Typography>
      </TouchableOpacity>

      {/* 统计信息看板 */}

      {/* 统计信息看板 */}
      <SectionHeader title={t.rag.viewVectorStats} />
      <View className="bg-white dark:bg-zinc-900 rounded-[24px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        <View className="flex-row justify-between items-center mb-6">
          <View className="flex-row items-center gap-3">
            <View className="w-10 h-10 rounded-full bg-indigo-50 dark:bg-indigo-900/20 items-center justify-center">
              <Database size={20} color="#6366f1" />
            </View>
            <View>
              <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                向量库状态
              </Typography>
              <Typography className="text-[10px] text-gray-500 font-medium">
                Local Vector Store
              </Typography>
            </View>
          </View>
          <TouchableOpacity onPress={handleNavigateToDebug}>
            <Typography className="text-xs font-bold text-indigo-600 dark:text-indigo-400">
              详细调试 &gt;
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
            <Typography className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
              文档总数
            </Typography>
          </View>

          {/* 向量数 */}
          <View className="flex-1 bg-gray-50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center">
            <Typography className="text-2xl font-black text-indigo-600 dark:text-indigo-400 mb-1">
              {vectorStats.totalVectors}
            </Typography>
            <Typography className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
              向量片段
            </Typography>
          </View>

          {/* 存储占用 */}
          <View className="flex-1 bg-gray-50 dark:bg-zinc-800/50 rounded-2xl p-4 items-center">
            <Typography className="text-2xl font-black text-gray-900 dark:text-white mb-1">
              {(vectorStats.totalSize / 1024 / 1024).toFixed(1)}
            </Typography>
            <Typography className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
              MB 占用
            </Typography>
          </View>
        </View>
      </View>

      {/* 危险操作：清空向量库 */}
      <TouchableOpacity
        activeOpacity={0.7}
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
          paddingVertical: 16,
          backgroundColor: isDark ? 'rgba(239, 68, 68, 0.1)' : '#FEF2F2',
          borderRadius: 16,
          marginBottom: 32,
          borderWidth: 1,
          borderColor: isDark ? 'rgba(239, 68, 68, 0.2)' : '#FECACA',
          opacity: isClearing ? 0.5 : 1,
        }}
      >
        <Trash2 size={18} color="#EF4444" style={{ marginRight: 8 }} />
        <Typography style={{ fontWeight: 'bold', color: '#EF4444' }}>
          {isClearing ? '清空中...' : '清空所有向量数据'}
        </Typography>
      </TouchableOpacity>

      {/* 确认对话框 */}
      <ConfirmDialog
        visible={showClearConfirm}
        title="危险操作"
        message="确定要清空所有向量数据吗？此操作不可恢复！"
        confirmText="确认清空"
        cancelText="取消"
        onConfirm={handleClearAllVectors}
        onCancel={() => setShowClearConfirm(false)}
      />
    </View>
  );
};

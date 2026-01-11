import React, { useState } from 'react';
import { View, TouchableOpacity, TextInput } from 'react-native';
import { Typography, ConfirmDialog, Switch } from '../../../components/ui';
import { ThemedSlider as Slider } from '../../../components/ui/Slider';
import { useSettingsStore } from '../../../store/settings-store';
import { useI18n } from '../../../lib/i18n';
import { useTheme } from '../../../theme/ThemeProvider';
import { Agent, RagConfiguration } from '../../../types/chat';
import { RefreshCw, Zap, BookOpen, Code, Edit3 } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { FloatingTextEditorModal } from '../../../components/ui/FloatingTextEditorModal';

interface Props {
  agent: Agent;
  onUpdate: (updates: Partial<Agent>) => void;
}

const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 12 }) => {
  const { colors } = useTheme();
  return (
    <View style={{ marginTop: mt }} className="flex-row items-center mb-4 mt-2">
      <View style={{ backgroundColor: colors[500] }} className="w-1 h-4 rounded-full mr-2" />
      <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
        {title}
      </Typography>
    </View>
  );
};

const PRESETS = {
  balanced: {
    name: '平衡',
    icon: Zap,
    config: {
      contextWindow: 20,
      summaryThreshold: 10,
      memoryLimit: 5,
      memoryThreshold: 0.7,
      docLimit: 8,
      docThreshold: 0.45,
    },
  },
  writing: {
    name: '写作',
    icon: BookOpen,
    config: {
      contextWindow: 30,
      summaryThreshold: 15,
      memoryLimit: 7,
      memoryThreshold: 0.75,
      docLimit: 10,
      docThreshold: 0.5,
    },
  },
  coding: {
    name: '代码',
    icon: Code,
    config: {
      contextWindow: 15,
      summaryThreshold: 8,
      memoryLimit: 4,
      memoryThreshold: 0.65,
      docLimit: 6,
      docThreshold: 0.4,
    },
  },
};

export const AgentRagConfigPanel: React.FC<Props> = ({ agent, onUpdate }) => {
  const { t } = useI18n();
  const { colors, isDark } = useTheme();
  const globalConfig = useSettingsStore((s) => s.globalRagConfig);
  const [showResetDialog, setShowResetDialog] = useState(false);
  const [isEditorVisible, setIsEditorVisible] = useState(false);

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
      newConfig,
    });
    onUpdate({ ragConfig: newConfig });
  };

  return (
    <View>
      {/* 状态标签 */}
      <SectionHeader title={t.rag.configStatus} mt={0} />
      <View className="bg-white dark:bg-zinc-900 rounded-[32px] p-6 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm">
        <View className="flex-row items-center justify-between">
          <View>
            <Typography className="text-base font-bold text-gray-900 dark:text-white mb-1">
              {t.rag.configMode}
            </Typography>
            <Typography
              className="text-sm font-medium"
              style={{ color: isUsingGlobal ? (isDark ? '#34d399' : '#059669') : colors[500] }}
            >
              {isUsingGlobal ? t.rag.modeInherit : t.rag.modeCustom}
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
                {t.rag.reset}
              </Typography>
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* 预设快捷选择 */}
      <SectionHeader title={t.rag.quickPresets} />
      <View className="flex-row mb-8 gap-3">
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
              className="flex-1 bg-white dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800 items-center shadow-sm"
            >
              <Icon size={22} color={colors[500]} />
              <Typography className="text-xs font-bold mt-2 text-gray-900 dark:text-white">
                {presetName}
              </Typography>
            </TouchableOpacity>
          );
        })}
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
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {t.rag.messageCount.replace(
                '{count}',
                (currentConfig.contextWindow ?? 20).toString(),
              )}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">50</Typography>
          </View>
          <Slider
            value={currentConfig.contextWindow ?? 20}
            onValueChange={(val) => handleChange({ contextWindow: Math.round(val) })}
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
            <Typography className="text-sm text-gray-600 dark:text-gray-400">5</Typography>
            <Typography style={{ color: colors[600] }} className="text-sm font-bold">
              {t.rag.messageCount.replace(
                '{count}',
                (currentConfig.summaryThreshold ?? 10).toString(),
              )}
            </Typography>
            <Typography className="text-sm text-gray-600 dark:text-gray-400">30</Typography>
          </View>
          <Slider
            value={currentConfig.summaryThreshold ?? 10}
            onValueChange={(val) => handleChange({ summaryThreshold: Math.round(val) })}
            minimumValue={5}
            maximumValue={30}
            step={5}
          />
        </View>

        <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

        <View>
          <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-1">
            {t.rag.summaryTemplate}
          </Typography>
          <Typography className="text-xs text-gray-500 dark:text-gray-400 mb-3">
            {t.rag.agentSummaryTemplateDesc}
          </Typography>
          <View className={`rounded-xl border border-dashed p-4 ${isDark ? 'bg-zinc-900/50 border-zinc-700' : 'bg-gray-50 border-gray-300'}`}>
            <View className="flex-row items-center justify-between mb-2">
              <View className="flex-row items-center">
                <Edit3 size={16} color={isDark ? '#a1a1aa' : '#64748b'} className="mr-2" />
                <Typography className="font-bold text-gray-700 dark:text-gray-300">
                  {t.rag.clickToEditPrompt}
                </Typography>
              </View>
              {currentConfig.summaryPrompt ? (
                <View className="bg-green-100 dark:bg-green-900/30 px-2 py-0.5 rounded">
                  <Typography className="text-[10px] text-green-700 dark:text-green-400">
                    {t.rag.configured}
                  </Typography>
                </View>
              ) : (
                <View className="bg-gray-200 dark:bg-gray-800 px-2 py-0.5 rounded">
                  <Typography className="text-[10px] text-gray-500">
                    {t.rag.usingDefault}
                  </Typography>
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
                {currentConfig.summaryPrompt || t.rag.promptPlaceholder}
              </Typography>
            </TouchableOpacity>
          </View>
        </View>
      </View>

      <FloatingTextEditorModal
        visible={isEditorVisible}
        initialContent={currentConfig.summaryPrompt || ''}
        title={t.rag.summaryTemplate}
        placeholder={t.rag.promptPlaceholder}
        onClose={() => setIsEditorVisible(false)}
        onSave={(text) => {
          handleChange({ summaryPrompt: text });
          setIsEditorVisible(false);
        }}
      />



      {/* 重置确认对话框 */}
      <ConfirmDialog
        visible={showResetDialog}
        title={t.rag.resetConfirmTitle}
        message={t.rag.resetConfirmMessage}
        confirmText={t.common.confirm}
        cancelText={t.common.cancel}
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

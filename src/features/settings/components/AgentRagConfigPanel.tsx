import React, { useState } from 'react';
import { View, TouchableOpacity, TextInput } from 'react-native';
import { Typography, ConfirmDialog, Switch, SettingsCard, SettingsSectionHeader } from '../../../components/ui';
import { ThemedSlider as Slider } from '../../../components/ui/Slider';
import { useSettingsStore } from '../../../store/settings-store';
import { useI18n } from '../../../lib/i18n';
import { useTheme } from '../../../theme/ThemeProvider';
import { Agent, RagConfiguration } from '../../../types/chat';
import { RefreshCw, Zap, BookOpen, Code, Edit3 } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { FloatingTextEditorModal } from '../../../components/ui/FloatingTextEditorModal';
import { Card } from '../../../components/ui/Card';

interface Props {
  agent: Agent;
  onUpdate: (updates: Partial<Agent>) => void;
}



import { RAG_PRESETS } from '../../../lib/rag/constants';

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
  const applyPreset = (presetKey: string) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      const preset = RAG_PRESETS[presetKey];
      if (preset) {
        const newConfig = { ...(agent.ragConfig || globalConfig), ...preset.config };
        onUpdate({ ragConfig: newConfig });
      }
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
      <SettingsSectionHeader title={t.rag.configStatus} className="mt-0" />
      <SettingsCard className="flex-row items-center justify-between">
        <View>
          <Typography className="text-sm font-bold text-gray-900 dark:text-white mb-1">
            {t.rag.configMode}
          </Typography>
          <Typography
            className="text-xs font-medium"
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
      </SettingsCard>

      {/* 预设快捷选择 */}
      <SettingsSectionHeader title={t.rag.quickPresets} />
      <View className="flex-row mb-3 gap-2">
        {Object.entries(RAG_PRESETS).map(([key, preset]) => {
          const Icon = preset.icon;
          // Use i18n key from preset
          const presetName = (t.rag[preset.name.split('.')[1] as keyof typeof t.rag] as any) || preset.name;

          const isActive =
            currentConfig.memoryLimit === preset.config.memoryLimit &&
            currentConfig.docLimit === preset.config.docLimit &&
            currentConfig.contextWindow === preset.config.contextWindow;

          return (
            <SettingsCard
              key={key}
              // SettingsCard is View, need Touchable if clickable? SettingsCard passes props to View. 
              // Wait, SettingsCard returns View. If I want Touchable, I should wrap children or make SettingsCard accept onPress? 
              // ViewProps doesn't have onPress. 
              // I should wrap SettingsCard in TouchableOpacity or keep Card implementation for clickable cards.
              // Existing Card accepts onPress.
              // I will use TouchableOpacity wrapping SettingsCard content or just use SettingsCard as visual and wrap it.
              // OR better: keep using `Card` for clickable grids IF `SettingsCard` doesn't support it, 
              // BUT `SettingsCard` was requested for standard style.
              // Let's modify SettingsCard usage.
              // Actually, I can just use SettingsCard style but wrap in Touchable.
              // Or better, since `Card` component is generic, maybe I should just update `Card` variant="glass" to match SettingsCard style globally?
              // No, user specifically asked for standardization in these pages.
              // I'll make the preset cards SettingsCards. 
              // Since SettingsCard is a View, I will wrap the content in TouchableOpacity inside SettingsCard? 
              // No, the Card itself is clickable.
              // I will wrap SettingsCard in TouchableOpacity.
              // <TouchableOpacity onPress={...}><SettingsCard ... pointerEvents="box-only"?/></TouchableOpacity>
              // SettingsCard has mb-3.
              // Flex row container.
              className="flex-1 mb-0" // override mb-3 since they are in a row container
              style={
                isActive
                  ? {
                    borderColor: colors[500],
                    borderWidth: 1.5,
                  }
                  : {}
              }
              noPadding
            >
              <TouchableOpacity
                activeOpacity={0.7}
                onPress={() => applyPreset(key)}
                className="p-4 items-center w-full"
                style={{
                  backgroundColor: isActive
                    ? (isDark ? `${colors[500]}15` : `${colors[500]}08`)
                    : 'transparent',
                }}
              >
                <Icon size={20} color={isActive ? colors[500] : colors[400]} />
                <Typography
                  className={`text-xs font-bold mt-2 ${isActive ? '' : 'text-gray-500 dark:text-gray-400'}`}
                  style={isActive ? { color: colors[500] } : null}
                >
                  {presetName}
                </Typography>
              </TouchableOpacity>
            </SettingsCard>
          );
        })}
      </View>

      {/* 自动摘要设置 */}
      <SettingsSectionHeader title={t.rag.summarySettings} />
      <SettingsCard>
        <View>
          <View className="mb-2">
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.activeWindow}
              </Typography>
              <Typography style={{ color: colors[600] }} className="text-xs font-bold">
                {t.rag.messageCount.replace(
                  '{count}',
                  (currentConfig.contextWindow ?? 20).toString(),
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
              value={currentConfig.contextWindow ?? 20}
              onValueChange={(val) => handleChange({ contextWindow: Math.round(val) })}
              minimumValue={10}
              maximumValue={50}
              step={5}
            />
          </View>

          <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

          <View className="mb-2">
            <View className="flex-row items-center justify-between mb-1">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.triggerThreshold}
              </Typography>
              <Typography style={{ color: colors[600] }} className="text-xs font-bold">
                {t.rag.messageCount.replace(
                  '{count}',
                  (currentConfig.summaryThreshold ?? 10).toString(),
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
      </SettingsCard>

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
    </View >
  );
};

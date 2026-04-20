import React, { useState, useMemo } from 'react';
import {
  View,
  TextInput,
  ScrollView,
  TouchableOpacity,
  Alert,
} from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { Stack, useRouter } from 'expo-router';
import { Typography, Switch, PageLayout, GlassHeader, Card } from '../../../components/ui';
import { SettingsSection } from '../components/SettingsSection';
import { SettingsItem } from '../components/SettingsItem';
import { SettingsSwitchItem } from '../components/SettingsSwitchItem';
import { useSettingsStore } from '../../../store/settings-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import { Colors } from '../../../theme/colors';
import {
  Network,
  Cpu,
  DollarSign,
  Edit3,
  ChevronLeft,
  Bot,
  RotateCcw,
  AlertTriangle,
} from 'lucide-react-native';
import { useApiStore } from '../../../store/api-store';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { FloatingTextEditorModal } from '../../../components/ui/FloatingTextEditorModal';
import { ModelPicker } from '../ModelPicker';

export default function RagAdvancedSettings() {
  const { isDark, colors } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();
  const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();
  const { providers } = useApiStore();
  const [modelModalVisible, setModelModalVisible] = useState(false);

  const selectedModelName = useMemo(() => {
    if (!globalRagConfig.kgExtractionModel) return null;
    for (const p of providers) {
      const m = p.models.find((m) => m.id === globalRagConfig.kgExtractionModel);
      if (m) return m.name;
    }
    return globalRagConfig.kgExtractionModel;
  }, [globalRagConfig.kgExtractionModel, providers]);

  // Local state for prompt editing (to avoid heavy store updates on every keystroke)
  const [promptText, setPromptText] = useState(globalRagConfig.kgExtractionPrompt || '');
  const [isEditorVisible, setIsEditorVisible] = useState(false);

  // Sync with store when config changes externally
  React.useEffect(() => {
    if (globalRagConfig.kgExtractionPrompt !== undefined) {
      setPromptText(globalRagConfig.kgExtractionPrompt);
    }
  }, [globalRagConfig.kgExtractionPrompt]);

  const handleSavePrompt = (content: string) => {
    // Validation
    if (!content.trim()) {
      Alert.alert(t.common.error, t.rag.kg.promptEmptyError);
      return;
    }

    // Warning Checks
    const issues: string[] = [];
    if (!content.includes('{entityTypes}')) {
      issues.push('- ' + t.rag.kg.missingPlaceholder.replace('{placeholder}', '{entityTypes}'));
    }
    if (!content.toLowerCase().includes('json')) {
      issues.push('- ' + t.rag.kg.missingJsonFormat);
    }

    const save = () => {
      updateGlobalRagConfig({ kgExtractionPrompt: content });
      setPromptText(content);
      setIsEditorVisible(false);
      Alert.alert(t.rag.kg.saveChanges, t.rag.kg.editPromptTitle);
    };

    if (issues.length > 0) {
      Alert.alert(
        t.rag.kg.promptWarning,
        issues.join('\n') +
        '\n\n' + t.common.confirm + '?',
        [
          { text: t.common.cancel, style: 'cancel' },
          { text: t.common.save, style: 'destructive', onPress: save },
        ]
      );
    } else {
      save();
    }
  };

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.rag.kg.title}
        subtitle="KNOWLEDGE GRAPH"
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
      />

      <KeyboardAvoidingView
        behavior="padding"
        className="flex-1"
      >
        <ScrollView
          className="flex-1 px-6"
          contentContainerStyle={{
            paddingTop: 74 + insets.top,
            paddingBottom: 40,
          }}
          showsVerticalScrollIndicator={false}
        >
          {/* 0. 知识图谱总开关 */}
          <SettingsSection title={t.rag.kg.title}>
            <SettingsSwitchItem
              icon={Network}
              title={t.rag.kg.enable}
              subtitle={t.rag.kg.enableDesc}
              value={!!globalRagConfig.enableKnowledgeGraph}
              onValueChange={(v) => updateGlobalRagConfig({ enableKnowledgeGraph: v })}
            />
            {/* Model Selection - Conditionally styled or disabled if KG disabled? keeping it visible as per original */}
            <SettingsItem
              icon={Bot}
              title={t.rag.kg.extractionModel}
              subtitle={selectedModelName
                ? t.rag.kg.currentModel.replace('{name}', selectedModelName)
                : t.rag.kg.extractionModelDefault}
              onPress={() => globalRagConfig.enableKnowledgeGraph && setModelModalVisible(true)}
              showChevron
              isLast
              rightElement={
                <Typography style={{ color: colors[600], opacity: globalRagConfig.enableKnowledgeGraph ? 1 : 0.5 }} className="text-xs font-bold mr-1">
                  {t.rag.kg.change}
                </Typography>
              }
            />
          </SettingsSection>

          {/* New: JIT Micro-Graph Configuration */}
          <SettingsSection title={t.rag.kg.jitTitle}>
            <SettingsSwitchItem
              icon={Cpu}
              title={t.rag.kg.jitEnable}
              subtitle={t.rag.kg.jitEnableDesc}
              value={!!globalRagConfig.jitMaxChunks && globalRagConfig.jitMaxChunks > 0}
              onValueChange={(v) => updateGlobalRagConfig({ jitMaxChunks: v ? 3 : 0 })}
            />
            {!!globalRagConfig.jitMaxChunks && globalRagConfig.jitMaxChunks > 0 && (
              <SettingsItem
                icon={Bot}
                title={t.rag.kg.jitMaxChunks}
                subtitle={t.rag.kg.jitMaxChunksDesc}
                rightElement={
                  <TextInput
                    keyboardType="numeric"
                    style={{ color: Colors.primary, fontWeight: 'bold', width: 40, textAlign: 'right' }}
                    defaultValue={String(globalRagConfig.jitMaxChunks || 3)}
                    onEndEditing={(e) => {
                       const val = parseInt(e.nativeEvent.text);
                       if (!isNaN(val)) updateGlobalRagConfig({ jitMaxChunks: val });
                    }}
                  />
                }
              />
            )}
            <SettingsSwitchItem
              icon={Cpu}
              title={t.rag.kg.freeModeEnable}
              subtitle={t.rag.kg.freeModeDesc}
              value={!!globalRagConfig.kgFreeMode}
              onValueChange={(v) => updateGlobalRagConfig({ kgFreeMode: v })}
            />
            <SettingsSwitchItem
              icon={Network}
              title={t.rag.kg.domainAuto}
              subtitle={t.rag.kg.domainAutoDesc}
              value={!!globalRagConfig.kgDomainAuto}
              onValueChange={(v) => updateGlobalRagConfig({ kgDomainAuto: v })}
              isLast
            />
          </SettingsSection>

          {/* 1. 策略选择 */}
          <SettingsSection title={t.rag.kg.costStrategyTitle}>
            <View className="p-3">
              <View className="flex-row items-center mb-4">
                <DollarSign size={20} color={colors[500]} style={{ marginRight: 8 }} />
                <View style={{ flex: 1 }}>
                  <Typography className="text-base font-bold text-gray-900 dark:text-white">
                    {t.rag.kg.costStrategyTitle}
                  </Typography>
                  <Typography variant="caption" className="text-gray-500 dark:text-gray-400">
                    {t.rag.kg.costStrategyDesc}
                  </Typography>
                </View>
              </View>

              {/* Radio Options - Custom UI inside Card */}
              {[
                {
                  key: 'summary-first',
                  label: t.rag.kg.summaryFirst,
                  desc: t.rag.kg.summaryFirstDesc,
                  color: colors[500],
                },
                {
                  key: 'on-demand',
                  label: t.rag.kg.onDemand,
                  desc: t.rag.kg.onDemandDesc,
                  color: colors[400],
                },
                {
                  key: 'full',
                  label: t.rag.kg.fullScan,
                  desc: t.rag.kg.fullScanDesc,
                  color: '#ef4444',
                },
              ].map((opt) => (
                <TouchableOpacity
                  key={opt.key}
                  onPress={() => updateGlobalRagConfig({ costStrategy: opt.key as any })}
                  style={{
                    backgroundColor: globalRagConfig.costStrategy === opt.key ? colors.opacity10 : 'transparent',
                    borderColor: globalRagConfig.costStrategy === opt.key ? opt.color : 'transparent',
                    overflow: 'hidden',
                  }}
                  className="p-3 rounded-[16px] mb-1 border border-transparent"
                >
                  <View className="flex-row items-center">
                    <View
                      style={{
                        width: 16,
                        height: 16,
                        borderRadius: 8,
                        borderWidth: 2,
                        borderColor: opt.color,
                        alignItems: 'center',
                        justifyContent: 'center',
                        marginRight: 10,
                      }}
                    >
                      {globalRagConfig.costStrategy === opt.key && (
                        <View
                          style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: opt.color }}
                        />
                      )}
                    </View>
                    <View>
                      <Typography className="font-bold text-sm text-gray-800 dark:text-gray-200">
                        {opt.label}
                      </Typography>
                      <Typography className="text-xs text-gray-400">{opt.desc}</Typography>
                    </View>
                  </View>
                </TouchableOpacity>
              ))}
            </View>
          </SettingsSection>

          {/* 2. 本地优化开关 */}
          <SettingsSection title={t.rag.kg.localOptimization}>
            <SettingsSwitchItem
              icon={Cpu}
              title={t.rag.kg.incrementalHash}
              subtitle={t.rag.kg.incrementalHashDesc}
              value={!!globalRagConfig.enableIncrementalHash}
              onValueChange={(v) => updateGlobalRagConfig({ enableIncrementalHash: v })}
            />
            <SettingsSwitchItem
              icon={Network}
              title={t.rag.kg.rulesPreFilter}
              subtitle={t.rag.kg.rulesPreFilterDesc}
              value={!!globalRagConfig.enableLocalPreprocess}
              onValueChange={(v) => updateGlobalRagConfig({ enableLocalPreprocess: v })}
              isLast
            />
          </SettingsSection>

          {/* 3. 提示词配置 */}
          <SettingsSection title={t.rag.kg.extractionPrompt}>
            <View className="p-3">
              <View className="flex-row justify-between items-center mb-4">
                <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                  {t.rag.kg.entityRelationPrompt}
                </Typography>
                <TouchableOpacity
                  onPress={() => {
                    const { DEFAULT_KG_PROMPT } = require('../../../lib/rag/defaults');
                    setPromptText(DEFAULT_KG_PROMPT);
                    setIsEditorVisible(true);
                  }}
                  className="flex-row items-center bg-gray-100 dark:bg-zinc-800 px-3 py-1.5 rounded-full"
                >
                  <RotateCcw size={12} color="#64748b" style={{ marginRight: 4 }} />
                  <Typography className="text-xs font-bold text-gray-500">{t.rag.kg.resetDefault}</Typography>
                </TouchableOpacity>
              </View>

              <TouchableOpacity
                onPress={() => setIsEditorVisible(true)}
                activeOpacity={0.7}
                className={`p-4 rounded-[16px] border border-dashed ${isDark ? 'bg-zinc-900/50 border-zinc-700' : 'bg-gray-50 border-gray-300'
                  }`}
              >
                <View className="flex-row items-center justify-between mb-2">
                  <View className="flex-row items-center">
                    <Edit3 size={16} color={isDark ? '#a1a1aa' : '#64748b'} className="mr-2" />
                    <Typography className="font-bold text-gray-700 dark:text-gray-300">
                      {t.rag.kg.editPrompt}
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

                <Typography
                  numberOfLines={3}
                  className="text-xs text-gray-500 dark:text-gray-400 leading-5"
                >
                  {promptText || t.rag.kg.usingDefaultPrompt}
                </Typography>
              </TouchableOpacity>

              <View className="mt-3 flex-row items-start bg-orange-50 dark:bg-orange-900/10 p-3 rounded-xl">
                <AlertTriangle size={14} color="#f97316" style={{ marginTop: 2, marginRight: 6 }} />
                <Typography className="text-[11px] text-orange-600 dark:text-orange-400 flex-1 leading-4">
                  {t.rag.kg.promptWarning}
                </Typography>
              </View>
            </View>
          </SettingsSection>

          <FloatingTextEditorModal
            visible={isEditorVisible}
            initialContent={promptText}
            title={t.rag.kg.editPromptTitle}
            placeholder={t.rag.kg.promptPlaceholder}
            warningMessage={t.rag.kg.promptFormatWarning}
            onClose={() => setIsEditorVisible(false)}
            onSave={handleSavePrompt}
          />

          {/* 4. 可视化入口 */}
          <SettingsSection title={t.rag.kg.visualization}>
            <TouchableOpacity
              onPress={() => router.push('/knowledge-graph')}
              activeOpacity={0.8}
            >
              <View className="p-3 items-center justify-center bg-indigo-50/50 dark:bg-indigo-900/10 border-indigo-200/50 dark:border-indigo-500/20 rounded-xl">
                <Typography style={{ color: colors[500] }} className="font-bold">
                  {t.rag.kg.viewFullGraph}
                </Typography>
              </View>
            </TouchableOpacity>
          </SettingsSection>

        </ScrollView>
      </KeyboardAvoidingView>

      <ModelPicker
        visible={modelModalVisible}
        onClose={() => setModelModalVisible(false)}
        onSelect={(uuid) => {
          let foundId = undefined;
          for (const p of providers) {
            const m = p.models.find((model) => model.uuid === uuid);
            if (m) {
              foundId = m.id;
              break;
            }
          }
          if (foundId) {
            updateGlobalRagConfig({ kgExtractionModel: foundId });
          }
        }}
        selectedUuid={(() => {
          if (!globalRagConfig.kgExtractionModel) return undefined;
          for (const p of providers) {
            const m = p.models.find((m) => m.id === globalRagConfig.kgExtractionModel);
            if (m) return m.uuid;
          }
          return undefined;
        })()}
        title={t.rag.kg.extractionModel}
        filterType="chat"
      />
    </PageLayout >
  );
}



import React, { useState, useMemo } from 'react';
import {
  View,
  TextInput,
  ScrollView,
  TouchableOpacity,
  Alert,
  KeyboardAvoidingView,
  Platform,
  Modal,
  FlatList,
} from 'react-native';
import { Stack, useRouter } from 'expo-router';
import { Typography, Switch, PageLayout, GlassHeader } from '../../../components/ui';
import { useSettingsStore } from '../../../store/settings-store';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import { Colors } from '../../../theme/colors';
import {
  Network,
  Cpu,
  DollarSign,
  Edit3,
  Save,
  ChevronLeft,
  Bot,
  Check,
  X,
  RotateCcw,
  AlertTriangle,
  Database,
  Trash2,
} from 'lucide-react-native';
import { useApiStore } from '../../../store/api-store';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { FloatingTextEditorModal } from '../../../components/ui/FloatingTextEditorModal';
import { ModelPicker } from '../ModelPicker';
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog';

const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 32 }) => {
  const { isDark, colors } = useTheme();
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

export default function RagAdvancedSettings() {
  const { isDark, colors } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();
  const { globalRagConfig, updateGlobalRagConfig } = useSettingsStore();
  const { providers } = useApiStore();
  const [modelModalVisible, setModelModalVisible] = useState(false);
  const [confirmState, setConfirmState] = useState({
    visible: false,
    title: '',
    message: '',
    onConfirm: () => { },
    isDestructive: false,
  });

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
      // promptText is already updated via onSave prop from modal? 
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
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        className="flex-1"
      >
        <ScrollView
          className="flex-1 px-5"
          contentContainerStyle={{
            paddingTop: 74 + insets.top,
            paddingBottom: 40,
          }}
          showsVerticalScrollIndicator={false}
        >
          {/* 0. 知识图谱总开关 */}
          <SectionHeader title={t.rag.kg.title} mt={10} />
          <View
            className="bg-white dark:bg-zinc-900 rounded-[24px] border border-gray-100 dark:border-zinc-800 mb-6 overflow-hidden shadow-sm"
          >
            <View className="p-4 flex-row justify-between items-center">
              <View style={{ flex: 1 }}>
                <View className="flex-row items-center mb-1">
                  <Network size={16} color={colors[500]} style={{ marginRight: 6 }} />
                  <Typography className="font-bold text-gray-900 dark:text-gray-100">
                    {t.rag.kg.enable}
                  </Typography>
                </View>
                <Typography variant="caption" className="text-gray-500 dark:text-gray-400">
                  {t.rag.kg.enableDesc}
                </Typography>
              </View>
              <Switch
                value={!!globalRagConfig.enableKnowledgeGraph}
                onValueChange={(v) => updateGlobalRagConfig({ enableKnowledgeGraph: v })}
              />
            </View>

            {/* Model Selection Row - Always visible */}
            <View>
              <View style={{ backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.02)' }} className="h-[1px] mx-4" />
              <TouchableOpacity
                onPress={() => globalRagConfig.enableKnowledgeGraph && setModelModalVisible(true)}
                activeOpacity={globalRagConfig.enableKnowledgeGraph ? 0.7 : 1}
                className="p-4 flex-row justify-between items-center transition-colors"
              >
                <View style={{ flex: 1, opacity: globalRagConfig.enableKnowledgeGraph ? 1 : 0.5 }}>
                  <View className="flex-row items-center mb-1">
                    <Bot
                      size={16}
                      color={globalRagConfig.enableKnowledgeGraph ? colors[500] : '#94a3b8'}
                      style={{ marginRight: 6 }}
                    />
                    <Typography className="font-bold text-gray-900 dark:text-gray-100">
                      {t.rag.kg.extractionModel}
                    </Typography>
                  </View>
                  <Typography variant="caption" className="text-gray-500 dark:text-gray-400">
                    {selectedModelName
                      ? t.rag.kg.currentModel.replace('{name}', selectedModelName)
                      : t.rag.kg.extractionModelDefault}
                  </Typography>
                </View>
                <View className="flex-row items-center">
                  <Typography style={{ color: colors[600] }} className="text-xs font-bold mr-1">{t.rag.kg.change}</Typography>
                  <ChevronLeft
                    size={16}
                    color={colors[500]}
                    style={{ transform: [{ rotate: '180deg' }] }}
                  />
                </View>
              </TouchableOpacity>
            </View>
          </View>

          {/* 1. 策略选择 (降本核心) */}
          <SectionHeader title={t.rag.kg.costStrategyTitle} mt={10} />
          <View
            className="bg-white dark:bg-zinc-900 rounded-[24px] p-5 border border-gray-100 dark:border-zinc-800 mb-8 shadow-sm"
          >
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

            {/* Radio Options */}
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
                }}
                className="p-3 rounded-xl mb-2 border"
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

          {/* 2. 本地优化开关 */}
          <SectionHeader title={t.rag.kg.localOptimization} />
          <View className="bg-white dark:bg-zinc-900 rounded-2xl overflow-hidden border border-gray-100 dark:border-zinc-800 mb-6">
            <View className="p-4 border-b border-gray-100 dark:border-gray-800 flex-row justify-between items-center">
              <View style={{ flex: 1 }}>
                <View className="flex-row items-center mb-1">
                  <Cpu size={16} color={colors[500]} style={{ marginRight: 6 }} />
                  <Typography className="font-bold text-gray-900 dark:text-gray-100">
                    {t.rag.kg.incrementalHash}
                  </Typography>
                </View>
                <Typography variant="caption" className="text-gray-500 dark:text-gray-400">
                  {t.rag.kg.incrementalHashDesc}
                </Typography>
              </View>
              <Switch
                value={!!globalRagConfig.enableIncrementalHash}
                onValueChange={(v) => updateGlobalRagConfig({ enableIncrementalHash: v })}
              />
            </View>
            <View className="p-4 flex-row justify-between items-center">
              <View style={{ flex: 1 }}>
                <View className="flex-row items-center mb-1">
                  <Network size={16} color={colors[500]} style={{ marginRight: 6 }} />
                  <Typography className="font-bold text-gray-900 dark:text-gray-100">
                    {t.rag.kg.rulesPreFilter}
                  </Typography>
                </View>
                <Typography variant="caption" className="text-gray-500 dark:text-gray-400">
                  {t.rag.kg.rulesPreFilterDesc}
                </Typography>
              </View>
              <Switch
                value={!!globalRagConfig.enableLocalPreprocess}
                onValueChange={(v) => updateGlobalRagConfig({ enableLocalPreprocess: v })}
              />
            </View>
          </View>

          {/* 3. 提示词配置 (Playground) */}
          <SectionHeader title={t.rag.kg.extractionPrompt} />
          <View className="bg-white dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800">
            <View className="flex-row justify-between items-center mb-4">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                {t.rag.kg.entityRelationPrompt}
              </Typography>
              <TouchableOpacity
                onPress={() => {
                  const { DEFAULT_KG_PROMPT } = require('../../../lib/rag/defaults');
                  setPromptText(DEFAULT_KG_PROMPT);
                  setIsEditorVisible(true); // Open editor with default
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
              className={`p-4 rounded-xl border border-dashed ${isDark ? 'bg-zinc-900/50 border-zinc-700' : 'bg-gray-50 border-gray-300'
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

            <View className="mt-3">
              <Typography className="text-[10px] text-orange-500 flex-1 leading-4">
                {t.rag.kg.promptWarning}
              </Typography>
            </View>
          </View>

          <FloatingTextEditorModal
            visible={isEditorVisible}
            initialContent={promptText}
            title={t.rag.kg.editPromptTitle}
            placeholder={t.rag.kg.promptPlaceholder}
            warningMessage={t.rag.kg.promptFormatWarning}
            onClose={() => setIsEditorVisible(false)}
            onSave={handleSavePrompt}
          />

          {/* 4. 可视化入口 (Beta) */}
          <SectionHeader title={t.rag.kg.visualization} />
          <TouchableOpacity
            onPress={() => router.push('/knowledge-graph')}
            style={{ backgroundColor: colors.opacity10, borderColor: colors.opacity20 }}
            className="p-4 rounded-2xl items-center border mb-6"
          >
            <Typography style={{ color: colors[600] }} className="font-bold">
              {t.rag.kg.viewFullGraph}
            </Typography>
          </TouchableOpacity>

        </ScrollView>
      </KeyboardAvoidingView>

      <ModelPicker
        visible={modelModalVisible}
        onClose={() => setModelModalVisible(false)}
        onSelect={(uuid) => {
          // ModelPicker returns UUID, but we store ID
          // We need to find the ID corresponding to this UUID
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



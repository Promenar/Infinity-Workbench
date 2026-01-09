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
} from 'lucide-react-native';
import { useApiStore } from '../../../store/api-store';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { FloatingTextEditorModal } from '../../../components/ui/FloatingTextEditorModal';

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

export default function RagAdvancedSettings() {
  const { isDark } = useTheme();
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
      Alert.alert('错误', '提示词不能为空');
      return;
    }

    // Warning Checks
    const issues: string[] = [];
    if (!content.includes('{entityTypes}')) {
      issues.push('- 缺少 {entityTypes} 占位符');
    }
    if (!content.toLowerCase().includes('json')) {
      issues.push('- 缺少 JSON 输出格式要求');
    }

    const save = () => {
      updateGlobalRagConfig({ kgExtractionPrompt: content });
      // promptText is already updated via onSave prop from modal? 
      // No, modal calls this with new content. We should update local state too.
      setPromptText(content);
      setIsEditorVisible(false);
      Alert.alert('已保存', '抽取提示词已更新');
    };

    if (issues.length > 0) {
      Alert.alert(
        '格式警告 (Format Warning)',
        '检测到以下问题可能导致知识提取失败:\n\n' +
        issues.join('\n') +
        '\n\n是否确认保存?',
        [
          { text: '取消', style: 'cancel' },
          { text: '强制保存', style: 'destructive', onPress: save },
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
        title="高级知识配置"
        subtitle="知识图谱 & 降本策略"
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
          <SectionHeader title="知识图谱 (Knowledge Graph)" mt={10} />
          <View className="bg-white dark:bg-zinc-900 rounded-2xl overflow-hidden border border-gray-100 dark:border-zinc-800 mb-6">
            <View className="p-4 flex-row justify-between items-center">
              <View style={{ flex: 1 }}>
                <View className="flex-row items-center mb-1">
                  <Network size={16} color="#6366f1" style={{ marginRight: 6 }} />
                  <Typography className="font-bold text-gray-900 dark:text-gray-100">
                    启用知识图谱
                  </Typography>
                </View>
                <Typography className="text-xs text-gray-500">
                  在向量化时自动抽取实体与关系 (需配置 LLM)
                </Typography>
              </View>
              <Switch
                value={!!globalRagConfig.enableKnowledgeGraph}
                onValueChange={(v) => updateGlobalRagConfig({ enableKnowledgeGraph: v })}
              />
            </View>

            {/* Model Selection Row - Always visible */}
            <View>
              <View className="h-[1px] bg-gray-100 dark:bg-zinc-800 mx-4" />
              <TouchableOpacity
                onPress={() => globalRagConfig.enableKnowledgeGraph && setModelModalVisible(true)}
                activeOpacity={globalRagConfig.enableKnowledgeGraph ? 0.7 : 1}
                className="p-4 flex-row justify-between items-center transition-colors"
              >
                <View style={{ flex: 1, opacity: globalRagConfig.enableKnowledgeGraph ? 1 : 0.5 }}>
                  <View className="flex-row items-center mb-1">
                    <Bot
                      size={16}
                      color={globalRagConfig.enableKnowledgeGraph ? '#10b981' : '#94a3b8'}
                      style={{ marginRight: 6 }}
                    />
                    <Typography className="font-bold text-gray-900 dark:text-gray-100">
                      抽取模型 (Extraction Model)
                    </Typography>
                  </View>
                  <Typography className="text-xs text-gray-500">
                    {selectedModelName
                      ? `当前: ${selectedModelName}`
                      : '默认 (跟随系统 Summary 模型)'}
                  </Typography>
                </View>
                {globalRagConfig.enableKnowledgeGraph && (
                  <View className="flex-row items-center">
                    <Typography className="text-xs text-indigo-500 font-bold mr-1">更换</Typography>
                    <ChevronLeft
                      size={16}
                      color="#6366f1"
                      style={{ transform: [{ rotate: '180deg' }] }}
                    />
                  </View>
                )}
              </TouchableOpacity>
            </View>
          </View>

          {/* 1. 策略选择 (降本核心) */}
          <SectionHeader title="降本增效策略 (Cost Strategy)" mt={0} />
          <View className="bg-white dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800 mb-6">
            <View className="flex-row items-center mb-4">
              <DollarSign size={20} color="#10b981" style={{ marginRight: 8 }} />
              <View style={{ flex: 1 }}>
                <Typography className="text-base font-bold text-gray-900 dark:text-white">
                  抽取策略
                </Typography>
                <Typography className="text-xs text-gray-500">
                  决定何时以及如何调用 LLM 进行知识抽取
                </Typography>
              </View>
            </View>

            {/* Radio Options */}
            {[
              {
                key: 'summary-first',
                label: '摘要优先 (Summary First)',
                desc: '仅分析文档摘要，成本最低 (推荐)',
                color: '#10b981',
              },
              {
                key: 'on-demand',
                label: '按需深挖 (On-Demand)',
                desc: '仅手动触发详细抽取',
                color: '#f59e0b',
              },
              {
                key: 'full',
                label: '全量扫描 (Full Scan)',
                desc: '分析每个片段，成本极高',
                color: '#ef4444',
              },
            ].map((opt) => (
              <TouchableOpacity
                key={opt.key}
                onPress={() => updateGlobalRagConfig({ costStrategy: opt.key as any })}
                className={`p-3 rounded-xl mb-2 border ${globalRagConfig.costStrategy === opt.key ? 'bg-gray-50 dark:bg-zinc-800' : 'border-transparent'}`}
                style={{
                  borderColor: globalRagConfig.costStrategy === opt.key ? opt.color : 'transparent',
                }}
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
          <SectionHeader title="本地预处理 (Local Optimization)" />
          <View className="bg-white dark:bg-zinc-900 rounded-2xl overflow-hidden border border-gray-100 dark:border-zinc-800 mb-6">
            <View className="p-4 border-b border-gray-100 dark:border-gray-800 flex-row justify-between items-center">
              <View style={{ flex: 1 }}>
                <View className="flex-row items-center mb-1">
                  <Cpu size={16} color="#6366f1" style={{ marginRight: 6 }} />
                  <Typography className="font-bold text-gray-900 dark:text-gray-100">
                    增量哈希校验
                  </Typography>
                </View>
                <Typography className="text-xs text-gray-500">
                  跳过内容未变更的文档，避免重复抽取
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
                  <Network size={16} color="#8b5cf6" style={{ marginRight: 6 }} />
                  <Typography className="font-bold text-gray-900 dark:text-gray-100">
                    规则预筛选
                  </Typography>
                </View>
                <Typography className="text-xs text-gray-500">
                  使用正则过滤低价值文本，不发送给 LLM
                </Typography>
              </View>
              <Switch
                value={!!globalRagConfig.enableLocalPreprocess}
                onValueChange={(v) => updateGlobalRagConfig({ enableLocalPreprocess: v })}
              />
            </View>
          </View>

          {/* 3. 提示词配置 (Playground) */}
          <SectionHeader title="抽取提示词" />
          <View className="bg-white dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800">
            <View className="flex-row justify-between items-center mb-4">
              <Typography className="text-sm font-bold text-gray-900 dark:text-gray-100">
                实体关系抽取提示词
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
                <Typography className="text-xs font-bold text-gray-500">重置默认</Typography>
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
                    点击编辑提取指令
                  </Typography>
                </View>
                {promptText ? (
                  <View className="bg-green-100 dark:bg-green-900/30 px-2 py-0.5 rounded">
                    <Typography className="text-[10px] text-green-700 dark:text-green-400">已配置</Typography>
                  </View>
                ) : (
                  <View className="bg-gray-200 dark:bg-gray-800 px-2 py-0.5 rounded">
                    <Typography className="text-[10px] text-gray-500">使用默认</Typography>
                  </View>
                )}
              </View>

              <Typography
                numberOfLines={3}
                className="text-xs text-gray-500 dark:text-gray-400 leading-5"
              >
                {promptText || "当前使用系统默认提示词 (点击查看或修改)..."}
              </Typography>
            </TouchableOpacity>

            <View className="mt-3">
              <Typography className="text-[10px] text-orange-500 flex-1 leading-4">
                修改提示词可能导致解析失败，请务必保留 JSON 输出格式要求。
              </Typography>
            </View>
          </View>

          <FloatingTextEditorModal
            visible={isEditorVisible}
            initialContent={promptText}
            title="编辑提取提示词"
            placeholder="输入系统提示词..."
            warningMessage="务必确认包含 {entityTypes} 占位符，并明确要求输出 JSON 格式，否则将导致知识提取失败。"
            onClose={() => setIsEditorVisible(false)}
            onSave={handleSavePrompt}
          />

          {/* 4. 可视化入口 (Beta) */}
          <SectionHeader title="可视化 (Beta)" />
          <TouchableOpacity
            onPress={() => router.push('/knowledge-graph')}
            className="bg-indigo-50 dark:bg-indigo-900/20 p-4 rounded-2xl items-center border border-indigo-100 dark:border-indigo-800"
          >
            <Typography className="font-bold text-indigo-600 dark:text-indigo-400">
              查看全量知识图谱 &gt;
            </Typography>
          </TouchableOpacity>
        </ScrollView>
      </KeyboardAvoidingView>

      <ModelSelectionModal
        visible={modelModalVisible}
        onClose={() => setModelModalVisible(false)}
        onSelect={(modelId) => updateGlobalRagConfig({ kgExtractionModel: modelId })}
        currentModelId={globalRagConfig.kgExtractionModel}
      />
    </PageLayout>
  );
}

const ModelSelectionModal: React.FC<{
  visible: boolean;
  onClose: () => void;
  onSelect: (modelId: string | undefined) => void;
  currentModelId?: string;
}> = ({ visible, onClose, onSelect, currentModelId }) => {
  const { isDark } = useTheme();
  const { providers } = useApiStore();

  const allModels = useMemo(() => {
    const models: any[] = [];
    providers.forEach((p) => {
      if (p.enabled) {
        p.models.forEach((m) => {
          if (m.enabled) {
            // Only enabled models
            models.push({ ...m, provider: p.name });
          }
        });
      }
    });
    return models;
  }, [providers]);

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View className="flex-1 bg-black/50 justify-center px-6">
        <View className="bg-white dark:bg-zinc-900 rounded-3xl max-h-[70%] overflow-hidden">
          <View className="p-4 border-b border-gray-100 dark:border-gray-800 flex-row justify-between items-center">
            <Typography className="font-bold text-lg text-gray-900 dark:text-white">
              选择模型
            </Typography>
            <TouchableOpacity onPress={onClose}>
              <X size={24} color={isDark ? '#fff' : '#000'} />
            </TouchableOpacity>
          </View>

          <ScrollView className="p-2">
            <TouchableOpacity
              onPress={() => {
                onSelect(undefined);
                onClose();
              }}
              className={`p-4 rounded-xl mb-2 flex-row justify-between items-center ${!currentModelId ? 'bg-indigo-50 dark:bg-indigo-900/30' : 'bg-gray-50 dark:bg-zinc-800'}`}
            >
              <View>
                <Typography className="font-bold text-gray-900 dark:text-white">
                  跟随系统默认
                </Typography>
                <Typography className="text-xs text-gray-500">使用全局默认总结模型</Typography>
              </View>
              {!currentModelId && <Check size={20} color="#6366f1" />}
            </TouchableOpacity>

            {allModels.map((model) => (
              <TouchableOpacity
                key={model.id}
                onPress={() => {
                  onSelect(model.id);
                  onClose();
                }}
                className={`p-4 rounded-xl mb-2 flex-row justify-between items-center ${currentModelId === model.id ? 'bg-indigo-50 dark:bg-indigo-900/30' : 'bg-gray-50 dark:bg-zinc-800'}`}
              >
                <View>
                  <Typography className="font-bold text-gray-900 dark:text-white">
                    {model.name}
                  </Typography>
                  <Typography className="text-xs text-gray-500">{model.provider}</Typography>
                </View>
                {currentModelId === model.id && <Check size={20} color="#6366f1" />}
              </TouchableOpacity>
            ))}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
};

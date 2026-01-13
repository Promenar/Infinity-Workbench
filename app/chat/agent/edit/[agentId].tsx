import React, { useState } from 'react';
import {
  View,
  ScrollView,
  TouchableOpacity,
  TextInput,
  KeyboardAvoidingView,
  Platform,
  ActivityIndicator,
} from 'react-native';
import { PageLayout, Typography, GlassHeader, ConfirmDialog } from '../../../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import {
  ChevronLeft,
  Save,
  Sparkles,
  Cpu,
  ChevronRight,
  Trash2,
  Image as ImageIcon,
  Check,
  Database,
  Edit3,
} from 'lucide-react-native';
import * as Haptics from '../../../../src/lib/haptics';
import { useAgentStore } from '../../../../src/store/agent-store';
import { useApiStore } from '../../../../src/store/api-store';
import { ModelPicker } from '../../../../src/features/settings/ModelPicker';
import { useTheme } from '../../../../src/theme/ThemeProvider';
import { useI18n } from '../../../../src/lib/i18n';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { clsx } from 'clsx';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
import { AgentAvatar } from '../../../../src/components/chat/AgentAvatar';
import { FloatingTextEditorModal } from '../../../../src/components/ui/FloatingTextEditorModal';
import { AgentRagConfigPanel } from '../../../../src/features/settings/components/AgentRagConfigPanel';
import { useDebounce } from '../../../../src/hooks/useDebounce';
import { PRESET_COLORS } from '../../../../src/types/super-assistant';
import { InferencePresets } from '../../../../src/components/chat/InferencePresets';
import { ColorPickerPanel } from '../../../../src/components/ui/ColorPickerPanel';

const PRESET_ICONS = [
  'MessageSquare',
  'Zap',
  'Brain',
  'Bot',
  'Cpu',
  'Sparkles',
  'Code2',
  'User',
  'Globe',
  'Terminal',
];

export default function AgentEditScreen() {
  const { agentId } = useLocalSearchParams<{ agentId: string }>();
  const router = useRouter();
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const { getAgent, updateAgent, deleteAgent } = useAgentStore();
  const { providers } = useApiStore();
  const agent = getAgent(agentId);

  const [showModelPicker, setShowModelPicker] = useState(false);
  const [isProcessingImage, setIsProcessingImage] = useState(false);
  const [isPromptEditorVisible, setIsPromptEditorVisible] = useState(false);

  const [formData, setFormData] = useState({
    name: agent?.name || '',
    description: agent?.description || '',
    systemPrompt: agent?.systemPrompt || '',
    defaultModel: agent?.defaultModel || '',
    params: agent?.params || ({} as any),
    temperature: agent?.params?.temperature || 0.7,
    avatar: agent?.avatar || 'MessageSquare',
    color: agent?.color || PRESET_COLORS[10].value, // Default to Indigo (#6366f1) if not set. Index 10 is Indigo in list.
  });

  const displayModelName = React.useMemo(() => {
    if (!formData.defaultModel) return t.agent.selectModel;
    for (const provider of providers) {
      const model = provider.models.find(m => m.uuid === formData.defaultModel || m.id === formData.defaultModel);
      if (model) return model.name;
    }
    return formData.defaultModel;
  }, [providers, formData.defaultModel]);

  // 确认弹窗状态
  const [confirmState, setConfirmState] = useState<{
    visible: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
    isDestructive?: boolean;
  }>({
    visible: false,
    title: '',
    message: '',
    onConfirm: () => { },
    isDestructive: false,
  });

  const handlePickImage = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsEditing: true,
      aspect: [1, 1],
      quality: 0.8,
    });

    if (!result.canceled && result.assets && result.assets.length > 0) {
      setIsProcessingImage(true);
      const sourceUri = result.assets[0].uri;
      const fileName = `agent-avatar-${agentId}-${Date.now()}.jpg`;
      const destPath = `${FileSystem.documentDirectory}${fileName}`;

      try {
        await FileSystem.copyAsync({
          from: sourceUri,
          to: destPath,
        });

        // Immediate update for Avatar
        updateAgent(agentId, { avatar: destPath });
        setFormData(prev => ({ ...prev, avatar: destPath }));

        setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium), 10);
      } catch (e) {
        console.error('Failed to copy avatar', e);

        // Fallback immediate update
        updateAgent(agentId, { avatar: sourceUri });
        setFormData(prev => ({ ...prev, avatar: sourceUri }));
      } finally {
        setIsProcessingImage(false);
      }
    }
  };

  // Auto-save logic
  const debouncedFormData = useDebounce(formData, 1000);

  React.useEffect(() => {
    if (!agent) return;

    let hasChanges = false;
    if (debouncedFormData.name !== agent.name) hasChanges = true;
    if (debouncedFormData.description !== agent.description) hasChanges = true;
    if (debouncedFormData.systemPrompt !== agent.systemPrompt) hasChanges = true;
    if (debouncedFormData.defaultModel !== agent.defaultModel) hasChanges = true;

    // Only update if avatar purely via basic string change (preset), handled via logic below
    // But preset icons might update formData directly. 
    // Wait, preset icons usually update formData.avatar.
    // If formData.avatar !== agent.avatar, we update.
    if (debouncedFormData.avatar !== agent.avatar) hasChanges = true;
    if (debouncedFormData.color !== agent.color) hasChanges = true;

    // Check params (temperature)
    if (debouncedFormData.temperature !== agent.params?.temperature) hasChanges = true;

    if (hasChanges) {
      updateAgent(agentId, {
        name: debouncedFormData.name,
        description: debouncedFormData.description,
        systemPrompt: debouncedFormData.systemPrompt,
        defaultModel: debouncedFormData.defaultModel,
        avatar: debouncedFormData.avatar,
        color: debouncedFormData.color,
        params: {
          ...agent.params,
          ...debouncedFormData.params,
          temperature: debouncedFormData.temperature
        },
      });
    }
  }, [debouncedFormData, agent?.id]); // Simplified dependencies since we check deep values

  const handleDelete = () => {
    setConfirmState({
      visible: true,
      title: t.agent.deleteConfirmTitle || '确认删除',
      message: t.agent.deleteConfirmMessage || '删除后无法恢复，确定要删除此助手吗？',
      isDestructive: true,
      onConfirm: () => {
        setTimeout(() => {
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
          deleteAgent(agentId);
          setConfirmState((prev) => ({ ...prev, visible: false }));
          router.push('/(tabs)/chat');
        }, 10);
      },
    });
  };

  const SectionHeader = ({ title }: { title: string }) => {
    const { colors } = useTheme();
    return (
      <View className="flex-row items-center mb-4 mt-2">
        <View style={{ backgroundColor: colors[500] }} className="w-1 h-4 rounded-full mr-2" />
        <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
          {title}
        </Typography>
      </View>
    );
  };

  if (!agent) return null;

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.agent.editTitle}
        subtitle={t.agent.editSubtitle}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
        rightAction={undefined}
      />

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        className="flex-1"
        keyboardVerticalOffset={0}
      >
        <ScrollView
          className="flex-1 px-6"
          contentContainerStyle={{
            paddingTop: 74 + insets.top,
            paddingBottom: 40,
          }}
          showsVerticalScrollIndicator={false}
        >
          {/* Agent Avatar Group */}
          <SectionHeader title={t.agent.avatar || '助手头像'} />
          <View className="bg-gray-50/80 dark:bg-zinc-900/60 rounded-3xl p-6 border border-indigo-50 dark:border-indigo-500/10 mb-8 items-center">
            <View className="relative mb-6">
              <AgentAvatar
                id={agentId}
                name={formData.name}
                avatar={formData.avatar}
                color={formData.color}
                size={100}
              />
              <TouchableOpacity
                onPress={handlePickImage}
                style={{ backgroundColor: colors[600] }}
                className="absolute bottom-0 right-0 p-2.5 rounded-full border-4 border-gray-50 dark:border-zinc-900 shadow-md"
              >
                {isProcessingImage ? (
                  <ActivityIndicator size="small" color="white" />
                ) : (
                  <ImageIcon size={18} color="white" />
                )}
              </TouchableOpacity>
            </View>

            <View className="flex-row flex-wrap justify-center gap-2">
              {PRESET_ICONS.map((iconName) => (
                <TouchableOpacity
                  key={iconName}
                  onPress={() => {
                    setFormData({ ...formData, avatar: iconName });
                    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                  }}
                  className={clsx(
                    'w-10 h-10 rounded-xl items-center justify-center border-2',
                    formData.avatar === iconName
                      ? 'border-transparent'
                      : 'bg-white dark:bg-black border-transparent',
                  )}
                  style={formData.avatar === iconName ? {
                    backgroundColor: colors.opacity10,
                    borderColor: colors[500]
                  } : {}}
                >
                  <AgentAvatar
                    id={agentId}
                    name={formData.name}
                    avatar={iconName}
                    color={formData.color}
                    size={24}
                  />
                  {formData.avatar === iconName && (
                    <View style={{ backgroundColor: colors[500] }} className="absolute -top-1 -right-1 rounded-full p-0.5">
                      <Check size={6} color="white" />
                    </View>
                  )}
                </TouchableOpacity>
              ))}
            </View>
          </View>

          {/* Basic Info Group */}
          <SectionHeader title={t.agent.basicInfo} />
          <View className="bg-gray-50/80 dark:bg-zinc-900/60 rounded-3xl p-5 border border-indigo-50 dark:border-indigo-500/10 mb-8">
            <Typography className="text-gray-900 dark:text-white font-bold mb-2">
              {t.agent.name}
            </Typography>
            <TextInput
              className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-indigo-50 dark:border-indigo-500/10 mb-4"
              value={formData.name}
              onChangeText={(text) => setFormData({ ...formData, name: text })}
              placeholder={t.agent.namePlaceholder}
              placeholderTextColor="#94a3b8"
            />

            <Typography className="text-gray-900 dark:text-white font-bold mb-2">
              {t.agent.description}
            </Typography>
            <TextInput
              className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-indigo-50 dark:border-indigo-500/10"
              multiline
              numberOfLines={2}
              value={formData.description}
              onChangeText={(text) => setFormData({ ...formData, description: text })}
              placeholder={t.agent.descriptionPlaceholder}
              placeholderTextColor="#94a3b8"
            />
          </View>

          {/* Theme Color Group */}
          <View className="mb-0">
            <ColorPickerPanel
              color={formData.color}
              onColorChange={(color) => {
                setFormData({ ...formData, color: color });
                setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
              }}
              title={t.common.color.title}
            />
          </View>

          {/* Personality Group */}
          <SectionHeader title={t.agent.personality} />
          <View className="bg-gray-50/80 dark:bg-zinc-900/60 rounded-3xl p-5 border border-indigo-50 dark:border-indigo-500/10 mb-8">
            <View className={`rounded-xl border border-dashed p-4 ${isDark ? 'bg-zinc-900/50 border-zinc-700' : 'bg-gray-50 border-gray-300'}`}>
              <View className="flex-row items-center justify-between mb-2">
                <View className="flex-row items-center">
                  <Edit3 size={16} color={isDark ? '#a1a1aa' : '#64748b'} className="mr-2" />
                  <Typography className="font-bold text-gray-700 dark:text-gray-300">
                    {t.agent.personality}
                  </Typography>
                </View>
                {formData.systemPrompt ? (
                  <View className="bg-green-100 dark:bg-green-900/30 px-2 py-0.5 rounded">
                    <Typography className="text-[10px] text-green-700 dark:text-green-400">
                      {t.rag.configured || '已配置'}
                    </Typography>
                  </View>
                ) : (
                  <View className="bg-gray-200 dark:bg-gray-800 px-2 py-0.5 rounded">
                    <Typography className="text-[10px] text-gray-500">
                      {t.common.notSet}
                    </Typography>
                  </View>
                )}
              </View>

              <TouchableOpacity
                onPress={() => setIsPromptEditorVisible(true)}
                activeOpacity={0.7}
              >
                <Typography
                  numberOfLines={4}
                  className="text-xs text-gray-500 dark:text-gray-400 leading-5"
                >
                  {formData.systemPrompt || (t.agent.personalityPlaceholder || t.agent.systemPromptPlaceholder)}
                </Typography>
              </TouchableOpacity>
            </View>
          </View>

          <FloatingTextEditorModal
            visible={isPromptEditorVisible}
            initialContent={formData.systemPrompt || ''}
            title={t.agent.personality}
            placeholder={t.agent.personalityPlaceholder || t.agent.systemPromptPlaceholder}
            onClose={() => setIsPromptEditorVisible(false)}
            onSave={(text) => {
              setFormData({ ...formData, systemPrompt: text });
              setIsPromptEditorVisible(false);
            }}
          />

          {/* Model Configuration Group */}
          <SectionHeader title={t.agent.modelConfig} />
          <View className="bg-gray-50/80 dark:bg-zinc-900/60 rounded-3xl border border-indigo-50 dark:border-indigo-500/10 mb-8">
            <TouchableOpacity
              onPress={() => {
                setTimeout(() => {
                  setShowModelPicker(true);
                }, 10);
              }}
              className="flex-row items-center justify-between p-5"
            >
              <View className="flex-1">
                <Typography className="text-gray-900 dark:text-white font-bold mb-1">
                  {t.agent.engine}
                </Typography>
                <Typography style={{ color: colors[500] }} className="font-semibold" numberOfLines={1}>
                  {displayModelName}
                </Typography>
              </View>
              <ChevronRight size={20} color="#94a3b8" />
            </TouchableOpacity>

            <View className="border-t border-indigo-50 dark:border-indigo-500/10 p-5">
              <Typography className="text-gray-900 dark:text-white font-bold mb-3">
                {t.agent.creativity}
              </Typography>
              <InferencePresets
                currentTemperature={formData.temperature}
                onSelect={(params: any) => {
                  setFormData((prev) => ({
                    ...prev,
                    temperature: params.temperature ?? prev.temperature,
                    params: {
                      ...prev.params,
                      ...params,
                    }
                  }));
                }}
              />
            </View>
          </View>

          {/* RAG 配置入口 */}
          <SectionHeader title={t.agent.ragConfigTitle} />
          <View className="bg-gray-50/80 dark:bg-zinc-900/60 rounded-3xl border border-indigo-50 dark:border-indigo-500/10 mb-8">
            <TouchableOpacity
              onPress={() => {
                setTimeout(() => {
                  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                  router.push(`/chat/agent/edit/rag-config/${agentId}` as any);
                }, 10);
              }}
              className="flex-row items-center justify-between p-5"
            >
              <View className="flex-1">
                <Typography className="text-gray-900 dark:text-white font-bold mb-1">
                  {t.common.ragSection}
                </Typography>
                <Typography className="text-gray-500 dark:text-gray-400 text-sm">
                  {t.agent.ragConfigDesc}
                </Typography>
              </View>
              <ChevronRight size={20} color="#9ca3af" />
            </TouchableOpacity>

            <View className="border-t border-indigo-50 dark:border-indigo-500/10" />

            <TouchableOpacity
              onPress={() => {
                setTimeout(() => {
                  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                  router.push(`/chat/agent/edit/advanced-retrieval/${agentId}` as any);
                }, 10);
              }}
              className="flex-row items-center justify-between p-5"
            >
              <View className="flex-1">
                <Typography className="text-gray-900 dark:text-white font-bold mb-1">
                  {t.agent.advancedRetrievalTitle}
                </Typography>
                <Typography className="text-gray-500 dark:text-gray-400 text-sm">
                  {t.agent.advancedRetrievalDesc}
                </Typography>
              </View>
              <ChevronRight size={20} color="#9ca3af" />
            </TouchableOpacity>
          </View>

          {/* Danger Zone */}
          <SectionHeader title={t.common.dangerZone} />
          <View className="bg-red-50 dark:bg-red-900/10 rounded-3xl p-5 border border-red-100 dark:border-red-900/20 mb-10">
            <TouchableOpacity
              onPress={handleDelete}
              className="flex-row items-center justify-between"
            >
              <View className="flex-row items-center">
                <View className="w-10 h-10 rounded-full bg-red-100 dark:bg-red-900/20 items-center justify-center mr-3">
                  <Trash2 size={20} color="#ef4444" />
                </View>
                <View>
                  <Typography className="text-red-600 dark:text-red-400 font-bold">
                    {t.common.delete || '删除助手'}
                  </Typography>
                  <Typography variant="caption" className="text-red-400/80">
                    {t.agent.deleteConfirmMessage || '永久删除此助手及其配置'}
                  </Typography>
                </View>
              </View>
              <ChevronRight size={20} color="#ef4444" />
            </TouchableOpacity>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>


      <ConfirmDialog
        visible={confirmState.visible}
        title={confirmState.title}
        message={confirmState.message}
        isDestructive={confirmState.isDestructive}
        confirmText={t.common.delete || '删除'}
        cancelText={t.common.cancel || '取消'}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
      />

      {showModelPicker && (
        <ModelPicker
          visible={showModelPicker}
          title={t.agent.selectModel}
          selectedUuid={formData.defaultModel}
          onSelect={(uuid) => {
            setFormData({ ...formData, defaultModel: uuid });
            setTimeout(() => {
              setShowModelPicker(false);
            }, 10);
          }}
          onClose={() => {
            setTimeout(() => {
              setShowModelPicker(false);
            }, 10);
          }}
        />
      )}
    </PageLayout>
  );
}

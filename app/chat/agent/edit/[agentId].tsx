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
import { AgentRagConfigPanel } from '../../../../src/features/settings/components/AgentRagConfigPanel';
import { useDebounce } from '../../../../src/hooks/useDebounce';

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
  const { isDark } = useTheme();
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const { getAgent, updateAgent, deleteAgent } = useAgentStore();
  const { providers } = useApiStore();
  const agent = getAgent(agentId);

  const [showModelPicker, setShowModelPicker] = useState(false);
  const [isProcessingImage, setIsProcessingImage] = useState(false);

  const [formData, setFormData] = useState({
    name: agent?.name || '',
    description: agent?.description || '',
    systemPrompt: agent?.systemPrompt || '',
    defaultModel: agent?.defaultModel || '',
    params: agent?.params || ({} as any),
    temperature: agent?.params?.temperature || 0.7,
    avatar: agent?.avatar || 'MessageSquare',
  });

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

    // Check params (temperature)
    if (debouncedFormData.temperature !== agent.params?.temperature) hasChanges = true;

    if (hasChanges) {
      updateAgent(agentId, {
        name: debouncedFormData.name,
        description: debouncedFormData.description,
        systemPrompt: debouncedFormData.systemPrompt,
        defaultModel: debouncedFormData.defaultModel,
        avatar: debouncedFormData.avatar,
        params: { ...agent.params, temperature: debouncedFormData.temperature },
      });
    }
  }, [debouncedFormData, agent?.name, agent?.description, agent?.systemPrompt, agent?.defaultModel, agent?.avatar, agent?.params?.temperature]);

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

  const SectionHeader = ({ title }: { title: string }) => (
    <View className="flex-row items-center mb-4 mt-2">
      <View className="w-1 h-4 bg-indigo-500 rounded-full mr-2" />
      <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
        {title}
      </Typography>
    </View>
  );

  if (!agent) return null;

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.agent.editTitle}
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
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-6 border border-gray-100 dark:border-zinc-800 mb-8 items-center">
            <View className="relative mb-6">
              <AgentAvatar
                id={agentId}
                name={formData.name}
                avatar={formData.avatar}
                color={agent.color}
                size={100}
              />
              <TouchableOpacity
                onPress={handlePickImage}
                className="absolute bottom-0 right-0 bg-indigo-600 p-2.5 rounded-full border-4 border-gray-50 dark:border-zinc-900 shadow-md"
              >
                {isProcessingImage ? (
                  <ActivityIndicator size="small" color="white" />
                ) : (
                  <ImageIcon size={18} color="white" />
                )}
              </TouchableOpacity>
            </View>

            <View className="flex-row flex-wrap justify-center gap-3">
              {PRESET_ICONS.map((iconName) => (
                <TouchableOpacity
                  key={iconName}
                  onPress={() => {
                    setFormData({ ...formData, avatar: iconName });
                    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                  }}
                  className={clsx(
                    'w-12 h-12 rounded-2xl items-center justify-center border-2',
                    formData.avatar === iconName
                      ? 'bg-indigo-50 dark:bg-indigo-500/20 border-indigo-500'
                      : 'bg-white dark:bg-black border-transparent',
                  )}
                >
                  <AgentAvatar
                    id={agentId}
                    name={formData.name}
                    avatar={iconName}
                    color={agent.color}
                    size={32}
                  />
                  {formData.avatar === iconName && (
                    <View className="absolute -top-1 -right-1 bg-indigo-500 rounded-full p-0.5">
                      <Check size={8} color="white" />
                    </View>
                  )}
                </TouchableOpacity>
              ))}
            </View>
          </View>

          {/* Basic Info Group */}
          <SectionHeader title={t.agent.basicInfo} />
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
            <Typography className="text-gray-900 dark:text-white font-bold mb-2">
              {t.agent.name}
            </Typography>
            <TextInput
              className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-gray-100 dark:border-zinc-800 mb-4"
              value={formData.name}
              onChangeText={(text) => setFormData({ ...formData, name: text })}
              placeholder={t.agent.namePlaceholder}
              placeholderTextColor="#94a3b8"
            />

            <Typography className="text-gray-900 dark:text-white font-bold mb-2">
              {t.agent.description}
            </Typography>
            <TextInput
              className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-gray-100 dark:border-zinc-800"
              multiline
              numberOfLines={2}
              value={formData.description}
              onChangeText={(text) => setFormData({ ...formData, description: text })}
              placeholder={t.agent.descriptionPlaceholder}
              placeholderTextColor="#94a3b8"
            />
          </View>

          {/* Personality Group */}
          <SectionHeader title={t.agent.personality} />
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
            <TextInput
              className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-4 rounded-xl border border-gray-100 dark:border-zinc-800 h-40"
              multiline
              textAlignVertical="top"
              value={formData.systemPrompt}
              onChangeText={(text) => setFormData({ ...formData, systemPrompt: text })}
              placeholder={t.agent.personalityPlaceholder || t.agent.systemPromptPlaceholder}
              placeholderTextColor="#94a3b8"
            />
          </View>

          {/* Model Configuration Group */}
          <SectionHeader title={t.agent.modelConfig} />
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl border border-gray-100 dark:border-zinc-800 mb-8">
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
                <Typography className="text-indigo-500 font-semibold">
                  {formData.defaultModel}
                </Typography>
              </View>
              <ChevronRight size={20} color="#94a3b8" />
            </TouchableOpacity>

            <View className="border-t border-gray-100 dark:border-zinc-800 p-5">
              <Typography className="text-gray-900 dark:text-white font-bold mb-3">
                {t.agent.creativity}
              </Typography>
              <View className="flex-row justify-between mb-2">
                {[0, 0.3, 0.7, 1].map((temp) => (
                  <TouchableOpacity
                    key={temp}
                    onPress={() => {
                      setTimeout(() => {
                        setFormData({ ...formData, temperature: temp });
                      }, 10);
                    }}
                    className={clsx(
                      'flex-1 py-2 mx-1 rounded-xl border',
                      formData.temperature === temp
                        ? 'bg-indigo-50 dark:bg-indigo-500/10 border-indigo-200 dark:border-indigo-900/30'
                        : 'bg-white dark:bg-black border-gray-100 dark:border-zinc-800',
                    )}
                  >
                    <Typography
                      className={clsx(
                        'text-center font-bold text-xs',
                        formData.temperature === temp
                          ? 'text-indigo-600 dark:text-indigo-400'
                          : 'text-gray-400',
                      )}
                    >
                      {temp === 0
                        ? t.agent.precise
                        : temp === 0.3
                          ? '0.3'
                          : temp === 0.7
                            ? '0.7'
                            : t.agent.creative}
                    </Typography>
                  </TouchableOpacity>
                ))}
              </View>
            </View>
          </View>

          {/* RAG 配置入口 */}
          <SectionHeader title="RAG 配置" />
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl border border-gray-100 dark:border-zinc-800 mb-8">
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
                  高级RAG配置
                </Typography>
                <Typography className="text-gray-500 dark:text-gray-400 text-sm">
                  配置切块、摘要、检索参数
                </Typography>
              </View>
              <ChevronRight size={20} color="#9ca3af" />
            </TouchableOpacity>

            <View className="border-t border-gray-100 dark:border-zinc-800" />

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
                  高级检索配置
                </Typography>
                <Typography className="text-gray-500 dark:text-gray-400 text-sm">
                  Rerank、查询重写、混合检索
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

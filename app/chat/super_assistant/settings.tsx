import React, { useState, useEffect } from 'react';
import {
  View,
  ScrollView,
  TouchableOpacity,
  TextInput,
  KeyboardAvoidingView,
  Platform,
  ActivityIndicator,
} from 'react-native';
import {
  PageLayout,
  Typography,
  GlassHeader,
  useToast,
  ConfirmDialog,
  Switch,
} from '../../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import {
  ChevronLeft,
  Save,
  Sparkles,
  Download,
  Trash2,
  Check,
  Upload,
  Database,
  ChevronRight,
} from 'lucide-react-native';
import * as Haptics from '../../../src/lib/haptics';
import { useChatStore } from '../../../src/store/chat-store';
import { useTheme } from '../../../src/theme/ThemeProvider';
import { useI18n } from '../../../src/lib/i18n';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { exportAllSessionsToTxt } from '../../../src/features/chat/utils/export';
import { useSPAStore } from '../../../src/store/spa-store';
import { useAgentStore } from '../../../src/store/agent-store';
import {
  PRESET_FAB_ICONS,
  PRESET_COLORS,
  FABIconType,
  ANIMATION_MODES,
} from '../../../src/types/super-assistant';
import * as ImagePicker from 'expo-image-picker';
import * as LucideIcons from 'lucide-react-native';
import { Image } from 'expo-image';
import { vectorStore } from '../../../src/lib/rag/vector-store';
import { InferenceSettings } from '../../../src/components/chat/InferenceSettings';
import { Sliders } from 'lucide-react-native';
import { ContextManagementPanel } from '../../../src/features/chat/settings/ContextManagementPanel';
import { AgentRagConfigPanel } from '../../../src/features/settings/components/AgentRagConfigPanel';
import { preventDoubleTap } from '../../../src/lib/navigation-utils';

const SPA_SESSION_ID = 'super_assistant';

export default function SuperAssistantSettingsScreen() {
  const router = useRouter();
  const { isDark } = useTheme();
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const { showToast } = useToast();

  const {
    getSession,
    updateSessionTitle,
    updateSessionInferenceParams,
    generateSessionTitle,
    deleteSession,
  } = useChatStore();
  const { preferences, updateFABConfig, updateRAGStats } = useSPAStore();

  const handlePruneGhostData = async () => {
    try {
      const allSessions = useChatStore.getState().sessions;
      const activeIds = allSessions.map((s) => s.id);
      if (!activeIds.includes(SPA_SESSION_ID)) {
        activeIds.push(SPA_SESSION_ID);
      }

      await vectorStore.pruneOrphanSessions(activeIds);

      showToast(t.superAssistant.pruneSuccess, 'success');
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);

      // 刷新统计
      updateRAGStats();
    } catch (error) {
      console.error(error);
      showToast(t.superAssistant.pruneFail, 'error');
    }
  };

  const session = getSession(SPA_SESSION_ID);

  const [formData, setFormData] = useState({
    title: session?.title || 'Super Personal Assistant',
  });

  const [isGeneratingTitle, setIsGeneratingTitle] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [showColorPicker, setShowColorPicker] = useState<'icon' | 'glow' | null>(null);

  // 加载 RAG 统计
  useEffect(() => {
    updateRAGStats();
  }, []);

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
    onConfirm: () => {},
    isDestructive: false,
  });

  const handleSave = () => {
    setTimeout(() => {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      updateSessionTitle(SPA_SESSION_ID, formData.title.trim());
      showToast(t.superAssistant.settingsSaved, 'success');
      router.back();
    }, 10);
  };

  const handleGenerateTitle = async () => {
    if (isGeneratingTitle) return;
    setIsGeneratingTitle(true);
    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium), 10);

    try {
      const newTitle = await generateSessionTitle(SPA_SESSION_ID);
      if (newTitle) {
        setFormData((prev) => ({ ...prev, title: newTitle }));
      }
    } catch (error) {
      console.error(error);
    } finally {
      setIsGeneratingTitle(false);
    }
  };

  const handleExportCurrent = async () => {
    if (isExporting || !session) return;
    setIsExporting(true);
    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);

    try {
      const result = await exportAllSessionsToTxt([session]);
      if (result.success) {
        setConfirmState({
          visible: true,
          title: t.superAssistant.exportSuccess,
          message: t.superAssistant.exportSuccess, // Simply using the success message again or add a dedicated desc key if needed
          onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
        });
      } else if (result.error !== 'Permission denied') {
        setConfirmState({
          visible: true,
          title: t.superAssistant.exportFail,
          message: result.error || 'Unknown error',
          onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
        });
      }
    } catch (error) {
      setConfirmState({
        visible: true,
        title: t.superAssistant.exportFail,
        message: (error as Error).message,
        onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
      });
    } finally {
      setIsExporting(false);
    }
  };

  const handleDeleteSession = async () => {
    setConfirmState({
      visible: true,
      title: t.superAssistant.deleteSession,
      message: t.superAssistant.deleteSessionDesc, // Reusing description for dialog message
      isDestructive: true,
      onConfirm: async () => {
        await deleteSession(SPA_SESSION_ID);
        showToast(t.superAssistant.sessionDeleted, 'success');
        setConfirmState((prev) => ({ ...prev, visible: false }));
        router.replace('/(tabs)/chat');
      },
    });
  };

  const handlePickCustomIcon = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images, // GIFs are classified as images
      allowsEditing: false, // Must disable editing to preserve GIF animation
      quality: 0.8,
      // aspect: [1, 1], // Removed because editing is disabled
    });

    if (!result.canceled && result.assets[0]) {
      updateFABConfig({
        iconType: 'custom',
        customIconUri: result.assets[0].uri,
      });
    }
  };

  const renderFABIcon = (type: FABIconType, size: number, color: string) => {
    if (type === 'custom' && preferences.fab.customIconUri) {
      return (
        <Image
          source={{ uri: preferences.fab.customIconUri }}
          style={{ width: size, height: size, borderRadius: size / 2 }}
          contentFit="cover"
          cachePolicy="disk"
        />
      );
    }

    const IconComponent = (LucideIcons as any)[type];
    if (IconComponent) {
      return <IconComponent size={size} color={color} />;
    }
    return <Sparkles size={size} color={color} />;
  };

  if (!session) return null;

  const stats = preferences.ragStats;

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.superAssistant.title}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
        rightAction={{
          icon: <Save size={24} color={isDark ? '#fff' : '#000'} strokeWidth={2} />,
          onPress: handleSave,
          label: t.common.save,
        }}
      />

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
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
          {/* RAG Status Card (Read-only) */}
          <Typography
            variant="label"
            className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3"
          >
            <Sparkles size={10} color="#64748b" className="mr-1" />{' '}
            {t.superAssistant.globalKnowledge}
          </Typography>
          <View className="bg-gradient-to-br from-purple-50 to-indigo-50 dark:from-purple-900/20 dark:to-indigo-900/20 rounded-3xl p-5 border border-purple-200 dark:border-purple-800/30 mb-8">
            <View className="flex-row items-center mb-4">
              <View className="w-10 h-10 rounded-full bg-purple-500 items-center justify-center mr-3">
                <Sparkles size={20} color="#fff" />
              </View>
              <View className="flex-1">
                <Typography className="text-lg font-bold text-purple-900 dark:text-purple-100">
                  {t.superAssistant.globalRagEnabled}
                </Typography>
                <Typography variant="caption" className="text-purple-600 dark:text-purple-300">
                  {t.superAssistant.accessAllData}
                </Typography>
              </View>
            </View>

            <View className="bg-white/50 dark:bg-black/20 rounded-2xl p-4 space-y-2">
              <View className="flex-row items-center justify-between">
                <Typography className="text-sm text-gray-700 dark:text-gray-300">
                  📄 {t.superAssistant.docCount}
                </Typography>
                <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                  {stats?.totalDocuments || 0}
                </Typography>
              </View>
              <View className="flex-row items-center justify-between">
                <Typography className="text-sm text-gray-700 dark:text-gray-300">
                  💬 {t.superAssistant.sessionMemory}
                </Typography>
                <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                  {stats?.totalSessions || 0}
                </Typography>
              </View>
              <View className="flex-row items-center justify-between">
                <Typography className="text-sm text-gray-700 dark:text-gray-300">
                  🔢 {t.superAssistant.totalVectors}
                </Typography>
                <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                  {stats?.totalVectors?.toLocaleString() || 0}
                </Typography>
              </View>
            </View>

            <View className="mt-4 flex-row items-center justify-between">
              <View className="flex-row items-center">
                <View className="w-2 h-2 rounded-full bg-green-500 mr-2" />
                <Typography className="text-xs text-gray-600 dark:text-gray-400">
                  {t.superAssistant.statusOperational}
                </Typography>
              </View>

              <TouchableOpacity
                onPress={handlePruneGhostData}
                className="bg-white/60 dark:bg-black/20 px-3 py-1.5 rounded-full flex-row items-center border border-purple-100 dark:border-purple-500/20"
              >
                <Database size={12} color="#9333ea" className="mr-1.5" />
                <Typography className="text-xs font-bold text-purple-700 dark:text-purple-300">
                  {t.superAssistant.pruneGhostData}
                </Typography>
              </TouchableOpacity>
            </View>
          </View>

          {/* Export */}
          <TouchableOpacity
            activeOpacity={0.7}
            onPress={handleExportCurrent}
            className="flex-row items-center justify-center py-4 bg-indigo-50 dark:bg-indigo-500/10 rounded-2xl mb-8 border border-indigo-100 dark:border-indigo-500/20"
          >
            {isExporting ? (
              <ActivityIndicator size="small" color="#6366f1" />
            ) : (
              <>
                <Download size={18} color="#6366f1" className="mr-2" />
                <Typography className="text-indigo-600 dark:text-indigo-400 font-bold">
                  {t.superAssistant.exportHistory}
                </Typography>
              </>
            )}
          </TouchableOpacity>

          {/* Inference Parameters */}
          <Typography
            variant="label"
            className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3"
          >
            <Sliders size={10} color="#64748b" className="mr-1" />{' '}
            {t.conversation.inferenceSettings}
          </Typography>
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
            <InferenceSettings
              params={session.inferenceParams || {}}
              onUpdate={(params) => updateSessionInferenceParams(SPA_SESSION_ID, params)}
              agentDefaultParams={{ temperature: 0.7 }}
            />
          </View>

          {/* Session Title */}
          <View className="flex-row items-center justify-between mb-3">
            <Typography
              variant="label"
              className="text-gray-400 font-bold uppercase text-[10px] tracking-widest"
            >
              {t.superAssistant.title}
            </Typography>
            <TouchableOpacity
              onPress={handleGenerateTitle}
              disabled={isGeneratingTitle}
              className="flex-row items-center"
            >
              {isGeneratingTitle ? (
                <ActivityIndicator size="small" color="#6366f1" />
              ) : (
                <>
                  <Sparkles size={12} color="#6366f1" className="mr-1" />
                  <Typography className="text-indigo-600 text-[10px] font-bold">
                    {t.superAssistant.aiGenerate}
                  </Typography>
                </>
              )}
            </TouchableOpacity>
          </View>
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
            <TextInput
              className="text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-4 rounded-xl border border-gray-100 dark:border-zinc-800 font-bold"
              value={formData.title}
              onChangeText={(text) => setFormData({ ...formData, title: text })}
              placeholder={t.superAssistant.enterTitle}
              placeholderTextColor="#94a3b8"
            />
          </View>

          {/* FAB Appearance Settings */}
          <Typography
            variant="label"
            className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3"
          >
            🎨 {t.superAssistant.fabAppearance}
          </Typography>
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
            {/* Icon Type */}
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-3">
              {t.superAssistant.iconStyle}
            </Typography>
            <View className="flex-row flex-wrap gap-2 mb-4">
              {PRESET_FAB_ICONS.map((preset) => (
                <TouchableOpacity
                  key={preset.type}
                  onPress={() => {
                    updateFABConfig({ iconType: preset.type, iconColor: preset.color });
                    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                  }}
                  className={`flex-1 min-w-[45%] p-3 rounded-xl ${
                    preferences.fab.iconType === preset.type
                      ? 'bg-indigo-50 dark:bg-indigo-500/20 border-2 border-indigo-500'
                      : 'bg-white dark:bg-black border border-gray-200 dark:border-zinc-800'
                  }`}
                >
                  <View className="flex-row items-center">
                    {renderFABIcon(preset.type, 20, preset.color)}
                    <Typography
                      className={`ml-2 text-sm font-semibold ${
                        preferences.fab.iconType === preset.type
                          ? 'text-indigo-600 dark:text-indigo-400'
                          : 'text-gray-600 dark:text-gray-400'
                      }`}
                    >
                      {(t.superAssistant as any)[preset.labelKey]}
                    </Typography>
                    {preferences.fab.iconType === preset.type && (
                      <Check size={16} color="#6366f1" className="ml-auto" />
                    )}
                  </View>
                </TouchableOpacity>
              ))}
              <TouchableOpacity
                onPress={handlePickCustomIcon}
                className={`flex-1 min-w-[45%] p-3 rounded-xl ${
                  preferences.fab.iconType === 'custom'
                    ? 'bg-indigo-50 dark:bg-indigo-500/20 border-2 border-indigo-500'
                    : 'bg-white dark:bg-black border border-gray-200 dark:border-zinc-800'
                }`}
              >
                <View className="flex-row items-center">
                  {preferences.fab.iconType === 'custom' && preferences.fab.customIconUri ? (
                    renderFABIcon('custom', 20, '#6366f1')
                  ) : (
                    <Upload size={20} color="#6366f1" />
                  )}
                  <Typography
                    className={`ml-2 text-sm font-semibold ${
                      preferences.fab.iconType === 'custom'
                        ? 'text-indigo-600 dark:text-indigo-400'
                        : 'text-gray-600 dark:text-gray-400'
                    }`}
                  >
                    {t.superAssistant.custom}
                  </Typography>
                </View>
              </TouchableOpacity>
            </View>

            <View className="h-[1px] bg-gray-200 dark:bg-zinc-800 my-4" />

            {/* Icon Color */}
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-3">
              {t.superAssistant.iconColor}
            </Typography>
            <View className="flex-row flex-wrap gap-2 mb-4">
              {PRESET_COLORS.map((color) => (
                <TouchableOpacity
                  key={color.value}
                  onPress={() => {
                    updateFABConfig({
                      iconColor: color.value,
                      backgroundColor: color.value, // 同步背景色
                      glowColor: color.value, // 同步光环色
                    });
                    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                  }}
                  className="w-12 h-12 rounded-xl border-2 items-center justify-center"
                  style={{
                    backgroundColor: color.value + '20',
                    borderColor:
                      preferences.fab.iconColor === color.value ? color.value : 'transparent',
                  }}
                >
                  <View className="w-6 h-6 rounded-full" style={{ backgroundColor: color.value }} />
                </TouchableOpacity>
              ))}
            </View>

            <View className="h-[1px] bg-gray-200 dark:bg-zinc-800 my-4" />

            {/* Animation Mode Selector */}
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100 mb-3">
              {t.superAssistant.animationMode || 'Animation Mode'}
            </Typography>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} className="mb-4">
              <View className="flex-row gap-3">
                {ANIMATION_MODES.map((mode) => (
                  <TouchableOpacity
                    key={mode.mode}
                    onPress={() => {
                      updateFABConfig({ animationMode: mode.mode });
                      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    }}
                    className={`px-4 py-3 rounded-xl items-center justify-center min-w-[100px] ${
                      preferences.fab.animationMode === mode.mode
                        ? 'bg-indigo-50 dark:bg-indigo-500/20 border-2 border-indigo-500'
                        : 'bg-white dark:bg-black border border-gray-200 dark:border-zinc-800'
                    }`}
                  >
                    {(LucideIcons as any)[mode.icon] &&
                      React.createElement((LucideIcons as any)[mode.icon], {
                        size: 20,
                        color:
                          preferences.fab.animationMode === mode.mode
                            ? '#6366f1'
                            : isDark
                              ? '#9ca3af'
                              : '#6b7280',
                      })}
                    <Typography
                      className={`mt-2 text-xs font-bold ${
                        preferences.fab.animationMode === mode.mode
                          ? 'text-indigo-600 dark:text-indigo-400'
                          : 'text-gray-600 dark:text-gray-400'
                      }`}
                    >
                      {mode.label}
                    </Typography>
                  </TouchableOpacity>
                ))}
              </View>
            </ScrollView>

            <View className="h-[1px] bg-gray-200 dark:bg-zinc-800 my-4" />

            {/* Animation Toggles */}
            <View className="flex-row items-center justify-between py-2 mb-2">
              <View className="flex-1 pr-4">
                <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                  {t.superAssistant.rotationAnim}
                </Typography>
                <Typography variant="caption" className="text-gray-500 mt-1">
                  {t.superAssistant.rotationAnimDesc}
                </Typography>
              </View>
              <Switch
                value={preferences.fab.enableRotation}
                onValueChange={(val) => updateFABConfig({ enableRotation: val })}
              />
            </View>

            <View className="h-[1px] bg-gray-200 dark:bg-zinc-800 my-2" />

            <View className="flex-row items-center justify-between py-2">
              <View className="flex-1 pr-4">
                <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                  {t.superAssistant.glowEffect}
                </Typography>
                <Typography variant="caption" className="text-gray-500 mt-1">
                  {t.superAssistant.glowEffectDesc}
                </Typography>
              </View>
              <Switch
                value={preferences.fab.enableGlow}
                onValueChange={(val) => updateFABConfig({ enableGlow: val })}
              />
            </View>

            {/* Glow Color removed as per user request (unified with Icon Color) */}
          </View>

          {/* RAG 配置入口 */}
          <Typography
            variant="label"
            className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mb-3"
          >
            <Database size={10} color="#64748b" className="mr-1" /> {t.settings.ragSection}
          </Typography>
          <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl border border-gray-100 dark:border-zinc-800 mb-8">
            <TouchableOpacity
              onPress={() => {
                setTimeout(() => {
                  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                  router.push('/chat/super_assistant/rag-config' as any);
                }, 10);
              }}
              className="flex-row items-center justify-between p-5"
            >
              <View className="flex-1">
                <Typography className="text-gray-900 dark:text-white font-bold mb-1">
                  {t.settings.ragSettings}
                </Typography>
                <Typography className="text-gray-500 dark:text-gray-400 text-sm">
                  {t.settings.ragSettingsDesc}
                </Typography>
              </View>
              <ChevronRight size={20} color="#9ca3af" />
            </TouchableOpacity>

            <View className="border-t border-gray-100 dark:border-zinc-800" />

            <TouchableOpacity
              onPress={() => {
                setTimeout(() => {
                  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                  router.push('/chat/super_assistant/advanced-retrieval' as any);
                }, 10);
              }}
              className="flex-row items-center justify-between p-5"
            >
              <View className="flex-1">
                <Typography className="text-gray-900 dark:text-white font-bold mb-1">
                  {t.rag.advancedSettings}
                </Typography>
                <Typography className="text-gray-500 dark:text-gray-400 text-sm">
                  {t.rag.advancedSettingsDesc}
                </Typography>
              </View>
              <ChevronRight size={20} color="#9ca3af" />
            </TouchableOpacity>
          </View>

          {/* Context Management */}
          <ContextManagementPanel sessionId={SPA_SESSION_ID} />

          {/* Danger Zone */}
          <Typography
            variant="label"
            className="text-red-400 font-bold uppercase text-[10px] tracking-widest mb-3"
          >
            {t.common.dangerZone}
          </Typography>
          <View className="bg-red-50 dark:bg-red-900/10 rounded-3xl p-5 border border-red-100 dark:border-red-900/20 mb-10">
            <TouchableOpacity
              onPress={handleDeleteSession}
              className="flex-row items-center justify-between"
            >
              <View className="flex-row items-center">
                <View className="w-10 h-10 rounded-full bg-red-100 dark:bg-red-900/20 items-center justify-center mr-3">
                  <Trash2 size={20} color="#ef4444" />
                </View>
                <View>
                  <Typography className="text-red-600 dark:text-red-400 font-bold">
                    {t.superAssistant.deleteSession}
                  </Typography>
                  <Typography variant="caption" className="text-red-400/80">
                    {t.superAssistant.deleteSessionDesc}
                  </Typography>
                </View>
              </View>
            </TouchableOpacity>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>

      <ConfirmDialog
        visible={confirmState.visible}
        title={confirmState.title}
        message={confirmState.message}
        isDestructive={confirmState.isDestructive}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
      />
    </PageLayout>
  );
}

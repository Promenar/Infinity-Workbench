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
import { clsx } from 'clsx';
import {
  PageLayout,
  Typography,
  GlassHeader,
  useToast,
  ConfirmDialog,
  Switch,
  SettingsSection,
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
  Network,
  Zap,
  RotateCcw,
} from 'lucide-react-native';
import * as Haptics from '../../../src/lib/haptics';
import { useChatStore } from '../../../src/store/chat-store';
import { useTheme } from '../../../src/theme/ThemeProvider';
import { useI18n } from '../../../src/lib/i18n';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { exportAllSessionsToTxt } from '../../../src/features/chat/utils/export';
import { useSPAStore } from '../../../src/store/spa-store';
import {
  PRESET_FAB_ICONS,
  PRESET_COLORS,
  FABIconType,
} from '../../../src/types/super-assistant';
import * as ImagePicker from 'expo-image-picker';
import * as LucideIcons from 'lucide-react-native';
import { Image } from 'expo-image';
import { vectorStore } from '../../../src/lib/rag/vector-store';
import { InferenceSettings } from '../../../src/components/chat/InferenceSettings';
import { ContextManagementPanel } from '../../../src/features/chat/settings/ContextManagementPanel';
import { useDebounce } from '../../../src/hooks/useDebounce';
import { ColorPickerPanel } from '../../../src/components/ui/ColorPickerPanel';
import CollapsibleSection from '../../../src/components/ui/CollapsibleSection';

const SPA_SESSION_ID = 'super_assistant';

// SectionHeader and SubHeader removed to use standard SettingsSection

interface FABIconProps {
  type: FABIconType;
  size: number;
  color: string;
  customIconUri?: string | null;
}

const FABIconRenderer = React.memo(({ type, size, color, customIconUri }: FABIconProps) => {
  if (type === 'custom' && customIconUri) {
    return (
      <Image
        source={{ uri: customIconUri }}
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
});

const FABIconGrid = React.memo(({
  preferences,
  isDark,
  onSelect,
  onCustomSelect,
  customLabel,
  themeColors
}: {
  preferences: any;
  isDark: boolean;
  onSelect: (type: FABIconType) => void;
  onCustomSelect: () => void;
  customLabel: string;
  themeColors: any;
}) => {
  // Robust Negative Margin Layout Strategy
  // Container has marginRight: -8 to pull right boundary
  // Items are exactly 25% width
  // Items have paddingRight: 8 to create the gap
  // This ensures perfect alignment regardless of container width
  const gap = 8;

  return (
    <View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', marginRight: -gap }}>
        {PRESET_FAB_ICONS.map((preset, index) => {
          const isSelected = preferences.fab.iconType === preset.type;

          return (
            <View
              key={preset.type}
              style={{ width: '16.66%', paddingRight: gap, marginBottom: gap }}
            >
              <TouchableOpacity
                onPress={() => onSelect(preset.type)}
                style={{
                  width: '100%',
                  aspectRatio: 1,
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRadius: 12,
                  backgroundColor: isSelected
                    ? (isDark ? themeColors.opacity20 : themeColors.opacity10)
                    : (isDark ? '#000000' : '#ffffff'),
                  borderWidth: isSelected ? 2 : 1,
                  borderColor: isSelected
                    ? themeColors[500]
                    : (isDark ? '#27272a' : '#e5e7eb'),
                  ...((isSelected && !isDark) ? { shadowColor: themeColors[500], shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.2, shadowRadius: 2, elevation: 2 } : {})
                }}
              >
                <FABIconRenderer
                  type={preset.type}
                  size={20}
                  color={isSelected ? themeColors[600] : isDark ? '#9ca3af' : '#6b7280'}
                  customIconUri={preferences.fab.customIconUri}
                />
                {isSelected && (
                  <View style={{ position: 'absolute', top: 4, right: 4 }}>
                    <Check size={8} color={themeColors[600]} />
                  </View>
                )}
              </TouchableOpacity>
            </View>
          );
        })}
      </View>

      <TouchableOpacity
        onPress={onCustomSelect}
        style={{
          width: '100%',
          paddingVertical: 16,

          marginTop: 0, // Reset margin since grid has internal marginBottom

          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'center',
          borderRadius: 16,
          backgroundColor: preferences.fab.iconType === 'custom'
            ? (isDark ? themeColors.opacity20 : themeColors.opacity10)
            : (isDark ? '#000000' : '#ffffff'),
          borderWidth: preferences.fab.iconType === 'custom' ? 2 : 1,
          borderColor: preferences.fab.iconType === 'custom'
            ? themeColors[500]
            : (isDark ? '#27272a' : '#e5e7eb')
        }}
      >
        {preferences.fab.iconType === 'custom' && preferences.fab.customIconUri ? (
          <FABIconRenderer type="custom" size={20} color={themeColors[600]} customIconUri={preferences.fab.customIconUri} />
        ) : (
          <Upload size={20} color={themeColors[600]} style={{ marginRight: 8 }} />
        )}
        <Typography style={{
          fontWeight: '600',
          color: preferences.fab.iconType === 'custom' ? themeColors[600] : '#6b7280'
        }}>
          {customLabel}
        </Typography>
      </TouchableOpacity>
    </View>
  );
});

const FABColorGrid = React.memo(({
  preferences,
  isDark,
  onSelect
}: {
  preferences: any;
  isDark: boolean;
  onSelect: (color: string) => void;
}) => {
  const gap = 8;

  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', marginRight: -gap }}>
      {PRESET_COLORS.map((color, index) => {
        const isSelected = preferences.fab.iconColor === color.value;

        return (
          <View
            key={color.value}
            style={{ width: '16.66%', paddingRight: gap, marginBottom: gap }}
          >
            <TouchableOpacity
              onPress={() => onSelect(color.value)}
              style={{
                width: '100%',
                aspectRatio: 1,
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: 12,
                borderWidth: 2,
                backgroundColor: color.value + '10',
                borderColor: isSelected ? color.value : isDark ? '#18181b' : '#f4f4f5',
              }}
            >
              <View
                style={{
                  width: 20,
                  height: 20,
                  borderRadius: 10,
                  backgroundColor: color.value,
                  ...(!isDark ? { shadowColor: "#000", shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.1, shadowRadius: 2, elevation: 1 } : {})
                }}
              />
              {isSelected && (
                <View style={{ position: 'absolute', top: 4, right: 4 }}>
                  <Check size={8} color={color.value} />
                </View>
              )}
            </TouchableOpacity>
          </View>
        );
      })}
    </View>
  );
});

export default function SuperAssistantSettingsScreen() {
  const router = useRouter();
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const { showToast } = useToast();

  const {
    getSession,
    updateSessionTitle,
    updateSessionInferenceParams,
    deleteSession,
    updateSessionOptions,
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

      showToast(t.agent.superAssistant.pruneSuccess, 'success');
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);

      // 刷新统计
      updateRAGStats();
    } catch (error) {
      console.error(error);
      showToast(t.agent.superAssistant.pruneFail, 'error');
    }
  };

  const session = getSession(SPA_SESSION_ID);

  const [formData, setFormData] = useState({
    title: session?.title || t.agent.superAssistant.defaultTitle,
  });

  const [isExporting, setIsExporting] = useState(false);

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
    onConfirm: () => { },
    isDestructive: false,
  });

  // Auto-save title
  const debouncedTitle = useDebounce(formData.title, 1000);

  useEffect(() => {
    if (session && debouncedTitle !== session.title) {
      updateSessionTitle(SPA_SESSION_ID, debouncedTitle.trim());
    }
  }, [debouncedTitle, session?.title]);

  // 🪄 Migration: Auto-update legacy titles to new localized brand (Nexus Hub / 万象中枢)
  useEffect(() => {
    if (session) {
      const legacyTitles = ['Super Personal Assistant', 'Super Assistant', '超级助手会话'];
      // Exact match check to avoid overwriting user customizations
      if (legacyTitles.includes(session.title)) {
        const newTitle = t.agent.superAssistant.defaultTitle;
        console.log(`[SPA] Migrating legacy title "${session.title}" to "${newTitle}"`);
        updateSessionTitle(SPA_SESSION_ID, newTitle);
        setFormData(prev => ({ ...prev, title: newTitle }));
      }
    }
  }, []); // Run once on mount (or when session loads)

  const handleExportCurrent = async () => {
    if (isExporting || !session) return;
    setIsExporting(true);
    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);

    try {
      const result = await exportAllSessionsToTxt([session]);
      if (result.success) {
        setConfirmState({
          visible: true,
          title: t.agent.superAssistant.exportSuccess,
          message: t.agent.superAssistant.exportSuccess,
          onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
        });
      } else if (result.error !== 'Permission denied') {
        setConfirmState({
          visible: true,
          title: t.agent.superAssistant.exportFail,
          message: result.error || 'Unknown error',
          onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
        });
      }
    } catch (error) {
      setConfirmState({
        visible: true,
        title: t.agent.superAssistant.exportFail,
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
      title: t.agent.superAssistant.deleteSession,
      message: t.agent.superAssistant.deleteSessionDesc,
      isDestructive: true,
      onConfirm: async () => {
        await deleteSession(SPA_SESSION_ID);
        showToast(t.agent.superAssistant.sessionDeleted, 'success');
        setConfirmState((prev) => ({ ...prev, visible: false }));
        router.replace('/(tabs)/chat');
      },
    });
  };

  const handleIconSelect = React.useCallback((type: FABIconType) => {
    updateFABConfig({ iconType: type });
    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
  }, [updateFABConfig]);

  const handleColorSelect = React.useCallback((color: string) => {
    updateFABConfig({
      iconColor: color,
      backgroundColor: color,
      glowColor: color,
    });
    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
  }, [updateFABConfig]);

  const handlePickCustomIcon = React.useCallback(async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      allowsEditing: false,
      quality: 0.8,
    });

    if (!result.canceled && result.assets[0]) {
      updateFABConfig({
        iconType: 'custom',
        customIconUri: result.assets[0].uri,
      });
    }
  }, [updateFABConfig]);

  if (!session) return null;

  const stats = preferences.ragStats;

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.agent.superAssistant.title}
        subtitle={formData.title}
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
      >
        <ScrollView
          className="flex-1 px-6"
          contentContainerStyle={{
            paddingTop: 74 + insets.top,
            paddingBottom: 40,
          }}
          showsVerticalScrollIndicator={false}
        >




          <SettingsSection title={t.agent.basicInfo}>
            <View className="px-4 pb-4">
              <TextInput
                className="text-gray-600 dark:text-gray-300 bg-white/50 dark:bg-black/20 px-4 py-3 rounded-xl border border-indigo-50 dark:border-indigo-500/10 font-bold"
                value={formData.title}
                onChangeText={(text) => setFormData({ ...formData, title: text })}
                placeholder={t.agent.superAssistant.enterTitle}
                placeholderTextColor="#94a3b8"
              />
            </View>
          </SettingsSection>

          {/* FAB 外观设置 */}
          <CollapsibleSection
            title={t.agent.superAssistant.fabAppearance}
            defaultOpen={false}
            icon={<Sparkles size={20} color={preferences.fab.iconColor} />}
            rightElement={
              <View className="flex-row items-center gap-2">
                <View style={{ backgroundColor: isDark ? '#000' : '#fff', borderColor: preferences.fab.iconColor }} className="w-8 h-8 rounded-full items-center justify-center border border-opacity-20">
                  <FABIconRenderer
                    type={preferences.fab.iconType}
                    size={16}
                    color={preferences.fab.iconColor}
                    customIconUri={preferences.fab.customIconUri}
                  />
                </View>
                <View style={{ backgroundColor: preferences.fab.iconColor }} className="w-4 h-4 rounded-full" />
              </View>
            }
          >
            <View className="px-1 py-2">
              {/* 图标样式 Grid (4列 对齐) */}
              <Typography className="text-xs font-bold text-gray-500 dark:text-gray-400 mb-3 uppercase tracking-wider">
                {t.agent.superAssistant.iconStyle}
              </Typography>
              <FABIconGrid
                preferences={preferences}
                isDark={isDark}
                onSelect={handleIconSelect}
                onCustomSelect={handlePickCustomIcon}
                customLabel={t.agent.superAssistant.custom}
                themeColors={colors}
              />

              <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-6" />

              {/* 图标颜色 Panel */}
              <View className="mb-2">
                <ColorPickerPanel
                  color={preferences.fab.iconColor}
                  onColorChange={handleColorSelect}
                  title={t.agent.superAssistant.iconColor}
                />
              </View>

              <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-6" />

              {/* 动画开关 */}
              <View className="flex-row items-center justify-between py-2 mb-2">
                <View className="flex-1 pr-4">
                  <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                    {t.agent.superAssistant.rotationAnim}
                  </Typography>
                  <Typography variant="caption" className="text-gray-500 mt-1">
                    {t.agent.superAssistant.rotationAnimDesc}
                  </Typography>
                </View>
                <Switch
                  value={preferences.fab.enableRotation}
                  onValueChange={(val) => updateFABConfig({ enableRotation: val })}
                />
              </View>

              <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 my-2" />

              <View className="flex-row items-center justify-between py-2">
                <View className="flex-1 pr-4">
                  <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                    {t.agent.superAssistant.glowEffect}
                  </Typography>
                  <Typography variant="caption" className="text-gray-500 mt-1">
                    {t.agent.superAssistant.glowEffectDesc}
                  </Typography>
                </View>
                <Switch
                  value={preferences.fab.enableGlow}
                  onValueChange={(val) => updateFABConfig({ enableGlow: val })}
                />
              </View>
            </View>
          </CollapsibleSection>

          {/* 模型配置区块 */}
          <SettingsSection title={t.agent.modelConfig}>
            <View className="p-4">
              <InferenceSettings
                params={session.inferenceParams || {}}
                onUpdate={(params) => updateSessionInferenceParams(SPA_SESSION_ID, params)}
              />
            </View>
          </SettingsSection>

          {/* Knowledge Graph Entry (Moved) */}
          <SettingsSection title={t.rag.knowledgeGraph}>
            <View className="p-5">
              {/* Enable Toggle */}
              <View className="flex-row items-center justify-between pb-2">
                <View className="flex-1 mr-4">
                  <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                    {t.agent.superAssistant.kgEnableTitle}
                  </Typography>
                  <Typography variant="caption" className="text-gray-500 dark:text-gray-400 mt-0.5">
                    {t.agent.superAssistant.kgEnableDesc}
                  </Typography>
                </View>
                <Switch
                  value={!!session.ragOptions?.enableKnowledgeGraph}
                  onValueChange={(val) => {
                    setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                    updateSessionOptions(SPA_SESSION_ID, {
                      ragOptions: { ...session.ragOptions, enableKnowledgeGraph: val } as any
                    });
                  }}
                />
              </View>

              {/* View Link */}
              <TouchableOpacity
                onPress={() => router.push({ pathname: '/knowledge-graph', params: { sessionId: 'super_assistant' } })}
                style={{ backgroundColor: isDark ? 'rgba(0,0,0,0.2)' : 'rgba(255,255,255,0.5)' }}
                className="mt-4 flex-row items-center justify-between p-4 rounded-2xl border border-indigo-100/50 dark:border-indigo-400/10"
              >
                <View className="flex-row items-center gap-3">
                  <View style={{ backgroundColor: colors.opacity10 }} className="w-10 h-10 rounded-full items-center justify-center">
                    <Network size={20} color={colors[600]} />
                  </View>
                  <View>
                    <Typography style={{ color: colors[900] }} className="text-sm font-bold">
                      {t.agent.superAssistant.kgViewTitle}
                    </Typography>
                    <Typography variant="caption" className="text-gray-500 dark:text-gray-400">
                      {t.agent.superAssistant.kgViewDesc}
                    </Typography>
                  </View>
                </View>
                <ChevronRight size={18} color={colors[600]} />
              </TouchableOpacity>
            </View>
          </SettingsSection>

          {/* RAG 配置入口 */}
          <SettingsSection title={t.settings.ragSection}>
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
              <ChevronRight size={18} color="#9ca3af" />
            </TouchableOpacity>

            <View className="h-[1px] bg-gray-100 dark:bg-zinc-800/50 mx-5" />

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
              <ChevronRight size={18} color="#9ca3af" />
            </TouchableOpacity>
          </SettingsSection>

          {/* 上下文管理 (Self-contained headers) */}
          <ContextManagementPanel sessionId={SPA_SESSION_ID} />



          <SettingsSection title={t.agent.superAssistant.globalKnowledge}>
            <View className="p-5">
              <View className="flex-row items-center mb-4">
                <View style={{ backgroundColor: colors[500] }} className="w-10 h-10 rounded-full items-center justify-center mr-3">
                  <Sparkles size={20} color="#fff" />
                </View>
                <View className="flex-1">
                  <Typography style={{ color: colors[900] }} className="text-lg font-bold dark:text-indigo-100">
                    {t.agent.superAssistant.globalRagEnabled}
                  </Typography>
                  <Typography variant="caption" style={{ color: colors[600] }} className="dark:text-indigo-300">
                    {t.agent.superAssistant.accessAllData}
                  </Typography>
                </View>
              </View>

              <View className="bg-white/50 dark:bg-black/20 rounded-2xl p-4 space-y-2 mb-4">
                <View className="flex-row items-center justify-between">
                  <Typography className="text-sm text-gray-700 dark:text-gray-300">
                    📄 {t.agent.superAssistant.docCount}
                  </Typography>
                  <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                    {stats?.totalDocuments || 0}
                  </Typography>
                </View>
                <View className="flex-row items-center justify-between">
                  <Typography className="text-sm text-gray-700 dark:text-gray-300">
                    💬 {t.agent.superAssistant.sessionMemory}
                  </Typography>
                  <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                    {stats?.totalSessions || 0}
                  </Typography>
                </View>
                <View className="flex-row items-center justify-between">
                  <Typography className="text-sm text-gray-700 dark:text-gray-300">
                    🔢 {t.agent.superAssistant.totalVectors}
                  </Typography>
                  <Typography className="text-sm font-bold text-gray-900 dark:text-white">
                    {stats?.totalVectors?.toLocaleString() || 0}
                  </Typography>
                </View>
              </View>

              <View className="flex-row items-center justify-between">
                <View className="flex-row items-center">
                  <View className="w-2 h-2 rounded-full bg-green-500 mr-2" />
                  <Typography className="text-xs text-gray-600 dark:text-gray-400">
                    {t.agent.superAssistant.statusOperational}
                  </Typography>
                </View>

                <TouchableOpacity
                  onPress={handlePruneGhostData}
                  style={{ backgroundColor: colors.opacity10, borderColor: colors.opacity20 }}
                  className="px-3 py-1.5 rounded-full flex-row items-center border"
                >
                  <Database size={12} color={colors[600]} className="mr-1.5" />
                  <Typography style={{ color: colors[700] }} className="text-xs font-bold">
                    {t.agent.superAssistant.pruneGhostData}
                  </Typography>
                </TouchableOpacity>
              </View>
            </View>
          </SettingsSection>

          {/* 导出历史记录 */}
          <SettingsSection title={t.agent.superAssistant.exportHistory}>
            <TouchableOpacity
              activeOpacity={0.7}
              onPress={handleExportCurrent}
              className="flex-row items-center justify-center py-4"
            >
              {isExporting ? (
                <ActivityIndicator size="small" color={colors[500]} />
              ) : (
                <>
                  <Download size={18} color={colors[600]} className="mr-2" />
                  <Typography style={{ color: colors[600] }} className="font-bold">
                    {t.agent.superAssistant.exportHistory}
                  </Typography>
                </>
              )}
            </TouchableOpacity>
          </SettingsSection>

          {/* 危险区域 */}
          <SettingsSection title={t.common.dangerZone}>
            <TouchableOpacity
              onPress={handleDeleteSession}
              className="flex-row items-center justify-between p-5"
            >
              <View className="flex-row items-center">
                <View className="w-10 h-10 rounded-full bg-red-100 dark:bg-red-900/20 items-center justify-center mr-3">
                  <Trash2 size={20} color="#ef4444" />
                </View>
                <View>
                  <Typography className="text-red-600 dark:text-red-400 font-bold">
                    {t.agent.superAssistant.deleteSession}
                  </Typography>
                  <Typography variant="caption" className="text-red-400/80">
                    {t.agent.superAssistant.deleteSessionDesc}
                  </Typography>
                </View>
              </View>
            </TouchableOpacity>
          </SettingsSection>
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
    </PageLayout >
  );
}

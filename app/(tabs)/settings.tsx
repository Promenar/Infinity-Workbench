import React, { useState } from 'react';
import { View, Text, TouchableOpacity, ScrollView } from 'react-native';
import Animated, { FadeIn, FadeOut, LinearTransition, useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { PageLayout, Switch, LargeTitleHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
// import * as Haptics from '../../src/lib/haptics';
// Removed in favor of wrapper
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';
import { BackupSettings } from '../../src/features/settings/BackupSettings';
import { useToast } from '../../src/components/ui/Toast';
import { useSettingsStore } from '../../src/store/settings-store';
import { useApiStore, ProviderConfig, ModelConfig } from '../../src/store/api-store';
import { clsx } from 'clsx';
import { SettingsSection } from '../../src/features/settings/components/SettingsSection';
import { SettingsItem } from '../../src/features/settings/components/SettingsItem';
import { Colors } from '../../src/theme/colors';
import {
  ChevronRight,
  Globe,
  MessageSquare,
  Zap,
  Cpu,
  Database,
  Sparkles,
  Trash2,
  HardDrive,
  Info,
  Sliders,
  Moon,
  Bell,
  Plus,
  Server,
  Edit2,
  FileText,
  Mic,
  Layers,
  Sun,
  Monitor,
  ArrowUpDown,
  BarChart2,
  Calendar,
  Settings as SettingsIcon,
  Palette,
  Image as ImageIcon,
} from 'lucide-react-native';

import { ProviderModal } from '../../src/features/settings/ProviderModal';
import { ModelSettingsModal } from '../../src/features/settings/ModelSettingsModal';
import { ModelPicker } from '../../src/features/settings/ModelPicker';
import { GlobalRagConfigPanel } from '../../src/features/settings/components/GlobalRagConfigPanel';
import * as Haptics from '../../src/lib/haptics'; // Import wrapper
import Constants from 'expo-constants';

export default function SettingsScreen() {
  const router = useRouter();
  const [activeTab, setActiveTab] = useState<'app' | 'providers'>('app');
  const [eggCount, setEggCount] = useState(0);
  const [modalVisible, setModalVisible] = useState(false);
  const [modelModalVisible, setModelModalVisible] = useState(false);
  const [pickerVisible, setPickerVisible] = useState(false);
  const [pickerConfig, setPickerConfig] = useState<{
    title: string;
    key:
    | 'defaultSummaryModel'
    | 'defaultSpeechModel'
    | 'defaultEmbeddingModel'
    | 'defaultRerankModel'
    | 'defaultImageModel';
    filterType?: 'chat' | 'reasoning' | 'image' | 'embedding' | 'rerank';
  }>({ title: '', key: 'defaultSummaryModel' });

  const [editingProvider, setEditingProvider] = useState<ProviderConfig | null>(null);
  const [activeProviderForModels, setActiveProviderForModels] = useState<ProviderConfig | null>(
    null,
  );
  const { t } = useI18n();
  const { theme, setTheme, isDark, colors } = useTheme();
  const { showToast } = useToast();
  const {
    language,
    setLanguage,
    defaultSummaryModel,
    defaultSpeechModel,
    defaultEmbeddingModel,
    defaultRerankModel,
    defaultImageModel,
    updateDefaultModel,
    hapticsEnabled,
    setHapticsEnabled, // New settings
  } = useSettingsStore();
  const { providers, deleteProvider, addProvider, updateProvider } = useApiStore();

  // 辅助函数：通过 UUID 获取模型名称
  const getModelName = (uuid?: string) => {
    if (!uuid) return t.settings.modelPresets.none;
    for (const p of providers) {
      const m = p.models.find((model) => model.uuid === uuid);
      if (m) return m.name;
    }
    return t.settings.modelPresets.none;
  };

  return (
    <PageLayout className="bg-white dark:bg-black" safeArea={false}>
      <Stack.Screen options={{ headerShown: false }} />

      {/* Fixed Title Header */}
      <LargeTitleHeader title={t.settings.title} subtitle={t.settings.subtitle} />

      <ScrollView
        style={{ flex: 1, paddingHorizontal: 16 }} // Reduced 24 -> 16
        contentContainerStyle={{ paddingBottom: 100 }}
        showsVerticalScrollIndicator={false}
      >
        {/* 标签切换器 */}
        <View
          style={{
            flexDirection: 'row',
            backgroundColor: isDark ? 'rgba(24, 24, 27, 0.8)' : '#f3f4f6',
            padding: 4,
            borderRadius: 16,
            marginBottom: 32,
          }}
        >
          <TouchableOpacity
            onPress={() => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                setActiveTab('app');
              }, 10);
            }}
            style={{
              flex: 1,
              paddingVertical: 12,
              borderRadius: 12,
              alignItems: 'center',
              backgroundColor:
                activeTab === 'app'
                  ? isDark
                    ? 'rgba(39, 39, 42, 0.9)'
                    : '#ffffff'
                  : 'transparent',
            }}
          >
            <Text
              style={{
                fontWeight: 'bold',
                color:
                  activeTab === 'app'
                    ? isDark
                      ? '#fff'
                      : '#111'
                    : isDark
                      ? Colors.dark.textSecondary
                      : '#9ca3af',
              }}
            >
              {t.settings.appSettings}
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            onPress={() => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                setActiveTab('providers');
              }, 10);
            }}
            style={{
              flex: 1,
              paddingVertical: 12,
              borderRadius: 12,
              alignItems: 'center',
              backgroundColor:
                activeTab === 'providers'
                  ? isDark
                    ? 'rgba(39, 39, 42, 0.9)'
                    : '#ffffff'
                  : 'transparent',
            }}
          >
            <Text
              style={{
                fontWeight: 'bold',
                color:
                  activeTab === 'providers'
                    ? isDark
                      ? '#fff'
                      : '#111'
                    : isDark
                      ? Colors.dark.textSecondary
                      : '#9ca3af',
              }}
            >
              {t.settings.providerSettings}
            </Text>
          </TouchableOpacity>
        </View>

        {activeTab === 'app' ? (
          <View key="app-tab-wrapper">
            <Animated.View
              entering={FadeIn.duration(300)}
              layout={LinearTransition.duration(300)}
            >
              <SettingsSection title={t.settings.basicSettings}>
                <SettingsItem
                  icon={Globe}
                  title={t.settings.language}
                  subtitle={language === 'zh' ? '简体中文' : 'English'}
                  rightElement={
                    <View
                      style={{
                        flexDirection: 'row',
                        backgroundColor: isDark ? '#27272a' : '#e5e7eb',
                        borderRadius: 16,
                        padding: 4,
                      }}
                    >
                      <TouchableOpacity
                        onPress={() => {
                          setTimeout(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                            setLanguage('zh');
                          }, 10);
                        }}
                        style={{
                          paddingHorizontal: 12,
                          paddingVertical: 4,
                          borderRadius: 16,
                          backgroundColor:
                            language === 'zh' ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent',
                        }}
                      >
                        <Text
                          style={{
                            fontSize: 12,
                            fontWeight: 'bold',
                            color: language === 'zh' ? colors[500] : '#9ca3af',
                          }}
                        >
                          中
                        </Text>
                      </TouchableOpacity>
                      <TouchableOpacity
                        onPress={() => {
                          setTimeout(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                            setLanguage('en');
                          }, 10);
                        }}
                        style={{
                          paddingHorizontal: 12,
                          paddingVertical: 4,
                          borderRadius: 16,
                          backgroundColor:
                            language === 'en' ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent',
                        }}
                      >
                        <Text
                          style={{
                            fontSize: 12,
                            fontWeight: 'bold',
                            color: language === 'en' ? colors[500] : '#9ca3af',
                          }}
                        >
                          EN
                        </Text>
                      </TouchableOpacity>
                    </View>
                  }
                />

                <SettingsItem
                  icon={Moon}
                  title={t.settings.appearance}
                  subtitle={
                    theme === 'system'
                      ? t.settings.themeSystem
                      : isDark
                        ? t.settings.themeDark
                        : t.settings.themeLight
                  }
                  rightElement={
                    <View
                      style={{
                        flexDirection: 'row',
                        backgroundColor: isDark ? '#27272a' : '#e5e7eb',
                        borderRadius: 16,
                        padding: 2,
                      }}
                    >
                      {[
                        { mode: 'light', icon: Sun },
                        { mode: 'system', icon: Monitor },
                        { mode: 'dark', icon: Moon },
                      ].map((item) => {
                        const m = item.mode as any;
                        const Icon = item.icon;
                        return (
                          <TouchableOpacity
                            key={m}
                            onPress={() => {
                              setTimeout(() => {
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                setTheme(m);
                              }, 10);
                            }}
                            style={{
                              paddingHorizontal: 12,
                              paddingVertical: 8,
                              borderRadius: 16,
                              backgroundColor:
                                theme === m ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent',
                              alignItems: 'center',
                              justifyContent: 'center',
                            }}
                          >
                            <Icon size={14} color={theme === m ? colors[500] : '#9ca3af'} />
                          </TouchableOpacity>
                        );
                      })}
                    </View>
                  }
                />

                <SettingsItem
                  icon={Palette}
                  title={t.settings.themeColor || '主题颜色'}
                  subtitle={t.settings.personalizationDesc}
                  showChevron
                  onPress={() => router.push('/settings/theme' as any)}
                />

                <SettingsItem
                  icon={Zap}
                  title={t.settings.haptics || 'Haptic Feedback'}
                  subtitle={t.settings.hapticsDesc || 'Enable vibration feedback'}
                  rightElement={
                    <Switch
                      value={hapticsEnabled}
                      onValueChange={(v) => {
                        setHapticsEnabled(v);
                      }}
                    />
                  }
                />

                <SettingsItem
                  icon={Globe}
                  title={t.settings.webSearchConfig}
                  subtitle={t.settings.webSearchConfigDesc}
                  showChevron
                  isLast
                  onPress={() => router.push('/settings/search')}
                />
              </SettingsSection>

              <SettingsSection title={t.settings.modelPresets.title}>
                <SettingsItem
                  icon={FileText}
                  title={t.settings.modelPresets.summary}
                  subtitle={getModelName(defaultSummaryModel)}
                  showChevron
                  onPress={() => {
                    setPickerConfig({
                      title: t.settings.modelPresets.summary,
                      key: 'defaultSummaryModel',
                      filterType: 'chat',
                    });
                    setPickerVisible(true);
                  }}
                />

                {/* 暂时隐藏语音处理服务配置 */}
                {/* <SettingsItem
                icon={Mic}
                title={t.settings.modelPresets.speech}
                subtitle={getModelName(defaultSpeechModel)}
                showChevron
                onPress={() => {
                  setPickerConfig({
                    title: t.settings.modelPresets.speech,
                    key: 'defaultSpeechModel',
                    filterType: 'chat',
                  });
                  setPickerVisible(true);
                }}
              /> */}

                <SettingsItem
                  icon={ImageIcon}
                  title={t.settings.modelPresets.image || 'Image Generation'}
                  subtitle={getModelName(defaultImageModel)}
                  showChevron
                  onPress={() => {
                    setPickerConfig({
                      title: t.settings.modelPresets.image || 'Image Generation',
                      key: 'defaultImageModel',
                      filterType: 'image',
                    });
                    setPickerVisible(true);
                  }}
                />

                <SettingsItem
                  icon={Layers}
                  title={t.settings.modelPresets.embedding}
                  subtitle={getModelName(defaultEmbeddingModel)}
                  showChevron
                  onPress={() => {
                    setPickerConfig({
                      title: t.settings.modelPresets.embedding,
                      key: 'defaultEmbeddingModel',
                      filterType: 'embedding',
                    });
                    setPickerVisible(true);
                  }}
                />

                <SettingsItem
                  icon={ArrowUpDown}
                  title={t.settings.modelPresets.rerank}
                  subtitle={getModelName(defaultRerankModel)}
                  showChevron
                  isLast
                  onPress={() => {
                    setPickerConfig({
                      title: t.settings.modelPresets.rerank,
                      key: 'defaultRerankModel',
                      filterType: 'rerank',
                    });
                    setPickerVisible(true);
                  }}
                />
              </SettingsSection>

              <SettingsSection title={t.settings.ragSection}>
                <SettingsItem
                  icon={Database}
                  title={t.settings.ragSection}
                  subtitle={t.settings.ragSettingsDesc}
                  showChevron
                  onPress={() => router.push('/settings/rag-config' as any)}
                />

                <SettingsItem
                  icon={Sliders}
                  title={t.rag.advancedSettings}
                  subtitle={t.rag.advancedSettingsDesc}
                  showChevron
                  onPress={() => router.push('/settings/advanced-retrieval' as any)}
                />

                <SettingsItem
                  icon={BarChart2}
                  title={t.settings.tokenUsage || '流量消耗统计'}
                  subtitle={t.settings.tokenUsageDesc}
                  showChevron
                  isLast
                  onPress={() => router.push('/settings/token-usage' as any)}
                />
              </SettingsSection>


              <SettingsSection title={t.settings.workbench.title}>
                <SettingsItem
                  icon={Monitor}
                  title={t.settings.workbench.title}
                  subtitle={t.settings.workbench.subtitle}
                  showChevron
                  isLast
                  onPress={() => router.push('/settings/workbench' as any)}
                />
              </SettingsSection>

              <SettingsSection title="Intelligence">
                <SettingsItem
                  icon={Sparkles}
                  title={t.settings.agentSkills.title}
                  subtitle={t.settings.agentSkills.subtitle}
                  showChevron
                  isLast
                  onPress={() => router.push('/settings/skills' as any)}
                />
              </SettingsSection>


              <BackupSettings />

              {/* 应用信息 */}

              {/* 应用信息 */}
              <SettingsSection title={t.settings.appSection}>
                {/* 暂时隐藏通知设置
              <SettingsItem
                icon={Bell}
                title={t.settings.notifications}
                subtitle={t.settings.notificationsDesc}
                onPress={() => showToast('通知设置', 'info')}
              /> */}

                {/* Theme moved to appearance section */}
                {/* <SettingsItem
                icon={Palette}
                title={t.settings.personalization}
                subtitle={t.settings.personalizationDesc}
                showChevron
                onPress={() => router.push('/settings/theme' as any)}
              /> */}

                <SettingsItem
                  icon={Info}
                  title={t.settings.about}
                  subtitle={`v${Constants.expoConfig?.version ?? '1.1'} (${Constants.expoConfig?.android?.versionCode ?? 2})`}
                  isLast
                  onPress={() => {
                    const newCount = (eggCount || 0) + 1;
                    setEggCount(newCount);

                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);

                    if (newCount >= 5) {
                      setEggCount(0);
                      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                      showToast('Developer Mode: Visual Demo Unlocked', 'success');
                      router.push('/visual-demo');
                    } else if (newCount > 1) {
                      // Subtle hints for 2, 3, 4 taps? maybe not needed to keep it hidden
                    } else {
                      showToast(`Nexara v${Constants.expoConfig?.version ?? '1.1'}`, 'info');
                    }
                  }}
                />
              </SettingsSection>
            </Animated.View>
          </View>
        ) : (
          // 服务商管理
          <View key="providers-tab-wrapper">
            <Animated.View
              entering={FadeIn.duration(300)}
              layout={LinearTransition.duration(300)}
            >
              {/* 添加服务商按钮 */}
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                    setEditingProvider(null);
                    setModalVisible(true);
                  }, 10);
                }}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  backgroundColor: colors[500],
                  paddingVertical: 14,
                  borderRadius: 16,
                  marginBottom: 24,
                }}
              >
                <Text style={{ color: '#ffffff', fontSize: 16, fontWeight: '700' }}>
                  + {t.settings.addProvider}
                </Text>
              </TouchableOpacity>

              {/* 服务商列表 */}
              {providers.length === 0 ? (
                <View style={{ alignItems: 'center', paddingVertical: 60 }}>
                  <Text
                    style={{
                      fontSize: 16,
                      color: isDark ? Colors.dark.textSecondary : '#9ca3af',
                      marginBottom: 8,
                    }}
                  >
                    {t.settings.noProviders}
                  </Text>
                  <Text
                    style={{ fontSize: 14, color: isDark ? Colors.dark.textTertiary : '#d1d5db' }}
                  >
                    {t.settings.noProvidersDesc}
                  </Text>
                </View>
              ) : (
                <View style={{ gap: 16 }}>
                  {providers.map((provider) => (
                    <View
                      key={provider.id}
                      style={{
                        backgroundColor: isDark ? 'rgba(24, 24, 27, 0.8)' : '#f9fafb',
                        borderRadius: 16,
                        padding: 16,
                        borderWidth: 1,
                        borderColor: isDark ? 'rgba(63, 63, 70, 0.5)' : '#e5e7eb',
                      }}
                    >
                      <View
                        style={{
                          flexDirection: 'row',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          marginBottom: 16,
                        }}
                      >
                        <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1 }}>
                          <View
                            style={{
                              width: 40,
                              height: 40,
                              borderRadius: 12,
                              backgroundColor: isDark ? 'rgba(39, 39, 42, 0.9)' : '#f0f3ff',
                              alignItems: 'center',
                              justifyContent: 'center',
                            }}
                          >
                            <Server size={20} color={colors[500]} />
                          </View>
                          <View style={{ marginLeft: 12 }}>
                            <Text
                              style={{
                                fontSize: 16,
                                fontWeight: '700',
                                color: isDark ? Colors.dark.textPrimary : '#111',
                              }}
                            >
                              {provider.name}
                            </Text>
                            <Text
                              style={{
                                fontSize: 12,
                                color: isDark ? Colors.dark.textSecondary : '#666',
                                marginTop: 2,
                              }}
                            >
                              {provider.baseUrl}
                            </Text>
                          </View>
                        </View>
                        <View style={{ flexDirection: 'row', gap: 8 }}>
                          <TouchableOpacity
                            onPress={() => {
                              setTimeout(() => {
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                setEditingProvider(provider);
                                setModalVisible(true);
                              }, 10);
                            }}
                            style={{ padding: 8 }}
                          >
                            <Edit2 size={18} color={colors[500]} />
                          </TouchableOpacity>
                          <TouchableOpacity
                            onPress={() => {
                              setTimeout(() => {
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                deleteProvider(provider.id);
                                showToast(t.settings.providerDeleted, 'success');
                              }, 10);
                            }}
                            style={{ padding: 8 }}
                          >
                            <Trash2 size={18} color="#ef4444" />
                          </TouchableOpacity>
                        </View>
                      </View>
                      <TouchableOpacity
                        onPress={() => {
                          setTimeout(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                            setActiveProviderForModels(provider);
                            setModelModalVisible(true);
                          }, 10);
                        }}
                        style={{
                          flexDirection: 'row',
                          alignItems: 'center',
                          alignSelf: 'flex-start',
                          marginTop: 4,
                          paddingVertical: 8,
                          paddingHorizontal: 16,
                          backgroundColor: isDark ? 'rgba(39, 39, 42, 0.9)' : '#f3f4f6',
                          borderRadius: 16,
                        }}
                      >
                        <Cpu size={14} color={colors[500]} style={{ marginRight: 6 }} />
                        <Text style={{ fontSize: 13, fontWeight: '700', color: colors[500] }}>
                          {t.settings.modelSettings.title}
                        </Text>
                      </TouchableOpacity>
                    </View>
                  ))}
                </View>
              )}
            </Animated.View>
          </View>
        )}

        <View style={{ marginTop: 32, alignItems: 'center', opacity: 0.3 }}>
          <View className="items-center pb-8 pt-4">
            <Text className={`text-xs ${isDark ? 'text-gray-600' : 'text-gray-400'}`}>
              Nexara AI • Project Narcis
            </Text>
          </View>
        </View>
      </ScrollView>

      <ProviderModal
        visible={modalVisible}
        onClose={() => setModalVisible(false)}
        onSave={(providerData) => {
          if (editingProvider) {
            updateProvider(editingProvider.id, providerData);
            showToast('服务商已更新', 'success');
          } else {
            addProvider(providerData);
            showToast('服务商已添加', 'success');
          }
        }}
        editingProvider={editingProvider}
      />

      <ModelSettingsModal
        visible={modelModalVisible}
        provider={activeProviderForModels}
        onClose={() => setModelModalVisible(false)}
        onUpdateModels={(newModels) => {
          if (activeProviderForModels) {
            updateProvider(activeProviderForModels.id, { models: newModels });
          }
        }}
      />

      <ModelPicker
        visible={pickerVisible}
        title={pickerConfig.title}
        filterType={pickerConfig.filterType}
        onClose={() => setPickerVisible(false)}
        selectedUuid={
          pickerConfig.key === 'defaultSummaryModel'
            ? defaultSummaryModel
            : pickerConfig.key === 'defaultSpeechModel'
              ? defaultSpeechModel
              : pickerConfig.key === 'defaultRerankModel'
                ? defaultRerankModel
                : pickerConfig.key === 'defaultImageModel'
                  ? defaultImageModel
                  : defaultEmbeddingModel
        }
        onSelect={(uuid) => {
          updateDefaultModel(pickerConfig.key, uuid);
          showToast('默认模型已更新', 'success');
        }}
      />
    </PageLayout >
  );
}

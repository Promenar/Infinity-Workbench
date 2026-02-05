import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, ScrollView, StyleSheet, Platform } from 'react-native';
import Animated, {
  FadeIn,
  FadeOut,
  LinearTransition,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  Easing,
  interpolate,
  Extrapolate
} from 'react-native-reanimated';
import { PageLayout, Switch } from '../../src/components/ui';
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

import { LargeTitleHeader } from '../../src/components/ui/LargeTitleHeader';
import { ProviderModal } from '../../src/features/settings/ProviderModal';
import { ProviderList } from '../../src/features/settings/components/ProviderList';
import { Card } from '../../src/components/ui/Card';
import { ModelPicker } from '../../src/features/settings/ModelPicker';
import { GlobalRagConfigPanel } from '../../src/features/settings/components/GlobalRagConfigPanel';
import * as Haptics from '../../src/lib/haptics';
import Constants from 'expo-constants';

export default function SettingsScreen() {
  const router = useRouter();
  const { theme, setTheme, isDark, colors } = useTheme();
  const { t } = useI18n();
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
    setHapticsEnabled,
    loggingEnabled,
    setLoggingEnabled,
  } = useSettingsStore();

  const { providers, deleteProvider, addProvider, updateProvider } = useApiStore();

  const [activeTab, setActiveTab] = useState<'app' | 'providers'>('app');
  const [containerWidth, setContainerWidth] = useState(0);
  const [eggCount, setEggCount] = useState(0);
  const [modalVisible, setModalVisible] = useState(false);

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

  // 1. Transition Shared Value: 0 = app, 1 = providers
  const tabProgress = useSharedValue(0);

  useEffect(() => {
    tabProgress.value = withTiming(activeTab === 'app' ? 0 : 1, {
      duration: 350,
      easing: Easing.bezier(0.33, 1, 0.68, 1),
    });
  }, [activeTab]);

  // 2. Animated style for the tab selector background indicator
  const animatedSelectorStyle = useAnimatedStyle(() => {
    const slideMultiplier = containerWidth ? (containerWidth / 2 - 4) : 163;
    return {
      transform: [{ translateX: tabProgress.value * slideMultiplier }],
      backgroundColor: isDark ? 'rgba(63, 63, 70, 0.9)' : '#ffffff', // Use zinc-700 in dark mode
    };
  });

  // 3. Animated styles for content cross-fading and subtle sliding
  const appTabStyle = useAnimatedStyle(() => {
    const opacity = interpolate(tabProgress.value, [0, 0.3], [1, 0], Extrapolate.CLAMP);
    const translateX = interpolate(tabProgress.value, [0, 1], [0, -20]);
    return {
      opacity,
      transform: [{ translateX }],
      display: tabProgress.value > 0.95 ? 'none' : 'flex',
    };
  });

  const providerTabStyle = useAnimatedStyle(() => {
    const opacity = interpolate(tabProgress.value, [0.7, 1], [0, 1], Extrapolate.CLAMP);
    const translateX = interpolate(tabProgress.value, [0, 1], [20, 0]);
    return {
      opacity,
      transform: [{ translateX }],
      display: tabProgress.value < 0.05 ? 'none' : 'flex',
    };
  });

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
        {/* 标签切换器 - Optimized with Animated Indicator */}
        <View
          onLayout={(e) => setContainerWidth(e.nativeEvent.layout.width)}
          style={{
            flexDirection: 'row',
            backgroundColor: isDark ? 'rgba(24, 24, 27, 0.8)' : '#f3f4f6',
            padding: 4,
            borderRadius: 24,
            marginBottom: 32,
            position: 'relative',
          }}
        >
          {/* 动态背景指示器 */}
          <Animated.View
            style={[
              {
                position: 'absolute',
                top: 4,
                bottom: 4,
                left: 4,
                width: containerWidth ? (containerWidth / 2 - 4) : '50%',
                borderRadius: 20,
                backgroundColor: isDark ? 'rgba(63, 63, 70, 0.9)' : '#ffffff',
                // 🎨 物理立体感方案：使用底边加粗模拟投影 (Rule 3)
                // 通过 borderBottom 实现阴影效果，彻底规避 elevation 的残影闪烁
                borderWidth: 1,
                borderColor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.04)',
                borderBottomWidth: isDark ? 1 : 1.5,
                borderBottomColor: isDark ? 'rgba(0,0,0,0.4)' : 'rgba(0,0,0,0.12)',

                // iOS 端原生阴影极其稳定，可以保留
                ...(Platform.OS === 'ios' && {
                  shadowColor: '#000',
                  shadowOffset: { width: 0, height: 1 },
                  shadowOpacity: isDark ? 0 : 0.08,
                  shadowRadius: 3,
                }),
              },
              animatedSelectorStyle
            ]}
          />

          <TouchableOpacity
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
              setActiveTab('app');
            }}
            style={{
              flex: 1,
              paddingVertical: 12,
              borderRadius: 20,
              alignItems: 'center',
              zIndex: 1,
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
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
              setActiveTab('providers');
            }}
            style={{
              flex: 1,
              paddingVertical: 12,
              borderRadius: 20,
              alignItems: 'center',
              zIndex: 1,
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

        {/* 内容区域：并行渲染以支持 Cross-fade 过渡 */}
        <View style={{ position: 'relative' }}>
          {/* 应用设置标签页 */}
          <Animated.View
            key="app-tab-content"
            style={[appTabStyle]}
            pointerEvents={activeTab === 'app' ? 'auto' : 'none'}
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
                      borderRadius: 24,
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
                        borderRadius: 20,
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
                        borderRadius: 20,
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
                      borderRadius: 24,
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
                            borderRadius: 20,
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


            <SettingsSection title={`${t.settings.workbench.title} 🧪`}>
              <SettingsItem
                icon={Monitor}
                title={`${t.settings.workbench.title} 🧪`}
                subtitle={t.settings.workbench.subtitle}
                showChevron
                isLast
                onPress={() => router.push('/settings/workbench' as any)}
              />
            </SettingsSection>

            <SettingsSection title={`${t.settings.intelligenceSection} 🧪`}>
              <SettingsItem
                icon={Sparkles}
                title={t.settings.agentSkills.title}
                subtitle={t.settings.agentSkills.subtitle}
                showChevron
                isLast
                onPress={() => router.push('/settings/skills' as any)}
              />
              <SettingsItem
                icon={Cpu}
                title={`${t.settings.localModels.title} 🧪`}
                subtitle={t.settings.localModels.subtitle}
                showChevron
                isLast
                onPress={() => router.push('/settings/local-models' as any)}
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
                icon={FileText}
                title={t.settings.logging || '运行日志记录'}
                subtitle={t.settings.loggingDesc || '记录应用运行与 API 调试信息'}
                rightElement={
                  <Switch
                    value={loggingEnabled}
                    onValueChange={(v) => {
                      setLoggingEnabled(v);
                    }}
                  />
                }
              />

              <SettingsItem
                icon={FileText}
                title={t.settings.exportLogs || '导出运行日志'}
                subtitle={t.settings.exportLogsDesc || '通过系统分享导出日志'}
                onPress={async () => {
                  try {
                    const { Logger } = require('../../src/lib/logging/Logger');
                    await Logger.getInstance().exportLogs();
                    showToast(t.settings.exportSuccess, 'success');
                  } catch (e) {
                    showToast(t.settings.exportFail, 'error');
                  }
                }}
              />


              <SettingsItem
                icon={Info}
                title={t.settings.about}

                subtitle={`v${Constants.expoConfig?.version ?? '1.1'} (${Constants.expoConfig?.android?.versionCode ?? 2})`}
                isLast
                onPress={() => {

                  const newCount = (eggCount || 0) + 1;
                  setEggCount(newCount);

                  setTimeout(() => {
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
                  }, 10);
                }}
              />
            </SettingsSection>
          </Animated.View>

          {/* 服务商管理标签页 */}
          <Animated.View
            key="providers-tab-content"
            style={[providerTabStyle]}
            pointerEvents={activeTab === 'providers' ? 'auto' : 'none'}
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
                borderRadius: 24,
                marginBottom: 24,
              }}
            >
              <Text style={{ color: '#ffffff', fontSize: 16, fontWeight: '700' }}>
                + {t.settings.addProvider}
              </Text>
            </TouchableOpacity>

            {/* 服务商列表 */}
            <ProviderList
              providers={providers}
              onEdit={(provider) => {
                setEditingProvider(provider);
                setModalVisible(true);
              }}
              onDelete={(id) => {
                deleteProvider(id);
                showToast(t.settings.providerDeleted, 'success');
              }}
              onManageModels={(provider) => {
                router.push({
                  pathname: '/settings/provider-models' as any,
                  params: { providerId: provider.id }
                });
              }}
            />
          </Animated.View>
        </View>

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
    </PageLayout>
  );
}

const styles = StyleSheet.create({
  tabContainer: {
    flexDirection: 'row',
    padding: 4,
    borderRadius: 24,
    marginBottom: 32,
    position: 'relative',
    height: 56,
  },
  tabIndicator: {
    position: 'absolute',
    top: 4,
    left: 4,
    borderRadius: 20,
  },
  tabButton: {
    flex: 1,
    paddingVertical: 12,
    borderRadius: 20,
    alignItems: 'center',
    zIndex: 1,
  },
  tabText: {
    fontWeight: 'bold',
  },
  contentWrapper: {
    position: 'relative',
  },
  tabContent: {
    // Shared styling if needed
  }
});

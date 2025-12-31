import React, { useState } from 'react';
import { View, Text, TouchableOpacity, ScrollView } from 'react-native';
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
import { Globe, Moon, Bell, Info, Plus, Server, Trash2, Edit2, Cpu, FileText, Mic, Layers, ChevronRight, Sun, Monitor, Zap, Database } from 'lucide-react-native';
import { ProviderModal } from '../../src/features/settings/ProviderModal';
import { ModelSettingsModal } from '../../src/features/settings/ModelSettingsModal';
import { ModelPicker } from '../../src/features/settings/ModelPicker';
import { GlobalRagConfigPanel } from '../../src/features/settings/components/GlobalRagConfigPanel';
import * as Haptics from '../../src/lib/haptics'; // Import wrapper

export default function SettingsScreen() {
    const router = useRouter();
    const [activeTab, setActiveTab] = useState<'app' | 'providers'>('app');
    const [modalVisible, setModalVisible] = useState(false);
    const [modelModalVisible, setModelModalVisible] = useState(false);
    const [pickerVisible, setPickerVisible] = useState(false);
    const [pickerConfig, setPickerConfig] = useState<{
        title: string;
        key: 'defaultSummaryModel' | 'defaultSpeechModel' | 'defaultEmbeddingModel';
        filterType?: 'chat' | 'reasoning' | 'image' | 'embedding';
    }>({ title: '', key: 'defaultSummaryModel' });

    const [editingProvider, setEditingProvider] = useState<ProviderConfig | null>(null);
    const [activeProviderForModels, setActiveProviderForModels] = useState<ProviderConfig | null>(null);
    const { t } = useI18n();
    const { theme, setTheme, isDark } = useTheme();
    const { showToast } = useToast();
    const {
        language, setLanguage,
        defaultSummaryModel, defaultSpeechModel, defaultEmbeddingModel,
        updateDefaultModel,
        hapticsEnabled, setHapticsEnabled // New settings
    } = useSettingsStore();
    const { providers, deleteProvider, addProvider, updateProvider } = useApiStore();

    // 辅助函数：通过 UUID 获取模型名称
    const getModelName = (uuid?: string) => {
        if (!uuid) return t.settings.modelPresets.none;
        for (const p of providers) {
            const m = p.models.find(model => model.uuid === uuid);
            if (m) return m.name;
        }
        return t.settings.modelPresets.none;
    };

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />

            {/* Fixed Title Header */}
            <View style={{ paddingTop: 64, paddingBottom: 8, paddingHorizontal: 24 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', height: 56, marginBottom: 24 }}>
                    <View>
                        <Text style={{ fontSize: 32, fontWeight: '900', color: isDark ? '#fff' : '#111', letterSpacing: -1.5, lineHeight: 38 }}>
                            {t.settings.title}
                        </Text>
                        <Text style={{ fontSize: 11, fontWeight: 'bold', color: '#9ca3af', textTransform: 'uppercase', letterSpacing: 2, marginTop: 4, lineHeight: 11 }}>
                            {t.settings.subtitle}
                        </Text>
                    </View>
                </View>
            </View>

            <ScrollView style={{ flex: 1, paddingHorizontal: 24 }} contentContainerStyle={{ paddingBottom: 100 }} showsVerticalScrollIndicator={false}>
                {/* 标签切换器 */}
                <View style={{ flexDirection: 'row', backgroundColor: isDark ? '#27272a' : '#f3f4f6', padding: 4, borderRadius: 16, marginBottom: 32 }}>
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
                            backgroundColor: activeTab === 'app' ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent'
                        }}
                    >
                        <Text style={{ fontWeight: 'bold', color: activeTab === 'app' ? (isDark ? '#fff' : '#111') : '#9ca3af' }}>
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
                            backgroundColor: activeTab === 'providers' ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent'
                        }}
                    >
                        <Text style={{ fontWeight: 'bold', color: activeTab === 'providers' ? (isDark ? '#fff' : '#111') : '#9ca3af' }}>
                            {t.settings.providerSettings}
                        </Text>
                    </TouchableOpacity>
                </View>

                {activeTab === 'app' ? (
                    <>
                        {/* 基础设置 */}
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 10, fontWeight: '700', color: '#9ca3af', marginBottom: 12, paddingHorizontal: 16, textTransform: 'uppercase', letterSpacing: 1.5 }}>
                                {t.settings.basicSettings}
                            </Text>
                            <View style={{ backgroundColor: isDark ? '#18181b' : '#f9fafb', borderRadius: 24, overflow: 'hidden', borderWidth: 1, borderColor: isDark ? '#27272a' : '#e5e7eb' }}>
                                {/* 语言设置 */}
                                <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: isDark ? '#27272a' : '#e5e7eb' }}>
                                    <Globe size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.language}
                                        </Text>
                                        <Text style={{ fontSize: 14, color: '#6b7280', marginTop: 4 }}>
                                            {language === 'zh' ? '简体中文' : 'English'}
                                        </Text>
                                    </View>
                                    <View style={{ flexDirection: 'row', backgroundColor: isDark ? '#27272a' : '#e5e7eb', borderRadius: 20, padding: 4 }}>
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
                                                backgroundColor: language === 'zh' ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent'
                                            }}
                                        >
                                            <Text style={{ fontSize: 12, fontWeight: 'bold', color: language === 'zh' ? '#6366f1' : '#9ca3af' }}>中</Text>
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
                                                backgroundColor: language === 'en' ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent'
                                            }}
                                        >
                                            <Text style={{ fontSize: 12, fontWeight: 'bold', color: language === 'en' ? '#6366f1' : '#9ca3af' }}>EN</Text>
                                        </TouchableOpacity>
                                    </View>
                                </View>

                                {/* 主题设置 */}
                                <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16 }}>
                                    <Moon size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.appearance}
                                        </Text>
                                        <Text style={{ fontSize: 13, color: '#6b7280', marginTop: 4 }}>
                                            {theme === 'system' ? t.settings.themeSystem : (isDark ? t.settings.themeDark : t.settings.themeLight)}
                                        </Text>
                                    </View>
                                    <View style={{ flexDirection: 'row', backgroundColor: isDark ? '#27272a' : '#e5e7eb', borderRadius: 20, padding: 2 }}>
                                        {[
                                            { mode: 'light', icon: Sun },
                                            { mode: 'system', icon: Monitor },
                                            { mode: 'dark', icon: Moon }
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
                                                        backgroundColor: theme === m ? (isDark ? '#3f3f46' : '#ffffff') : 'transparent',
                                                        alignItems: 'center',
                                                        justifyContent: 'center'
                                                    }}
                                                >
                                                    <Icon size={14} color={theme === m ? '#6366f1' : '#9ca3af'} />
                                                </TouchableOpacity>
                                            );
                                        })}
                                    </View>
                                </View>

                                {/* Haptics Setting */}
                                <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderTopWidth: 1, borderTopColor: isDark ? '#27272a' : '#e5e7eb' }}>
                                    <Zap size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.haptics || 'Haptic Feedback'}
                                        </Text>
                                        <Text style={{ fontSize: 13, color: '#6b7280', marginTop: 4 }}>
                                            {t.settings.hapticsDesc || 'Enable vibration feedback'}
                                        </Text>
                                    </View>
                                    <Switch
                                        value={hapticsEnabled}
                                        onValueChange={(v) => {
                                            setHapticsEnabled(v);
                                        }}
                                    />
                                </View>

                                {/* 搜索配置 */}
                                <TouchableOpacity
                                    onPress={() => {
                                        setTimeout(() => {
                                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                            router.push('/settings/search');
                                        }, 10);
                                    }}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderTopWidth: 1, borderTopColor: isDark ? '#27272a' : '#e5e7eb' }}
                                >
                                    <Globe size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.webSearchConfig}
                                        </Text>
                                        <Text style={{ fontSize: 13, color: '#9ca3af', marginTop: 2 }}>
                                            {t.settings.webSearchConfigDesc}
                                        </Text>
                                    </View>
                                    <ChevronRight size={20} color="#9ca3af" />
                                </TouchableOpacity>
                            </View>
                        </View>

                        {/* RAG 配置 */}
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 10, fontWeight: '700', color: '#9ca3af', marginBottom: 12, paddingHorizontal: 16, textTransform: 'uppercase', letterSpacing: 1.5 }}>
                                {t.settings.ragSection}
                            </Text>
                            <TouchableOpacity
                                onPress={() => {
                                    setTimeout(() => {
                                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                        router.push('/settings/rag-config' as any);
                                    }, 10);
                                }}
                                style={{ backgroundColor: isDark ? '#18181b' : '#f9fafb', borderRadius: 24, borderWidth: 1, borderColor: isDark ? '#27272a' : '#e5e7eb', flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16 }}
                            >
                                <Database size={20} color="#6b7280" />
                                <View style={{ flex: 1, marginLeft: 12 }}>
                                    <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                        {t.settings.ragSettings}
                                    </Text>
                                    <Text style={{ fontSize: 13, color: '#9ca3af', marginTop: 2 }}>
                                        {t.settings.ragSettingsDesc}
                                    </Text>
                                </View>
                                <ChevronRight size={20} color="#9ca3af" />
                            </TouchableOpacity>
                        </View>


                        <BackupSettings />

                        {/* 模型预设 */}
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 10, fontWeight: '700', color: '#9ca3af', marginBottom: 12, paddingHorizontal: 16, textTransform: 'uppercase', letterSpacing: 1.5 }}>
                                {t.settings.modelPresets.title}
                            </Text>
                            <View style={{ backgroundColor: isDark ? '#18181b' : '#f9fafb', borderRadius: 24, overflow: 'hidden', borderWidth: 1, borderColor: isDark ? '#27272a' : '#e5e7eb' }}>
                                {/* 摘要模型 */}
                                <TouchableOpacity
                                    onPress={() => {
                                        setTimeout(() => {
                                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                            setPickerConfig({
                                                title: t.settings.modelPresets.summary,
                                                key: 'defaultSummaryModel',
                                                filterType: 'chat'
                                            });
                                            setPickerVisible(true);
                                        }, 10);
                                    }}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: isDark ? '#27272a' : '#e5e7eb' }}
                                >
                                    <FileText size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.modelPresets.summary}
                                        </Text>
                                        <Text style={{ fontSize: 13, color: '#9ca3af', marginTop: 2 }}>
                                            {getModelName(defaultSummaryModel)}
                                        </Text>
                                    </View>
                                    <ChevronRight size={20} color="#9ca3af" />
                                </TouchableOpacity>

                                {/* 语音模型 */}
                                <TouchableOpacity
                                    onPress={() => {
                                        setTimeout(() => {
                                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                            setPickerConfig({
                                                title: t.settings.modelPresets.speech,
                                                key: 'defaultSpeechModel',
                                                filterType: 'chat'
                                            });
                                            setPickerVisible(true);
                                        }, 10);
                                    }}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: isDark ? '#27272a' : '#e5e7eb' }}
                                >
                                    <Mic size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.modelPresets.speech}
                                        </Text>
                                        <Text style={{ fontSize: 13, color: '#9ca3af', marginTop: 2 }}>
                                            {getModelName(defaultSpeechModel)}
                                        </Text>
                                    </View>
                                    <ChevronRight size={20} color="#9ca3af" />
                                </TouchableOpacity>

                                {/* 向量模型 */}
                                <TouchableOpacity
                                    onPress={() => {
                                        setTimeout(() => {
                                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                            setPickerConfig({
                                                title: t.settings.modelPresets.embedding,
                                                key: 'defaultEmbeddingModel',
                                                filterType: 'embedding'
                                            });
                                            setPickerVisible(true);
                                        }, 10);
                                    }}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16 }}
                                >
                                    <Layers size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.modelPresets.embedding}
                                        </Text>
                                        <Text style={{ fontSize: 13, color: '#9ca3af', marginTop: 2 }}>
                                            {getModelName(defaultEmbeddingModel)}
                                        </Text>
                                    </View>
                                    <ChevronRight size={20} color="#9ca3af" />
                                </TouchableOpacity>
                            </View>
                        </View>

                        {/* 应用信息 */}
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 14, fontWeight: 'bold', color: '#9ca3af', marginBottom: 12, paddingHorizontal: 16 }}>
                                {t.settings.appSection}
                            </Text>
                            <View style={{ backgroundColor: isDark ? '#18181b' : '#f9fafb', borderRadius: 16, overflow: 'hidden' }}>
                                <TouchableOpacity
                                    onPress={() => showToast('通知设置', 'info')}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: isDark ? '#27272a' : '#e5e7eb' }}
                                >
                                    <Bell size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.notifications}
                                        </Text>
                                        <Text style={{ fontSize: 14, color: '#6b7280', marginTop: 4 }}>
                                            {t.settings.notificationsDesc}
                                        </Text>
                                    </View>
                                </TouchableOpacity>

                                <TouchableOpacity
                                    onPress={() => showToast('Nexara v1.0.0', 'info')}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16 }}
                                >
                                    <Info size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                            {t.settings.about}
                                        </Text>
                                        <Text style={{ fontSize: 14, color: '#6b7280', marginTop: 4 }}>
                                            v1.0.0
                                        </Text>
                                    </View>
                                </TouchableOpacity>
                            </View>
                        </View>
                    </>
                ) : (
                    // 服务商管理
                    <View>
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
                                backgroundColor: '#6366f1',
                                paddingVertical: 14,
                                borderRadius: 12,
                                marginBottom: 24
                            }}
                        >
                            <Text style={{ color: '#ffffff', fontSize: 16, fontWeight: '600' }}>
                                + {t.settings.addProvider}
                            </Text>
                        </TouchableOpacity>

                        {/* 服务商列表 */}
                        {providers.length === 0 ? (
                            <View style={{ alignItems: 'center', paddingVertical: 60 }}>
                                <Text style={{ fontSize: 16, color: '#9ca3af', marginBottom: 8 }}>
                                    {t.settings.noProviders}
                                </Text>
                                <Text style={{ fontSize: 14, color: '#d1d5db' }}>
                                    {t.settings.noProvidersDesc}
                                </Text>
                            </View>
                        ) : (
                            <View style={{ gap: 12 }}>
                                {providers.map((provider) => (
                                    <View
                                        key={provider.id}
                                        style={{
                                            backgroundColor: isDark ? '#18181b' : '#f9fafb',
                                            borderRadius: 12,
                                            padding: 16,
                                            borderWidth: 1,
                                            borderColor: isDark ? '#27272a' : '#e5e7eb'
                                        }}
                                    >
                                        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                                            <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1 }}>
                                                <Server size={20} color="#6366f1" />
                                                <Text style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111', marginLeft: 8 }}>
                                                    {provider.name}
                                                </Text>
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
                                                    <Edit2 size={18} color="#6366f1" />
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
                                                marginTop: 12,
                                                paddingVertical: 8,
                                                paddingHorizontal: 12,
                                                backgroundColor: isDark ? '#27272a' : '#f3f4f6',
                                                borderRadius: 8
                                            }}
                                        >
                                            <Cpu size={14} color="#6366f1" style={{ marginRight: 6 }} />
                                            <Text style={{ fontSize: 13, fontWeight: 'bold', color: '#6366f1' }}>
                                                {t.settings.modelSettings.title}
                                            </Text>
                                        </TouchableOpacity>
                                    </View>
                                ))}
                            </View>
                        )}
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
                    pickerConfig.key === 'defaultSummaryModel' ? defaultSummaryModel :
                        pickerConfig.key === 'defaultSpeechModel' ? defaultSpeechModel :
                            defaultEmbeddingModel
                }
                onSelect={(uuid) => {
                    updateDefaultModel(pickerConfig.key, uuid);
                    showToast('默认模型已更新', 'success');
                }}
            />
        </PageLayout >
    );
}

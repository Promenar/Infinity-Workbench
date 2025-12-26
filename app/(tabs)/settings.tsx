import React, { useState } from 'react';
import { View, Text, TouchableOpacity, ScrollView, Switch } from 'react-native';
import { PageLayout } from '../../src/components/ui/PageLayout';
import { Stack } from 'expo-router';
import * as Haptics from 'expo-haptics';
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useToast } from '../../src/components/ui/Toast';
import { useSettingsStore } from '../../src/store/settings-store';
import { useApiStore, ProviderConfig, ModelConfig } from '../../src/store/api-store';
import { clsx } from 'clsx';
import { Globe, Moon, Bell, Info, Plus, Server, Trash2, Edit2, Cpu, FileText, Mic, Layers, ChevronRight } from 'lucide-react-native';
import { ProviderModal } from '../../src/features/settings/ProviderModal';
import { ModelSettingsModal } from '../../src/features/settings/ModelSettingsModal';
import { ModelPicker } from '../../src/features/settings/ModelPicker';

export default function SettingsScreen() {
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
    const { theme, setTheme } = useTheme();
    const { showToast } = useToast();
    const {
        language, setLanguage,
        defaultSummaryModel, defaultSpeechModel, defaultEmbeddingModel,
        updateDefaultModel
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
                        <Text style={{ fontSize: 32, fontWeight: '900', color: theme === 'dark' ? '#fff' : '#111', letterSpacing: -1.5, lineHeight: 32 }}>
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
                <View style={{ flexDirection: 'row', backgroundColor: theme === 'dark' ? '#27272a' : '#f3f4f6', padding: 4, borderRadius: 16, marginBottom: 32 }}>
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
                            backgroundColor: activeTab === 'app' ? (theme === 'dark' ? '#3f3f46' : '#ffffff') : 'transparent'
                        }}
                    >
                        <Text style={{ fontWeight: 'bold', color: activeTab === 'app' ? (theme === 'dark' ? '#fff' : '#111') : '#9ca3af' }}>
                            应用设置
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
                            backgroundColor: activeTab === 'providers' ? (theme === 'dark' ? '#3f3f46' : '#ffffff') : 'transparent'
                        }}
                    >
                        <Text style={{ fontWeight: 'bold', color: activeTab === 'providers' ? (theme === 'dark' ? '#fff' : '#111') : '#9ca3af' }}>
                            服务商管理
                        </Text>
                    </TouchableOpacity>
                </View>

                {activeTab === 'app' ? (
                    <>
                        {/* 基础设置 */}
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 14, fontWeight: 'bold', color: '#9ca3af', marginBottom: 12, paddingHorizontal: 16 }}>
                                基础设置
                            </Text>
                            <View style={{ backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb', borderRadius: 16, overflow: 'hidden' }}>
                                {/* 语言设置 */}
                                <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: theme === 'dark' ? '#27272a' : '#e5e7eb' }}>
                                    <Globe size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
                                            {t.settings.language}
                                        </Text>
                                        <Text style={{ fontSize: 14, color: '#6b7280', marginTop: 4 }}>
                                            {language === 'zh' ? '简体中文' : 'English'}
                                        </Text>
                                    </View>
                                    <View style={{ flexDirection: 'row', backgroundColor: theme === 'dark' ? '#27272a' : '#e5e7eb', borderRadius: 20, padding: 4 }}>
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
                                                backgroundColor: language === 'zh' ? (theme === 'dark' ? '#3f3f46' : '#ffffff') : 'transparent'
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
                                                backgroundColor: language === 'en' ? (theme === 'dark' ? '#3f3f46' : '#ffffff') : 'transparent'
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
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
                                            {t.settings.appearance}
                                        </Text>
                                        <Text style={{ fontSize: 14, color: '#6b7280', marginTop: 4 }}>
                                            {t.settings.themeDark}
                                        </Text>
                                    </View>
                                    <Switch
                                        value={theme === 'dark'}
                                        onValueChange={(v) => {
                                            setTimeout(() => {
                                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                                                setTheme(v ? 'dark' : 'light');
                                            }, 10);
                                        }}
                                        trackColor={{ false: '#e2e8f0', true: '#818cf8' }}
                                        thumbColor={'#ffffff'}
                                    />
                                </View>
                            </View>
                        </View>

                        {/* 模型预设 */}
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 14, fontWeight: 'bold', color: '#9ca3af', marginBottom: 12, paddingHorizontal: 16 }}>
                                {t.settings.modelPresets.title}
                            </Text>
                            <View style={{ backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb', borderRadius: 16, overflow: 'hidden' }}>
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
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: theme === 'dark' ? '#27272a' : '#e5e7eb' }}
                                >
                                    <FileText size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
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
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: theme === 'dark' ? '#27272a' : '#e5e7eb' }}
                                >
                                    <Mic size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
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
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
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
                                应用
                            </Text>
                            <View style={{ backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb', borderRadius: 16, overflow: 'hidden' }}>
                                <TouchableOpacity
                                    onPress={() => showToast('通知设置', 'info')}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: theme === 'dark' ? '#27272a' : '#e5e7eb' }}
                                >
                                    <Bell size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
                                            {t.settings.notifications}
                                        </Text>
                                        <Text style={{ fontSize: 14, color: '#6b7280', marginTop: 4 }}>
                                            {t.settings.notificationsDesc}
                                        </Text>
                                    </View>
                                </TouchableOpacity>

                                <TouchableOpacity
                                    onPress={() => showToast('NeuralFlow v1.0.0', 'info')}
                                    style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16 }}
                                >
                                    <Info size={20} color="#6b7280" />
                                    <View style={{ flex: 1, marginLeft: 12 }}>
                                        <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111' }}>
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
                                + 添加服务商
                            </Text>
                        </TouchableOpacity>

                        {/* 服务商列表 */}
                        {providers.length === 0 ? (
                            <View style={{ alignItems: 'center', paddingVertical: 60 }}>
                                <Text style={{ fontSize: 16, color: '#9ca3af', marginBottom: 8 }}>
                                    暂无服务商配置
                                </Text>
                                <Text style={{ fontSize: 14, color: '#d1d5db' }}>
                                    点击上方按钮添加您的第一个服务商
                                </Text>
                            </View>
                        ) : (
                            <View style={{ gap: 12 }}>
                                {providers.map((provider) => (
                                    <View
                                        key={provider.id}
                                        style={{
                                            backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                            borderRadius: 12,
                                            padding: 16,
                                            borderWidth: 1,
                                            borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb'
                                        }}
                                    >
                                        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                                            <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1 }}>
                                                <Server size={20} color="#6366f1" />
                                                <Text style={{ fontSize: 16, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111', marginLeft: 8 }}>
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
                                                            showToast('已删除服务商', 'success');
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
                                                backgroundColor: theme === 'dark' ? '#27272a' : '#f3f4f6',
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
                    <Text style={{ color: '#9ca3af', fontSize: 10, fontWeight: 'bold', letterSpacing: 4, textTransform: 'uppercase' }}>
                        NeuralFlow AI • Project Narcis
                    </Text>
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

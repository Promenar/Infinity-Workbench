import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { View, Text, TouchableOpacity, Modal, TextInput, Switch, ActivityIndicator, Platform, KeyboardAvoidingView, ScrollView } from 'react-native';
import { X, Plus, Trash2, Cpu, Globe, Eye, Zap, RefreshCw, Search, CheckCircle2, AlertCircle } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { FlashList } from '@shopify/flash-list';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { ProviderConfig, ModelConfig } from '../../store/api-store';
import { useToast } from '../../components/ui/Toast';
import { createLlmClient } from '../../lib/llm/factory';

// 使用 any 绕过某些环境下 FlashList 的类型检测问题
const TypedFlashList = FlashList as any;

interface ModelSettingsModalProps {
    visible: boolean;
    provider: ProviderConfig | null;
    onClose: () => void;
    onUpdateModels: (models: ModelConfig[]) => void;
}

// 提取并 Memoize 模型项以获得极致性能
const ModelItem = React.memo(({
    model,
    onToggle,
    onDelete,
    onUpdate,
    onTest,
    testStatus,
    theme,
    t
}: {
    model: ModelConfig;
    onToggle: (uuid: string, enabled: boolean) => void;
    onDelete: (uuid: string) => void;
    onUpdate: (uuid: string, updates: Partial<ModelConfig>) => void;
    onTest: (model: ModelConfig) => void;
    testStatus?: { loading: boolean; success?: boolean; latency?: number; error?: string };
    theme: string;
    t: any;
}) => (
    <View
        style={{
            backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
            borderRadius: 16,
            padding: 16,
            marginBottom: 12,
            marginHorizontal: 16,
            borderWidth: 1,
            borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb'
        }}
    >
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <View style={{ flex: 1, marginRight: 12 }}>
                {/* 1. 显示名称 (Editable) */}
                <View style={{ marginBottom: 8 }}>
                    <Text style={{ fontSize: 10, color: '#9ca3af', marginBottom: 2 }}>{t.settings.modelSettings.modelName}</Text>
                    <TextInput
                        value={model.name}
                        onChangeText={(v) => onUpdate(model.uuid, { name: v })}
                        style={{ fontSize: 16, fontWeight: 'bold', color: theme === 'dark' ? '#fff' : '#111' }}
                    />
                </View>
                {/* 2. API 参数 (Editable only if not auto-fetched) */}
                <View>
                    <Text style={{ fontSize: 10, color: '#9ca3af', marginBottom: 2 }}>{t.settings.modelSettings.modelId}</Text>
                    <TextInput
                        value={model.id}
                        editable={!model.isAutoFetched}
                        onChangeText={(v) => onUpdate(model.uuid, { id: v })}
                        style={{
                            fontSize: 12,
                            color: model.isAutoFetched ? '#6b7280' : (theme === 'dark' ? '#fff' : '#111'),
                            fontFamily: 'monospace'
                        }}
                    />
                </View>
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
                {/* 测试按钮 */}
                <TouchableOpacity
                    onPress={() => onTest(model)}
                    disabled={testStatus?.loading}
                    style={{ padding: 4 }}
                >
                    {testStatus?.loading ? (
                        <ActivityIndicator size="small" color="#6366f1" />
                    ) : (
                        <RefreshCw size={18} color={testStatus?.success === true ? '#22c55e' : (testStatus?.success === false ? '#ef4444' : '#6366f1')} />
                    )}
                </TouchableOpacity>

                <Switch
                    value={model.enabled}
                    onValueChange={(v) => onToggle(model.uuid, v)}
                    trackColor={{ false: '#e2e8f0', true: '#818cf8' }}
                />
                <TouchableOpacity onPress={() => onDelete(model.uuid)}>
                    <Trash2 size={18} color="#ef4444" />
                </TouchableOpacity>
            </View>
        </View>

        {/* 测试结果展示 */}
        {testStatus && !testStatus.loading && (testStatus.success !== undefined) && (
            <View style={{
                flexDirection: 'row',
                alignItems: 'center',
                backgroundColor: testStatus.success ? 'rgba(34, 197, 94, 0.1)' : 'rgba(239, 68, 68, 0.1)',
                paddingHorizontal: 10,
                paddingVertical: 6,
                borderRadius: 8,
                marginBottom: 12
            }}>
                {testStatus.success ? (
                    <CheckCircle2 size={12} color="#22c55e" />
                ) : (
                    <AlertCircle size={12} color="#ef4444" />
                )}
                <Text style={{
                    fontSize: 11,
                    marginLeft: 6,
                    color: testStatus.success ? '#22c55e' : '#ef4444',
                    fontWeight: '600',
                    flex: 1
                }}>
                    {testStatus.success
                        ? t.settings.modelSettings.testSuccess.replace('{latency}', testStatus.latency?.toString())
                        : t.settings.modelSettings.testError.replace('{error}', testStatus.error || 'Unknown')}
                </Text>
            </View>
        )}

        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
            {(['chat', 'reasoning', 'image', 'embedding'] as const).map((type) => (
                <TypeButton
                    key={type}
                    label={t.settings.modelSettings[`type${type.charAt(0).toUpperCase() + type.slice(1)}` as keyof typeof t.settings.modelSettings] as string}
                    active={(model.type || 'chat') === type}
                    onPress={() => onUpdate(model.uuid, { type })}
                />
            ))}
        </View>

        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
            <CapabilityTag
                icon={<Eye size={12} />}
                label={t.settings.modelSettings.vision}
                active={model.capabilities.vision}
                onToggle={() => onUpdate(model.uuid, { capabilities: { ...model.capabilities, vision: !model.capabilities.vision } })}
            />
            <CapabilityTag
                icon={<Globe size={12} />}
                label={t.settings.modelSettings.internet}
                active={model.capabilities.internet}
                onToggle={() => onUpdate(model.uuid, { capabilities: { ...model.capabilities, internet: !model.capabilities.internet } })}
            />
            <CapabilityTag
                icon={<Zap size={12} />}
                label={t.settings.modelSettings.reasoning}
                active={model.capabilities.reasoning || model.type === 'reasoning'}
                onToggle={() => onUpdate(model.uuid, { capabilities: { ...model.capabilities, reasoning: !model.capabilities.reasoning } })}
            />
        </View>

        <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 12 }}>
            <Text style={{ fontSize: 12, color: '#6b7280', marginRight: 8 }}>{t.settings.modelSettings.contextLength}:</Text>
            <TextInput
                value={model.contextLength?.toString() || ''}
                onChangeText={(v) => onUpdate(model.uuid, { contextLength: parseInt(v) || undefined })}
                keyboardType="numeric"
                placeholder="e.g. 128000"
                placeholderTextColor="#9ca3af"
                style={{ flex: 1, fontSize: 12, color: theme === 'dark' ? '#fff' : '#111', backgroundColor: theme === 'dark' ? '#27272a' : '#f3f4f6', borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 }}
            />
        </View>
    </View>
));

export function ModelSettingsModal({ visible, provider, onClose, onUpdateModels }: ModelSettingsModalProps) {
    const { t } = useI18n();
    const { theme } = useTheme();
    const { showToast } = useToast();
    const [models, setModels] = useState<ModelConfig[]>([]);
    const [searchQuery, setSearchQuery] = useState('');
    const [isFetching, setIsFetching] = useState(false);
    const [testResults, setTestResults] = useState<Record<string, { loading: boolean; success?: boolean; latency?: number; error?: string }>>({});

    useEffect(() => {
        if (visible && provider) {
            // 对没有 uuid 的旧数据进行迁移
            const initialModels = (provider.models || []).map(m => m.uuid ? m : { ...m, uuid: m.id + '-' + Math.random().toString(36).substr(2, 9) });
            setModels(initialModels);
            setSearchQuery('');
            setTestResults({});
        }
    }, [visible, provider]);

    const handleTestModel = useCallback(async (model: ModelConfig) => {
        if (!provider) return;

        setTestResults(prev => ({
            ...prev,
            [model.uuid]: { loading: true }
        }));

        try {
            const fullConfig = {
                ...model,
                provider: provider.type,
                apiKey: provider.apiKey,
                baseUrl: provider.baseUrl,
                vertexProject: provider.vertexProject,
                vertexLocation: provider.vertexLocation,
                vertexKeyJson: provider.vertexKeyJson,
                modelName: model.id,
                temperature: 0.7
            };

            const client = createLlmClient(fullConfig as any);
            const result = await client.testConnection();

            setTestResults(prev => ({
                ...prev,
                [model.uuid]: {
                    loading: false,
                    success: result.success,
                    latency: result.latency,
                    error: result.error
                }
            }));

            if (result.success) {
                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
            } else {
                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
            }
        } catch (e) {
            setTestResults(prev => ({
                ...prev,
                [model.uuid]: { loading: false, success: false, error: (e as Error).message }
            }));
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        }
    }, [provider]);

    const handleToggleModel = useCallback((uuid: string, enabled: boolean) => {
        setModels(prev => {
            const next = prev.map(m => m.uuid === uuid ? { ...m, enabled } : m);
            onUpdateModels(next);
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }, [onUpdateModels]);

    const handleDeleteModel = useCallback((uuid: string) => {
        setModels(prev => {
            const next = prev.filter(m => m.uuid !== uuid);
            onUpdateModels(next);
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    }, [onUpdateModels]);

    const handleUpdateModel = useCallback((uuid: string, updates: Partial<ModelConfig>) => {
        setModels(prev => {
            const next = prev.map(m => m.uuid === uuid ? { ...m, ...updates } : m);
            onUpdateModels(next);
            return next;
        });
    }, [onUpdateModels]);

    const handleAutoFetch = async () => {
        if (!provider?.apiKey || !provider?.baseUrl) {
            showToast(t.settings.modelSettings.fetchError, 'error');
            return;
        }

        setIsFetching(true);
        try {
            const response = await fetch(`${provider.baseUrl}/models`, {
                headers: { 'Authorization': `Bearer ${provider.apiKey}` },
            });

            if (!response?.ok) throw new Error(`HTTP ${response?.status}`);

            const data = await response.json();
            const apiModels = Array.isArray(data.data) ? data.data : (Array.isArray(data) ? data : (data.models || []));

            if (apiModels.length === 0) {
                showToast('未获取到任何模型', 'info');
                return;
            }

            const newModels: ModelConfig[] = apiModels.map((m: any) => {
                const modelId = (m.id || '').toLowerCase();
                const modelName = (m.name || '').toLowerCase();
                const fullText = `${modelId} ${modelName}`;

                let type: 'chat' | 'reasoning' | 'image' | 'embedding' = 'chat';
                if (fullText.includes('embedding')) {
                    type = 'embedding';
                } else if (fullText.includes('dall-e') || fullText.includes('stable-diffusion') || fullText.includes('flux') || fullText.includes('image') || fullText.includes('creative')) {
                    type = 'image';
                } else if (fullText.includes('o1') || fullText.includes('r1') || fullText.includes('reasoning') || fullText.includes('thinking')) {
                    type = 'reasoning';
                }

                const hasVision = fullText.includes('vision') || fullText.includes('-vl') || (modelId.endsWith('-v') && !modelId.includes('deepseek')) || fullText.includes('multimodal');
                const hasInternet = fullText.includes('search') || fullText.includes('internet') || fullText.includes('web') || fullText.includes('online');
                const isReasoning = type === 'reasoning' || fullText.includes('thought');

                let contextLength = m.context_window || m.max_context || m.context_length || undefined;
                if (!contextLength) {
                    const kMatch = fullText.match(/(\d+)k\b/);
                    const mMatch = fullText.match(/(\d+)m\b/);
                    if (kMatch) {
                        contextLength = parseInt(kMatch[1]) * 1000;
                    } else if (mMatch) {
                        contextLength = parseInt(mMatch[1]) * 1000000;
                    } else {
                        if (fullText.includes('gpt-4o') || fullText.includes('gpt-4-turbo')) contextLength = 128000;
                        else if (fullText.includes('gpt-4')) contextLength = 8192;
                        else if (fullText.includes('claude-3')) contextLength = 200000;
                        else if (fullText.includes('gemini-1.5')) contextLength = 1000000;
                        else if (fullText.includes('deepseek')) contextLength = 64000;
                        else if (fullText.includes('yi-')) contextLength = 200000;
                    }
                }

                return {
                    uuid: (m.id || 'm') + '-' + Math.random().toString(36).substr(2, 9),
                    id: m.id || m.name || 'unknown',
                    name: m.name || m.id || 'Unnamed Model',
                    enabled: false,
                    isAutoFetched: true,
                    type,
                    contextLength,
                    capabilities: {
                        vision: hasVision,
                        internet: hasInternet,
                        reasoning: isReasoning,
                    }
                };
            });

            setModels(prev => {
                const updatedModels = [...prev];
                newModels.forEach(nm => {
                    const existingIndex = updatedModels.findIndex(m => m.id === nm.id);
                    if (existingIndex > -1) {
                        const existing = updatedModels[existingIndex];
                        if (existing.isAutoFetched) {
                            updatedModels[existingIndex] = {
                                ...existing,
                                contextLength: nm.contextLength || existing.contextLength,
                                type: nm.type !== 'chat' ? nm.type : existing.type,
                                capabilities: {
                                    ...existing.capabilities,
                                    ...nm.capabilities,
                                }
                            };
                        }
                    } else {
                        updatedModels.push(nm);
                    }
                });
                onUpdateModels(updatedModels);
                return updatedModels;
            });
            showToast(t.settings.modelSettings.fetchSuccess.replace('{count}', newModels.length.toString()), 'success');
        } catch (error: any) {
            showToast(`${t.settings.modelSettings.fetchError}: ${error.message}`, 'error');
        } finally {
            setIsFetching(false);
        }
    };

    const handleManualAdd = () => {
        const id = `model-${Date.now()}`;
        const newModel: ModelConfig = {
            uuid: id + '-' + Math.random().toString(36).substr(2, 9),
            id: id,
            name: 'New Model',
            enabled: true,
            isAutoFetched: false,
            capabilities: {},
        };
        setModels(prev => {
            const next = [...prev, newModel];
            onUpdateModels(next);
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    };

    const handleDisableAll = () => {
        setModels(prev => {
            const next = prev.map(m => ({ ...m, enabled: false }));
            onUpdateModels(next);
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        showToast('已关闭所有模型', 'info');
    };

    const filteredModels = useMemo(() => {
        if (!searchQuery) return models;
        const query = searchQuery.toLowerCase();
        return models.filter(m =>
            m.name.toLowerCase().includes(query) ||
            m.id.toLowerCase().includes(query)
        );
    }, [models, searchQuery]);

    const renderItem = useCallback(({ item }: { item: ModelConfig }) => (
        <ModelItem
            model={item}
            onToggle={handleToggleModel}
            onDelete={handleDeleteModel}
            onUpdate={handleUpdateModel}
            onTest={handleTestModel}
            testStatus={testResults[item.uuid]}
            theme={theme}
            t={t}
        />
    ), [theme, t, handleToggleModel, handleDeleteModel, handleUpdateModel, handleTestModel, testResults]);

    return (
        <Modal visible={visible} animationType="slide" transparent={false} onRequestClose={onClose}>
            <View style={{ flex: 1, backgroundColor: theme === 'dark' ? '#000' : '#fff' }}>
                <View style={{
                    paddingTop: 60,
                    paddingHorizontal: 20,
                    paddingBottom: 16,
                    borderBottomWidth: 1,
                    borderBottomColor: theme === 'dark' ? '#27272a' : '#e5e7eb',
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between'
                }}>
                    <View style={{ flex: 1, marginRight: 16 }}>
                        <Text style={{ fontSize: 18, fontWeight: '900', color: theme === 'dark' ? '#fff' : '#111' }} numberOfLines={1}>
                            {provider?.name}
                        </Text>
                        <Text style={{ fontSize: 11, fontWeight: 'bold', color: '#9ca3af', textTransform: 'uppercase', letterSpacing: 1, marginTop: 2 }}>
                            {t.settings.modelSettings.title}
                        </Text>
                    </View>
                    <TouchableOpacity onPress={onClose} style={{ padding: 8 }}>
                        <X size={24} color={theme === 'dark' ? '#9ca3af' : '#6b7280'} />
                    </TouchableOpacity>
                </View>

                <View style={{ paddingHorizontal: 16, marginTop: 16 }}>
                    <View style={{
                        flexDirection: 'row',
                        alignItems: 'center',
                        backgroundColor: theme === 'dark' ? '#18181b' : '#f3f4f6',
                        borderRadius: 12,
                        paddingHorizontal: 12,
                        height: 48,
                        borderWidth: 1,
                        borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb'
                    }}>
                        <Search size={18} color="#9ca3af" />
                        <TextInput
                            placeholder={t.settings.modelSettings.searchPlaceholder}
                            placeholderTextColor="#9ca3af"
                            value={searchQuery}
                            onChangeText={setSearchQuery}
                            style={{ flex: 1, marginLeft: 8, fontSize: 14, color: theme === 'dark' ? '#fff' : '#111' }}
                        />
                        {searchQuery !== '' && (
                            <TouchableOpacity onPress={() => setSearchQuery('')}>
                                <X size={16} color="#9ca3af" />
                            </TouchableOpacity>
                        )}
                    </View>
                </View>

                <View style={{ padding: 16, gap: 12 }}>
                    <View style={{ flexDirection: 'row', gap: 12 }}>
                        <TouchableOpacity
                            onPress={handleAutoFetch}
                            disabled={isFetching}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: theme === 'dark' ? '#18181b' : '#f3f4f6',
                                paddingVertical: 12,
                                borderRadius: 12,
                                borderWidth: 1,
                                borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb'
                            }}
                        >
                            {isFetching ? <ActivityIndicator size="small" color="#6366f1" style={{ marginRight: 8 }} /> : <RefreshCw size={18} color="#6366f1" style={{ marginRight: 8 }} />}
                            <Text style={{ fontWeight: 'bold', color: theme === 'dark' ? '#fff' : '#111' }}>
                                {t.settings.modelSettings.autoFetch}
                            </Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                            onPress={handleManualAdd}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: theme === 'dark' ? '#18181b' : '#f3f4f6',
                                paddingVertical: 12,
                                borderRadius: 12,
                                borderWidth: 1,
                                borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb'
                            }}
                        >
                            <Plus size={18} color="#10b981" style={{ marginRight: 8 }} />
                            <Text style={{ fontWeight: 'bold', color: theme === 'dark' ? '#fff' : '#111' }}>
                                {t.settings.modelSettings.manualAdd}
                            </Text>
                        </TouchableOpacity>
                    </View>
                    <TouchableOpacity
                        onPress={handleDisableAll}
                        style={{
                            flexDirection: 'row',
                            alignItems: 'center',
                            justifyContent: 'center',
                            backgroundColor: '#ef444410',
                            paddingVertical: 12,
                            borderRadius: 12,
                            borderWidth: 1,
                            borderColor: '#ef444420'
                        }}
                    >
                        <Trash2 size={18} color="#ef4444" style={{ marginRight: 8 }} />
                        <Text style={{ fontWeight: 'bold', color: '#ef4444' }}>
                            {t.settings.modelSettings.disableAll}
                        </Text>
                    </TouchableOpacity>
                </View>

                <TypedFlashList
                    data={filteredModels}
                    renderItem={renderItem}
                    estimatedItemSize={210}
                    keyExtractor={(item: ModelConfig) => item.uuid}
                    contentContainerStyle={{ paddingBottom: 40 }}
                />
            </View>
        </Modal>
    );
}

function TypeButton({ label, active, onPress }: { label: string, active: boolean, onPress: () => void }) {
    const { theme } = useTheme();
    return (
        <TouchableOpacity
            onPress={onPress}
            style={{
                paddingHorizontal: 10,
                paddingVertical: 4,
                borderRadius: 6,
                backgroundColor: active ? '#6366f1' : (theme === 'dark' ? '#27272a' : '#f3f4f6'),
            }}
        >
            <Text style={{ fontSize: 10, fontWeight: 'bold', color: active ? '#fff' : '#6b7280' }}>{label}</Text>
        </TouchableOpacity>
    );
}

function CapabilityTag({ icon, label, active, onToggle }: { icon: React.ReactNode, label: string, active?: boolean, onToggle: () => void }) {
    const { theme } = useTheme();
    return (
        <TouchableOpacity
            onPress={onToggle}
            style={{
                flexDirection: 'row',
                alignItems: 'center',
                paddingHorizontal: 8,
                paddingVertical: 4,
                borderRadius: 8,
                backgroundColor: active ? '#6366f115' : (theme === 'dark' ? '#27272a' : '#f3f4f6'),
                borderWidth: 1,
                borderColor: active ? '#6366f1' : 'transparent',
                gap: 4
            }}
        >
            <View style={{ opacity: active ? 1 : 0.5 }}>
                {React.isValidElement(icon) && React.cloneElement(icon as React.ReactElement<any>, {
                    color: active ? '#6366f1' : '#6b7280'
                })}
            </View>
            <Text style={{ fontSize: 10, fontWeight: 'bold', color: active ? '#6366f1' : '#6b7280' }}>{label}</Text>
        </TouchableOpacity>
    );
}

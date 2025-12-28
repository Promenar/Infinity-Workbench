import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { View, Text, TouchableOpacity, Modal, TextInput, ActivityIndicator, Platform, KeyboardAvoidingView, ScrollView } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Switch } from '../../components/ui/Switch';
import { X, Plus, Trash2, Cpu, Globe, Eye, Zap, RefreshCw, Search, CheckCircle2, AlertCircle, ChevronLeft } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { GlassHeader } from '../../components/ui/GlassHeader';
import { FlashList } from '@shopify/flash-list';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { ProviderConfig, ModelConfig } from '../../store/api-store';
import { useToast } from '../../components/ui/Toast';
import { createLlmClient } from '../../lib/llm/factory';
import { findContextLength, extractContextLengthFromName } from '../../lib/llm/model-specs';

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
    isDark,
    t
}: {
    model: ModelConfig;
    onToggle: (uuid: string, enabled: boolean) => void;
    onDelete: (uuid: string) => void;
    onUpdate: (uuid: string, updates: Partial<ModelConfig>) => void;
    onTest: (model: ModelConfig) => void;
    testStatus?: { loading: boolean; success?: boolean; latency?: number; error?: string };
    theme: string;
    isDark: boolean;
    t: any;
}) => {
    // 性能优化：使用本地状态管理输入，仅在 OnBlur 时同步到全局
    const [localName, setLocalName] = useState(model.name);
    const [localId, setLocalId] = useState(model.id);
    const [localContext, setLocalContext] = useState(model.contextLength?.toString() || '');

    useEffect(() => { setLocalName(model.name); }, [model.name]);
    useEffect(() => { setLocalId(model.id); }, [model.id]);
    useEffect(() => { setLocalContext(model.contextLength?.toString() || ''); }, [model.contextLength]);

    const handleSync = useCallback((updates: Partial<ModelConfig>) => {
        onUpdate(model.uuid, updates);
    }, [onUpdate, model.uuid]);

    return (
        <View
            style={{
                backgroundColor: isDark ? '#18181b' : '#f9fafb',
                borderRadius: 12,
                padding: 10,
                marginBottom: 8,
                marginHorizontal: 16,
                borderWidth: 1,
                borderColor: isDark ? '#27272a' : '#e5e7eb'
            }}
        >
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                <View style={{ flex: 1, marginRight: 10 }}>
                    <TextInput
                        value={localName}
                        onChangeText={setLocalName}
                        onBlur={() => localName !== model.name && handleSync({ name: localName })}
                        style={{
                            fontSize: 14,
                            fontWeight: 'bold',
                            color: isDark ? '#fff' : '#111',
                            marginBottom: 4,
                        }}
                        placeholder="Model Name"
                        placeholderTextColor="#9ca3af"
                    />
                    <TextInput
                        value={localId}
                        editable={!model.isAutoFetched}
                        onChangeText={setLocalId}
                        onBlur={() => localId !== model.id && handleSync({ id: localId })}
                        style={{
                            fontSize: 10,
                            color: model.isAutoFetched ? '#6b7280' : (isDark ? '#d1d5db' : '#4b5563'),
                            fontFamily: 'monospace',
                        }}
                        placeholder="api-model-id"
                        placeholderTextColor="#9ca3af"
                    />
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
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
                    />
                    <TouchableOpacity onPress={() => onDelete(model.uuid)}>
                        <Trash2 size={18} color="#ef4444" />
                    </TouchableOpacity>
                </View>
            </View>

            {testStatus && !testStatus.loading && (testStatus.success !== undefined) && (
                <View style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    backgroundColor: testStatus.success ? 'rgba(34, 197, 94, 0.1)' : 'rgba(239, 68, 68, 0.1)',
                    paddingHorizontal: 8,
                    paddingVertical: 4,
                    borderRadius: 6,
                    marginBottom: 8
                }}>
                    {testStatus.success ? (
                        <CheckCircle2 size={10} color="#22c55e" />
                    ) : (
                        <AlertCircle size={10} color="#ef4444" />
                    )}
                    <Text style={{
                        fontSize: 10,
                        marginLeft: 4,
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

            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 5, marginBottom: 6 }}>
                {(['chat', 'reasoning', 'image', 'embedding'] as const).map((type) => (
                    <TypeButton
                        key={type}
                        label={t.settings.modelSettings[`type${type.charAt(0).toUpperCase() + type.slice(1)}` as keyof typeof t.settings.modelSettings] as string}
                        active={(model.type || 'chat') === type}
                        onPress={() => onUpdate(model.uuid, { type })}
                    />
                ))}
            </View>

            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 4, marginBottom: 5 }}>
                <CapabilityTag
                    icon={<Eye size={11} />}
                    label={t.settings.modelSettings.vision}
                    active={model.capabilities.vision}
                    onToggle={() => onUpdate(model.uuid, { capabilities: { ...model.capabilities, vision: !model.capabilities.vision } })}
                />
                <CapabilityTag
                    icon={<Globe size={11} />}
                    label={t.settings.modelSettings.internet}
                    active={model.capabilities.internet}
                    onToggle={() => onUpdate(model.uuid, { capabilities: { ...model.capabilities, internet: !model.capabilities.internet } })}
                />
                <CapabilityTag
                    icon={<Zap size={11} />}
                    label={t.settings.modelSettings.reasoning}
                    active={model.capabilities.reasoning || model.type === 'reasoning'}
                    onToggle={() => onUpdate(model.uuid, { capabilities: { ...model.capabilities, reasoning: !model.capabilities.reasoning } })}
                />
            </View>

            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                <Text style={{ fontSize: 10, color: '#6b7280', marginRight: 6 }}>{t.settings.modelSettings.contextLength}:</Text>
                <TextInput
                    value={localContext}
                    onChangeText={setLocalContext}
                    onBlur={() => {
                        const val = parseInt(localContext) || undefined;
                        if (val !== model.contextLength) handleSync({ contextLength: val });
                    }}
                    keyboardType="numeric"
                    placeholder="e.g. 128000"
                    placeholderTextColor="#9ca3af"
                    style={{ flex: 1, fontSize: 11, color: isDark ? '#fff' : '#111', backgroundColor: isDark ? '#27272a' : '#f3f4f6', borderRadius: 4, paddingHorizontal: 5, paddingVertical: 2 }}
                />
            </View>
        </View>
    );
}, (prev, next) => {
    // 自定义 Memo 逻辑：深比较必要的属性
    return prev.model.uuid === next.model.uuid &&
        prev.model.name === next.model.name &&
        prev.model.id === next.model.id &&
        prev.model.enabled === next.model.enabled &&
        prev.model.type === next.model.type &&
        prev.model.contextLength === next.model.contextLength &&
        prev.model.capabilities.vision === next.model.capabilities.vision &&
        prev.model.capabilities.internet === next.model.capabilities.internet &&
        prev.model.capabilities.reasoning === next.model.capabilities.reasoning &&
        prev.testStatus === next.testStatus &&
        prev.theme === next.theme;
});

export function ModelSettingsModal({ visible, provider, onClose, onUpdateModels }: ModelSettingsModalProps) {
    const { t } = useI18n();
    const { theme, isDark } = useTheme();
    const { showToast } = useToast();
    const insets = useSafeAreaInsets();
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
            // Defer the parent update to next tick or strictly outside certain render phases
            requestAnimationFrame(() => onUpdateModels(next));
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }, [onUpdateModels]);

    const handleDeleteModel = useCallback((uuid: string) => {
        setModels(prev => {
            const next = prev.filter(m => m.uuid !== uuid);
            requestAnimationFrame(() => onUpdateModels(next));
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    }, [onUpdateModels]);

    const handleUpdateModel = useCallback((uuid: string, updates: Partial<ModelConfig>) => {
        setModels(prev => {
            const next = prev.map(m => m.uuid === uuid ? { ...m, ...updates } : m);
            requestAnimationFrame(() => onUpdateModels(next));
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

                // 模型类型自动识别（优先级：embedding > image > reasoning > chat）
                let type: 'chat' | 'reasoning' | 'image' | 'embedding' = 'chat';

                // Embedding 模型识别（向量/嵌入）
                if (
                    fullText.includes('embedding') ||
                    fullText.includes('embed') ||
                    fullText.includes('vector') ||
                    fullText.includes('bge-') ||
                    fullText.includes('gte-') ||
                    fullText.includes('m3e-') ||
                    fullText.includes('bce-') ||
                    fullText.includes('sentence-')
                ) {
                    type = 'embedding';
                }
                // Image 模型识别（图像生成）
                else if (
                    fullText.includes('dall-e') ||
                    fullText.includes('dalle') ||
                    fullText.includes('stable-diffusion') ||
                    fullText.includes('sd-') ||
                    fullText.includes('sdxl') ||
                    fullText.includes('flux') ||
                    fullText.includes('midjourney') ||
                    fullText.includes('image') ||
                    fullText.includes('creative') ||
                    fullText.includes('cogview') ||
                    fullText.includes('wanx') ||
                    fullText.includes('hunyuan-turbo') ||
                    fullText.includes('hunyuan-standard') ||
                    fullText.includes('ernie-vilg') ||
                    fullText.includes('文心一格')
                ) {
                    type = 'image';
                }
                // Reasoning 模型识别（推理/深度思考）
                else if (
                    fullText.includes('o1') ||
                    fullText.includes('r1') ||
                    fullText.includes('reasoning') ||
                    fullText.includes('reasoner') ||
                    fullText.includes('reason') ||
                    fullText.includes('thinking') ||
                    fullText.includes('thought') ||
                    fullText.includes('deepthink') ||
                    fullText.includes('deep-think') ||
                    fullText.includes('chain-of-thought') ||
                    fullText.includes('cot')
                ) {
                    type = 'reasoning';
                }

                const hasVision = fullText.includes('vision') || fullText.includes('-vl') || (modelId.endsWith('-v') && !modelId.includes('deepseek')) || fullText.includes('multimodal');
                const hasInternet = fullText.includes('search') || fullText.includes('internet') || fullText.includes('web') || fullText.includes('online');
                const isReasoning = type === 'reasoning' || fullText.includes('thought');

                // 上下文长度推断（优先级：API 返回 > 数据库匹配 > 名称提取）
                let contextLength = m.context_window || m.max_context || m.context_length || undefined;
                if (!contextLength) {
                    // 1. 尝试从模型规格数据库匹配
                    contextLength = findContextLength(m.id || m.name || '');

                    // 2. 如果数据库没有，尝试从名称提取（如 "128k", "2m"）
                    if (!contextLength) {
                        contextLength = extractContextLengthFromName(fullText);
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
                        // 模型已存在，更新时保留用户设置
                        const existing = updatedModels[existingIndex];
                        if (existing.isAutoFetched) {
                            updatedModels[existingIndex] = {
                                ...existing,
                                contextLength: nm.contextLength || existing.contextLength,
                                // 保留用户手动设置的类型，除非 API 明确返回了非默认类型
                                type: existing.type || nm.type,
                                capabilities: {
                                    ...existing.capabilities,
                                    // 只添加新的能力，不覆盖已有的
                                    vision: existing.capabilities.vision || nm.capabilities.vision,
                                    internet: existing.capabilities.internet || nm.capabilities.internet,
                                    reasoning: existing.capabilities.reasoning || nm.capabilities.reasoning,
                                }
                            };
                        }
                    } else {
                        // 新模型，直接添加
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
        showToast(t.settings.modelSettings.disableAllSuccess || '已关闭所有模型', 'info');
    };

    const handleDeleteAll = () => {
        setModels([]);
        onUpdateModels([]);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
        showToast(t.settings.modelSettings.deleteAllSuccess || '已删除所有模型', 'info');
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
            isDark={isDark}
            t={t}
        />
    ), [theme, isDark, t, handleToggleModel, handleDeleteModel, handleUpdateModel, handleTestModel, testResults]);

    return (
        <Modal visible={visible} animationType="slide" transparent={false} onRequestClose={onClose}>
            <KeyboardAvoidingView
                behavior={Platform.OS === 'ios' ? 'padding' : undefined}
                style={{ flex: 1, backgroundColor: theme === 'dark' ? '#000' : '#fff' }}
            >
                <GlassHeader
                    title={provider?.name || ''}
                    subtitle={t.settings.modelSettings.title}
                    leftAction={{
                        icon: <ChevronLeft size={24} color={theme === 'dark' ? '#fff' : '#000'} />,
                        onPress: onClose
                    }}
                    intensity={theme === 'dark' ? 40 : 60}
                />

                <View style={{ paddingHorizontal: 16, marginTop: insets.top + 64 + 16 }}>
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

                <View style={{ paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 }}>
                    <View style={{ flexDirection: 'row', gap: 8 }}>
                        {/* 自动拉取 */}
                        <TouchableOpacity
                            onPress={handleAutoFetch}
                            disabled={isFetching}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: theme === 'dark' ? '#18181b' : '#f3f4f6',
                                paddingVertical: 10,
                                borderRadius: 10,
                                borderWidth: 1,
                                borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb',
                                gap: 4
                            }}
                        >
                            {isFetching ? <ActivityIndicator size="small" color="#6366f1" /> : <RefreshCw size={16} color="#6366f1" />}
                            <Text style={{ fontWeight: 'bold', color: theme === 'dark' ? '#fff' : '#111', fontSize: 12 }}>
                                {t.settings.modelSettings.fetch || '拉取'}
                            </Text>
                        </TouchableOpacity>

                        {/* 手动添加 */}
                        <TouchableOpacity
                            onPress={handleManualAdd}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: theme === 'dark' ? '#18181b' : '#f3f4f6',
                                paddingVertical: 10,
                                borderRadius: 10,
                                borderWidth: 1,
                                borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb',
                                gap: 4
                            }}
                        >
                            <Plus size={16} color="#10b981" />
                            <Text style={{ fontWeight: 'bold', color: theme === 'dark' ? '#fff' : '#111', fontSize: 12 }}>
                                {t.settings.modelSettings.add || '添加'}
                            </Text>
                        </TouchableOpacity>

                        {/* 全部关闭 */}
                        <TouchableOpacity
                            onPress={handleDisableAll}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: '#fef2f2',
                                paddingVertical: 10,
                                borderRadius: 10,
                                borderWidth: 1,
                                borderColor: '#fecaca',
                                gap: 4
                            }}
                        >
                            <X size={16} color="#ef4444" />
                            <Text style={{ fontWeight: 'bold', color: '#ef4444', fontSize: 12 }}>
                                {t.settings.modelSettings.close || '关闭'}
                            </Text>
                        </TouchableOpacity>

                        {/* 全部删除 */}
                        <TouchableOpacity
                            onPress={handleDeleteAll}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: '#fef2f2',
                                paddingVertical: 10,
                                borderRadius: 10,
                                borderWidth: 1,
                                borderColor: '#fecaca',
                                gap: 4
                            }}
                        >
                            <Trash2 size={16} color="#dc2626" />
                            <Text style={{ fontWeight: 'bold', color: '#dc2626', fontSize: 12 }}>
                                {t.settings.modelSettings.deleteAll || '删除'}
                            </Text>
                        </TouchableOpacity>
                    </View>
                </View>

                <TypedFlashList
                    data={filteredModels}
                    renderItem={renderItem}
                    estimatedItemSize={140} // 经过测量的大致平均高度
                    drawDistance={1200} // 降低预渲染距离以换取更高的主线程空闲率
                    keyExtractor={(item: ModelConfig) => item.uuid}
                    contentContainerStyle={{ paddingBottom: 100, paddingTop: 10 }}
                    extraData={testResults} // 确保状态更新能触发必要重绘，而无需重新计算整个 renderItem 闭包（配合 ModelItem.memo 效果更佳）
                    params={{ keyboardShouldPersistTaps: 'handled' }}
                />
            </KeyboardAvoidingView>
        </Modal>
    );
}

function TypeButton({ label, active, onPress }: { label: string, active: boolean, onPress: () => void }) {
    const { theme } = useTheme();
    return (
        <TouchableOpacity
            onPress={onPress}
            style={{
                paddingHorizontal: 8,
                paddingVertical: 3,
                borderRadius: 5,
                backgroundColor: active ? '#6366f1' : (theme === 'dark' ? '#27272a' : '#f3f4f6'),
            }}
        >
            <Text style={{ fontSize: 9, fontWeight: 'bold', color: active ? '#fff' : '#6b7280' }}>{label}</Text>
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
                paddingHorizontal: 6,
                paddingVertical: 3,
                borderRadius: 6,
                backgroundColor: active ? '#6366f115' : (theme === 'dark' ? '#27272a' : '#f3f4f6'),
                borderWidth: 1,
                borderColor: active ? '#6366f1' : 'transparent',
                gap: 3
            }}
        >
            <View style={{ opacity: active ? 1 : 0.5 }}>
                {React.isValidElement(icon) && React.cloneElement(icon as React.ReactElement<any>, {
                    color: active ? '#6366f1' : '#6b7280'
                })}
            </View>
            <Text style={{ fontSize: 9, fontWeight: 'bold', color: active ? '#6366f1' : '#6b7280' }}>{label}</Text>
        </TouchableOpacity>
    );
}

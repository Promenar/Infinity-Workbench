import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import {
    View,
    Text,
    TouchableOpacity,
    TextInput,
    ActivityIndicator,
    Platform,
    Keyboard,
} from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, { FadeIn, useSharedValue, useAnimatedStyle, withTiming, interpolateColor } from 'react-native-reanimated';
import { Switch } from '../../../components/ui/Switch';
import {
    X,
    Plus,
    Trash2,
    Cpu,
    Globe,
    Eye,
    Zap,
    RefreshCw,
    CheckCircle2,
    AlertCircle,
    ChevronLeft,
    Edit2,
} from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { GlassHeader } from '../../../components/ui/GlassHeader';
import { PageLayout } from '../../../components/ui/PageLayout';
import { AnimatedSearchBar } from '../../../components/ui/AnimatedSearchBar';
import { AnimatedInput } from '../../../components/ui/AnimatedInput';
import { Marquee } from '../../../components/ui/Marquee';
import { FlashList } from '@shopify/flash-list';
import { useI18n } from '../../../lib/i18n';
import { useTheme } from '../../../theme/ThemeProvider';
import { ModelConfig, useApiStore } from '../../../store/api-store';
import { useToast } from '../../../components/ui/Toast';
import { createLlmClient } from '../../../lib/llm/factory';
import { ModelService } from '../../../lib/provider-parser';
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog';
import { Typography } from '../../../components/ui/Typography';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';

// 使用 any 绕过某些环境下 FlashList 的类型检测问题
const TypedFlashList = FlashList as any;

// Store 写入防抖时间 (毫秒)
const STORE_WRITE_DEBOUNCE_MS = 200;
// 按钮交互冷却时间 (毫秒)
const INTERACTION_COOLDOWN_MS = 120;

// 提取并 Memoize 模型项以获得极致性能
const ModelItem = React.memo(
    ({
        model,
        onToggle,
        onDelete,
        onUpdate,
        onTest,
        testStatus,
    }: {
        model: ModelConfig;
        onToggle: (uuid: string, enabled: boolean) => void;
        onDelete: (uuid: string) => void;
        onUpdate: (uuid: string, updates: Partial<ModelConfig>) => void;
        onTest: (model: ModelConfig) => void;
        testStatus?: { loading: boolean; success?: boolean; latency?: number; error?: string };
    }) => {
        const { t } = useI18n();
        const { isDark, colors } = useTheme();
        // 性能优化：使用本地状态管理输入，仅在 OnBlur 时同步到全局
        const [localName, setLocalName] = useState(model.name);
        const [localId, setLocalId] = useState(model.id);
        const [localContext, setLocalContext] = useState(model.contextLength?.toString() || '');
        const [isEditingName, setIsEditingName] = useState(false);
        const [isEditingId, setIsEditingId] = useState(false);

        useEffect(() => {
            setLocalName(model.name);
            setLocalId(model.id);
            setLocalContext(model.contextLength?.toString() || '');
        }, [model.name, model.id, model.contextLength]);

        const handleSync = useCallback(
            (updates: Partial<ModelConfig>) => {
                onUpdate(model.uuid, updates);
            },
            [onUpdate, model.uuid],
        );

        return (
            <Animated.View
                entering={FadeIn.duration(150)}
                style={{
                    backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : '#fff',
                    borderRadius: 20,
                    padding: 16,
                    marginBottom: 10,
                    marginHorizontal: 16,
                    borderWidth: 1,
                    borderColor: isDark ? 'rgba(99, 102, 241, 0.1)' : 'rgba(0,0,0,0.05)',
                    minHeight: 185,
                }}
            >
                <View
                    style={{
                        flexDirection: 'row',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        marginBottom: 10,
                    }}
                >
                    <View style={{ flex: 1, marginRight: 10 }}>
                        <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 4 }}>
                            {!isEditingName ? (
                                <TouchableOpacity
                                    onPress={() => setIsEditingName(true)}
                                    style={{
                                        flex: 1,
                                        height: 30,
                                        justifyContent: 'center',
                                        paddingHorizontal: 8,
                                        borderWidth: 1,
                                        borderColor: 'transparent',
                                        borderRadius: 8,
                                    }}
                                >
                                    <Marquee
                                        text={localName || t.settings.modelSettings.unnamedModel}
                                        className="font-bold text-sm text-gray-900 dark:text-white"
                                        style={{ height: '100%' }}
                                        textProps={{
                                            style: {
                                                fontSize: 14,
                                                lineHeight: 18,
                                                includeFontPadding: false
                                            }
                                        }}
                                    />
                                </TouchableOpacity>
                            ) : (
                                <AnimatedInput
                                    value={localName}
                                    autoFocus
                                    onChangeText={setLocalName}
                                    onBlur={() => {
                                        setIsEditingName(false);
                                        if (localName !== model.name) handleSync({ name: localName });
                                    }}
                                    containerStyle={{
                                        flex: 1,
                                        height: 30,
                                    }}
                                    inputStyle={{
                                        fontSize: 14,
                                        lineHeight: 18,
                                        fontWeight: 'bold',
                                        paddingVertical: 0,
                                        includeFontPadding: false,
                                    }}
                                    placeholder={t.settings.modelSettings.namePlaceholder}
                                />
                            )}
                            <Edit2 size={12} color="#9ca3af" style={{ opacity: 0.5, marginLeft: 4 }} />
                        </View>
                        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                            <View style={{ flex: 1 }}>
                                {!isEditingId ? (
                                    <TouchableOpacity
                                        onPress={() => setIsEditingId(true)}
                                        style={{
                                            flex: 1,
                                            height: 26,
                                            justifyContent: 'center',
                                            paddingHorizontal: 8,
                                            borderWidth: 1,
                                            borderColor: 'transparent',
                                            borderRadius: 8,
                                        }}
                                    >
                                        <Text
                                            numberOfLines={1}
                                            ellipsizeMode="tail"
                                            style={{
                                                fontSize: 10,
                                                lineHeight: 14,
                                                color: model.isAutoFetched ? '#6b7280' : isDark ? '#d1d5db' : '#4b5563',
                                                fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                                includeFontPadding: false,
                                            }}
                                        >
                                            {localId}
                                        </Text>
                                    </TouchableOpacity>
                                ) : (
                                    <AnimatedInput
                                        value={localId}
                                        autoFocus
                                        onChangeText={setLocalId}
                                        onBlur={() => {
                                            setIsEditingId(false);
                                            if (localId !== model.id) handleSync({ id: localId });
                                        }}
                                        containerStyle={{
                                            flex: 1,
                                            height: 26,
                                        }}
                                        inputStyle={{
                                            fontSize: 10,
                                            lineHeight: 14,
                                            fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                            paddingVertical: 0,
                                            includeFontPadding: false,
                                        }}
                                        placeholder={t.settings.modelSettings.modelIdPlaceholder}
                                    />
                                )}
                            </View>
                            <Edit2 size={10} color="#9ca3af" style={{ opacity: 0.5, marginLeft: 4 }} />
                        </View>
                    </View>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                        <TouchableOpacity
                            onPress={() => onTest(model)}
                            disabled={testStatus?.loading}
                            style={{ padding: 4 }}
                        >
                            {testStatus?.loading ? (
                                <ActivityIndicator size="small" color={colors[500]} />
                            ) : (
                                <RefreshCw
                                    size={18}
                                    color={
                                        testStatus?.success === true
                                            ? '#22c55e'
                                            : testStatus?.success === false
                                                ? '#ef4444'
                                                : colors[500]
                                    }
                                />
                            )}
                        </TouchableOpacity>

                        <Switch
                            value={!!model.enabled}
                            onValueChange={(v) => onToggle(model.uuid, v)}
                        />
                        <TouchableOpacity onPress={() => onDelete(model.uuid)}>
                            <Trash2 size={18} color="#ef4444" />
                        </TouchableOpacity>
                    </View>
                </View>

                {testStatus && !testStatus.loading && testStatus.success !== undefined && (
                    <View
                        style={{
                            flexDirection: 'row',
                            alignItems: 'center',
                            backgroundColor: testStatus.success
                                ? 'rgba(34, 197, 94, 0.1)'
                                : 'rgba(239, 68, 68, 0.1)',
                            paddingHorizontal: 8,
                            paddingVertical: 4,
                            borderRadius: 6,
                            marginBottom: 8,
                        }}
                    >
                        {testStatus.success ? (
                            <CheckCircle2 size={10} color="#22c55e" />
                        ) : (
                            <AlertCircle size={10} color="#ef4444" />
                        )}
                        <Text
                            style={{
                                fontSize: 10,
                                marginLeft: 4,
                                color: testStatus.success ? '#22c55e' : '#ef4444',
                                fontWeight: '600',
                                flex: 1,
                            }}
                        >
                            {testStatus.success
                                ? t.settings.modelSettings.testSuccess.replace(
                                    '{latency}',
                                    (testStatus.latency || 0).toString(),
                                )
                                : t.settings.modelSettings.testError.replace(
                                    '{error}',
                                    testStatus.error || 'Unknown',
                                )}
                        </Text>
                    </View>
                )}

                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 5, marginBottom: 6 }}>
                    {(['chat', 'reasoning', 'image', 'embedding', 'rerank'] as const).map((type) => (
                        <TypeButton
                            key={type}
                            label={
                                t.settings.modelSettings[
                                `type${type.charAt(0).toUpperCase() + type.slice(1)}` as keyof typeof t.settings.modelSettings
                                ] as string
                            }
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
                        onToggle={() =>
                            onUpdate(model.uuid, {
                                capabilities: { ...model.capabilities, vision: !model.capabilities.vision },
                            })
                        }
                    />
                    <CapabilityTag
                        icon={<Globe size={11} />}
                        label={t.settings.modelSettings.internet}
                        active={model.capabilities.internet}
                        onToggle={() =>
                            onUpdate(model.uuid, {
                                capabilities: { ...model.capabilities, internet: !model.capabilities.internet },
                            })
                        }
                    />
                    <CapabilityTag
                        icon={<Zap size={11} />}
                        label={t.settings.modelSettings.reasoning}
                        active={model.capabilities.reasoning || model.type === 'reasoning'}
                        onToggle={() =>
                            onUpdate(model.uuid, {
                                capabilities: { ...model.capabilities, reasoning: !model.capabilities.reasoning },
                            })
                        }
                    />
                </View>

                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                    <Text style={{ fontSize: 10, color: '#6b7280', marginRight: 6 }}>
                        {t.settings.modelSettings.contextLength}:
                    </Text>
                    <AnimatedInput
                        value={localContext}
                        onChangeText={setLocalContext}
                        onBlur={() => {
                            const val = parseInt(localContext) || undefined;
                            if (val !== model.contextLength) handleSync({ contextLength: val });
                        }}
                        keyboardType="numeric"
                        placeholder="e.g. 128000"
                        containerStyle={{
                            flex: 1,
                            height: 28,
                        }}
                        inputStyle={{
                            fontSize: 11,
                            paddingVertical: 0,
                        }}
                    />
                </View>
            </Animated.View>
        );
    },
    (prev, next) => {
        // [FIX I3+I4] 深比较所有必要属性 + testStatus 深比较替代引用比较
        const testEqual =
            prev.testStatus?.loading === next.testStatus?.loading &&
            prev.testStatus?.success === next.testStatus?.success &&
            prev.testStatus?.latency === next.testStatus?.latency &&
            prev.testStatus?.error === next.testStatus?.error;
        return (
            prev.model.uuid === next.model.uuid &&
            prev.model.name === next.model.name &&
            prev.model.id === next.model.id &&
            prev.model.enabled === next.model.enabled &&
            prev.model.type === next.model.type &&
            prev.model.contextLength === next.model.contextLength &&
            prev.model.isAutoFetched === next.model.isAutoFetched &&
            prev.model.icon === next.model.icon &&
            prev.model.capabilities.vision === next.model.capabilities.vision &&
            prev.model.capabilities.internet === next.model.capabilities.internet &&
            prev.model.capabilities.reasoning === next.model.capabilities.reasoning &&
            testEqual
        );
    },
);

export default function ProviderModelsScreen() {
    const router = useRouter();
    const { providerId } = useLocalSearchParams();
    const { t } = useI18n();
    const { theme, isDark, colors } = useTheme();
    const { showToast } = useToast();
    const insets = useSafeAreaInsets();
    const { providers, updateProvider } = useApiStore();

    const provider = useMemo(() =>
        providers.find(p => p.id === providerId),
        [providers, providerId]
    );

    const [models, setModels] = useState<ModelConfig[]>([]);
    const [searchQuery, setSearchQuery] = useState('');
    const [isFetching, setIsFetching] = useState(false);
    const [testResults, setTestResults] = useState<
        Record<string, { loading: boolean; success?: boolean; latency?: number; error?: string }>
    >({});
    const inputRef = useRef<TextInput>(null);

    // [FIX I5] 用 ref 持有最新 testResults，避免 renderItem 闭包过期
    const testResultsRef = useRef(testResults);
    testResultsRef.current = testResults;

    // [FIX P1+D3] 防抖 Store 写入机制
    const storeWriteTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const pendingModelsRef = useRef<ModelConfig[]>([]);
    const lastLocalEditRef = useRef<number>(0);

    // 防抖写入 Store（用于高频操作：toggle, update）
    const scheduleStoreWrite = useCallback((newModels: ModelConfig[]) => {
        lastLocalEditRef.current = Date.now();
        pendingModelsRef.current = newModels;
        if (storeWriteTimerRef.current) {
            clearTimeout(storeWriteTimerRef.current);
        }
        storeWriteTimerRef.current = setTimeout(() => {
            if (provider) {
                updateProvider(provider.id, { models: pendingModelsRef.current });
            }
            storeWriteTimerRef.current = null;
        }, STORE_WRITE_DEBOUNCE_MS);
    }, [provider, updateProvider]);

    // 立即写入 Store（用于低频操作：delete, add, batch）
    const writeToStore = useCallback((newModels: ModelConfig[]) => {
        lastLocalEditRef.current = Date.now();
        if (storeWriteTimerRef.current) {
            clearTimeout(storeWriteTimerRef.current);
            storeWriteTimerRef.current = null;
        }
        if (provider) {
            updateProvider(provider.id, { models: newModels });
        }
    }, [provider, updateProvider]);

    // 组件卸载时刷新待写入数据
    useEffect(() => {
        return () => {
            if (storeWriteTimerRef.current) {
                clearTimeout(storeWriteTimerRef.current);
                if (provider) {
                    updateProvider(provider.id, { models: pendingModelsRef.current });
                }
            }
        };
    }, [provider, updateProvider]);

    useEffect(() => {
        const keyboardDidHideListener = Keyboard.addListener('keyboardDidHide', () => {
            inputRef.current?.blur();
        });
        return () => {
            keyboardDidHideListener.remove();
        };
    }, []);

    // Confirmation Dialog State
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

    const [isReady, setIsReady] = useState(false);

    // [FIX I1+I2] 同步内部状态与 Store 数据，移除 setTimeout(0)，添加防抖窗口保护
    useEffect(() => {
        if (provider) {
            // 跳过防抖窗口内的同步，避免覆盖本地未写入的编辑
            if (Date.now() - lastLocalEditRef.current < STORE_WRITE_DEBOUNCE_MS + 50) {
                setIsReady(true);
                return;
            }

            // 对没有 uuid 或 uuid 与 id 冲突的旧数据进行迁移
            const initialModels = (provider.models || []).map((m) =>
                (m.uuid && m.uuid !== m.id) ? m : { ...m, uuid: m.id + '-' + Math.random().toString(36).substr(2, 9) },
            );

            // Auto-save if migration occurred
            let needsMigration = false;
            for (let i = 0; i < initialModels.length; i++) {
                if (initialModels[i].uuid !== (provider.models || [])[i]?.uuid) {
                    needsMigration = true;
                    break;
                }
            }
            if (needsMigration) {
                console.log('[ProviderModels] Auto-migrating model UUIDs for uniqueness');
                writeToStore(initialModels);
            }

            setModels(initialModels);
            setIsReady(true);
        } else {
            setIsReady(false);
            setModels([]);
        }
    }, [provider, writeToStore]);

    // [FIX D4] 测试连通性，添加已取消标志防止过期结果覆盖
    const handleTestModel = useCallback(
        async (model: ModelConfig) => {
            if (!provider) return;

            Keyboard.dismiss();

            const testId = model.uuid;
            setTestResults((prev) => ({
                ...prev,
                [testId]: { loading: true },
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
                    temperature: 0.7,
                };

                const client = createLlmClient(fullConfig as any);

                let result;
                if (model.type === 'rerank' && client.testRerankConnection) {
                    result = await client.testRerankConnection();
                } else {
                    result = await client.testConnection();
                }

                setTestResults((prev) => ({
                    ...prev,
                    [testId]: {
                        loading: false,
                        success: result.success,
                        latency: result.latency,
                        error: result.error,
                    },
                }));

                if (result.success) {
                    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                } else {
                    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
                }
            } catch (e) {
                setTestResults((prev) => ({
                    ...prev,
                    [testId]: { loading: false, success: false, error: (e as Error).message },
                }));
                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
            }
        },
        [provider],
    );

    // [FIX P1+D3] 防抖写入 + [FIX 崩溃] Keyboard.dismiss + rAF 延迟布局变更
    const handleToggleModel = useCallback(
        (uuid: string, enabled: boolean) => {
            Keyboard.dismiss();
            requestAnimationFrame(() => {
                setModels((prev) => {
                    const next = prev.map((m) => (m.uuid === uuid ? { ...m, enabled } : m));
                    scheduleStoreWrite(next);
                    return next;
                });
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            });
        },
        [scheduleStoreWrite],
    );

    const handleDeleteModel = useCallback(
        (uuid: string) => {
            Keyboard.dismiss();
            requestAnimationFrame(() => {
                setModels((prev) => {
                    const next = prev.filter((m) => m.uuid !== uuid);
                    writeToStore(next);
                    return next;
                });
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            });
        },
        [writeToStore],
    );

    const handleUpdateModel = useCallback(
        (uuid: string, updates: Partial<ModelConfig>) => {
            Keyboard.dismiss();
            requestAnimationFrame(() => {
                setModels((prev) => {
                    const next = prev.map((m) => (m.uuid === uuid ? { ...m, ...updates } : m));
                    scheduleStoreWrite(next);
                    return next;
                });
            });
        },
        [scheduleStoreWrite],
    );

    const handleAutoFetch = useCallback(async () => {
        if (!provider?.apiKey) {
            showToast(t.settings.modelSettings.fetchError, 'error');
            return;
        }

        Keyboard.dismiss();
        setIsFetching(true);
        try {
            const fetchedModels = await ModelService.fetchModels(
                provider.type,
                provider.apiKey,
                provider.baseUrl
            );

            if (!fetchedModels || fetchedModels.length === 0) {
                showToast(t.settings.modelSettings.noModelsFound, 'info');
                return;
            }

            setModels((prev) => {
                const updatedModels = [...prev];
                fetchedModels.forEach((nm) => {
                    const existingIndex = updatedModels.findIndex((m) => m.id === nm.id);
                    if (existingIndex > -1) {
                        const existing = updatedModels[existingIndex];
                        if (existing.isAutoFetched) {
                            updatedModels[existingIndex] = {
                                ...existing,
                                contextLength: nm.contextLength || existing.contextLength,
                                type: existing.type || nm.type,
                                capabilities: {
                                    ...existing.capabilities,
                                    vision: existing.capabilities.vision || nm.capabilities.vision,
                                    internet: existing.capabilities.internet || nm.capabilities.internet,
                                    reasoning: existing.capabilities.reasoning || nm.capabilities.reasoning,
                                },
                            };
                        }
                    } else {
                        updatedModels.push(nm);
                    }
                });
                writeToStore(updatedModels);
                return updatedModels;
            });
            showToast(
                t.settings.modelSettings.fetchSuccess.replace('{count}', fetchedModels.length.toString()),
                'success',
            );
        } catch (error: any) {
            console.error('Fetch models failed:', error);
            showToast(`${t.settings.modelSettings.fetchError}: ${error.message}`, 'error');
        } finally {
            setIsFetching(false);
        }
    }, [provider, writeToStore, showToast, t]);

    const handleManualAdd = useCallback(() => {
        Keyboard.dismiss();
        requestAnimationFrame(() => {
            const id = `model-${Date.now()}`;
            const newModel: ModelConfig = {
                uuid: id + '-' + Math.random().toString(36).substr(2, 9),
                id: id,
                name: t.settings.modelSettings.newModel,
                enabled: true,
                isAutoFetched: false,
                capabilities: {},
            };
            setModels((prev) => {
                const next = [newModel, ...prev];
                writeToStore(next);
                return next;
            });
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        });
    }, [writeToStore, t]);

    const handleDisableAll = useCallback(() => {
        setConfirmState({
            visible: true,
            title: t.settings.modelSettings.disableAll,
            message: t.settings.modelSettings.disableAllConfirm,
            isDestructive: false,
            onConfirm: () => {
                Keyboard.dismiss();
                requestAnimationFrame(() => {
                    setModels((prev) => {
                        const next = prev.map((m) => ({ ...m, enabled: false }));
                        writeToStore(next);
                        return next;
                    });
                    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                    showToast(t.settings.modelSettings.disableAllSuccess, 'info');
                    setConfirmState((prev) => ({ ...prev, visible: false }));
                });
            },
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }, [writeToStore, showToast, t]);

    const handleDeleteAll = useCallback(() => {
        setConfirmState({
            visible: true,
            title: t.settings.modelSettings.deleteAll,
            message: t.settings.modelSettings.deleteAllConfirm,
            isDestructive: true,
            onConfirm: () => {
                Keyboard.dismiss();
                requestAnimationFrame(() => {
                    setModels([]);
                    writeToStore([]);
                    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                    showToast(t.settings.modelSettings.deleteAllSuccess, 'info');
                    setConfirmState((prev) => ({ ...prev, visible: false }));
                });
            },
        });
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    }, [writeToStore, showToast, t]);

    const filteredModels = useMemo(() => {
        if (!searchQuery) return models;
        const query = searchQuery.toLowerCase();
        return models.filter(
            (m) => m.name.toLowerCase().includes(query) || m.id.toLowerCase().includes(query),
        );
    }, [models, searchQuery]);

    // [FIX I5+P2] 使用 ref 访问 testResults，从依赖中移除以稳定 renderItem 引用
    const renderItem = useCallback(
        ({ item }: { item: ModelConfig }) => (
            <ModelItem
                model={item}
                onToggle={handleToggleModel}
                onDelete={handleDeleteModel}
                onUpdate={handleUpdateModel}
                onTest={handleTestModel}
                testStatus={testResultsRef.current[item.uuid]}
            />
        ),
        [
            handleToggleModel,
            handleDeleteModel,
            handleUpdateModel,
            handleTestModel,
        ],
    );


    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />
            <GlassHeader
                title={provider?.name || ''}
                subtitle={t.settings.modelSettings.title}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                }}
                intensity={100}
            />
            <KeyboardAvoidingView
                style={{ flex: 1 }}
                behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
                keyboardVerticalOffset={Platform.OS === 'ios' ? 88 : 0}
            >
                {/* Fixed Header Section (Search & Actions) */}
                <View style={{
                    paddingTop: insets.top + 64 + 16,
                    paddingHorizontal: 16,
                    paddingBottom: 12,
                    borderBottomWidth: 1,
                    borderBottomColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)',
                    backgroundColor: isDark ? '#000' : '#fff',
                    zIndex: 10,
                }}>
                    <AnimatedSearchBar
                        value={searchQuery}
                        onChangeText={setSearchQuery}
                        placeholder={t.settings.modelSettings.searchPlaceholder}
                        containerStyle={{ marginBottom: 12 }}
                        inputRef={inputRef}
                    />

                    <View style={{ flexDirection: 'row', gap: 8 }}>
                        {/* Auto Fetch */}
                        <TouchableOpacity
                            onPress={handleAutoFetch}
                            disabled={isFetching}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : '#f3f4f6',
                                paddingVertical: 10,
                                borderRadius: 12,
                                borderWidth: 1,
                                borderColor: isDark ? 'rgba(255, 255, 255, 0.08)' : '#e5e7eb',
                                gap: 4,
                            }}
                        >
                            {isFetching ? (
                                <ActivityIndicator size="small" color={colors[500]} />
                            ) : (
                                <RefreshCw size={16} color={colors[500]} />
                            )}
                            <Text style={{ fontWeight: 'bold', color: isDark ? '#fff' : '#111', fontSize: 12 }}>
                                {t.settings.modelSettings.fetch || '拉取'}
                            </Text>
                        </TouchableOpacity>

                        {/* Manual Add */}
                        <TouchableOpacity
                            onPress={handleManualAdd}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : '#f3f4f6',
                                paddingVertical: 10,
                                borderRadius: 12,
                                borderWidth: 1,
                                borderColor: isDark ? 'rgba(255, 255, 255, 0.08)' : '#e5e7eb',
                                gap: 4,
                            }}
                        >
                            <Plus size={16} color="#10b981" />
                            <Text style={{ fontWeight: 'bold', color: isDark ? '#fff' : '#111', fontSize: 12 }}>
                                {t.settings.modelSettings.add || '添加'}
                            </Text>
                        </TouchableOpacity>

                        {/* Disable All */}
                        <TouchableOpacity
                            onPress={handleDisableAll}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: isDark ? 'rgba(239, 68, 68, 0.08)' : '#fef2f2',
                                paddingVertical: 10,
                                borderRadius: 12,
                                borderWidth: 1,
                                borderColor: isDark ? 'rgba(239, 68, 68, 0.2)' : '#fecaca',
                                gap: 4,
                            }}
                        >
                            <X size={16} color="#ef4444" />
                            <Text style={{ fontWeight: 'bold', color: '#ef4444', fontSize: 12 }}>
                                {t.settings.modelSettings.close || '关闭'}
                            </Text>
                        </TouchableOpacity>

                        {/* Delete All */}
                        <TouchableOpacity
                            onPress={handleDeleteAll}
                            style={{
                                flex: 1,
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'center',
                                backgroundColor: isDark ? 'rgba(239, 68, 68, 0.08)' : '#fef2f2',
                                paddingVertical: 10,
                                borderRadius: 12,
                                borderWidth: 1,
                                borderColor: isDark ? 'rgba(239, 68, 68, 0.2)' : '#fecaca',
                                gap: 4,
                            }}
                        >
                            <Trash2 size={16} color="#dc2626" />
                            <Text style={{ fontWeight: 'bold', color: '#dc2626', fontSize: 12 }}>
                                {t.settings.modelSettings.deleteAll || '删除'}
                            </Text>
                        </TouchableOpacity>
                    </View>
                </View>

                {isReady && provider ? (
                    <TypedFlashList
                        data={filteredModels}
                        renderItem={renderItem}
                        estimatedItemSize={195}
                        removeClippedSubviews={false}
                        keyExtractor={(item: ModelConfig) => item.uuid}
                        contentContainerStyle={{
                            paddingBottom: 450,
                            paddingTop: 16,
                        }}
                        extraData={testResults}
                        getItemType={() => 'model'}
                        keyboardDismissMode="on-drag"
                        keyboardShouldPersistTaps="handled"
                        onScrollBeginDrag={() => {
                            Keyboard.dismiss();
                            inputRef.current?.blur();
                        }}
                    />
                ) : (
                    <View
                        style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingTop: 100 }}
                    >
                        <ActivityIndicator size="large" color={colors[500]} />
                    </View>
                )}

                <ConfirmDialog
                    visible={confirmState.visible}
                    title={confirmState.title}
                    message={confirmState.message}
                    isDestructive={confirmState.isDestructive}
                    onConfirm={confirmState.onConfirm}
                    onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
                />
            </KeyboardAvoidingView>
        </PageLayout>
    );
}

// [FIX D5+D6] TypeButton 添加交互冷却防抖
const TypeButton = React.memo(function TypeButton({
    label,
    active,
    onPress,
}: {
    label: string;
    active: boolean;
    onPress: () => void;
}) {
    const { isDark, colors } = useTheme();
    const progress = useSharedValue(active ? 1 : 0);
    const lastPressRef = useRef(0);

    const activeColor = colors[500];
    const inactiveColor = isDark ? 'rgb(24, 24, 27)' : 'rgb(243, 244, 246)';
    const inactiveBorder = isDark ? 'rgb(63, 63, 70)' : 'rgb(229, 231, 235)';

    useEffect(() => {
        progress.value = withTiming(active ? 1 : 0, { duration: 150 });
    }, [active]);

    const animatedStyle = useAnimatedStyle(() => {
        return {
            backgroundColor: interpolateColor(progress.value, [0, 1], [inactiveColor, activeColor]),
            borderColor: interpolateColor(progress.value, [0, 1], [inactiveBorder, activeColor]),
            borderWidth: 1.5,
        };
    });

    const handlePress = useCallback(() => {
        const now = Date.now();
        if (now - lastPressRef.current < INTERACTION_COOLDOWN_MS) return;
        lastPressRef.current = now;
        onPress();
    }, [onPress]);

    return (
        <TouchableOpacity onPress={handlePress} activeOpacity={0.7}>
            <Animated.View style={[{ paddingHorizontal: 8, paddingVertical: 3, borderRadius: 7 }, animatedStyle]}>
                <Typography
                    style={{
                        fontSize: 9,
                        fontWeight: 'bold',
                        color: active ? '#fff' : isDark ? '#9ca3af' : '#6b7280',
                    }}
                >
                    {label}
                </Typography>
            </Animated.View>
        </TouchableOpacity>
    );
});

// [FIX D6] CapabilityTag 添加交互冷却防抖
const CapabilityTag = React.memo(function CapabilityTag({
    icon,
    label,
    active,
    onToggle,
}: {
    icon: React.ReactNode;
    label: string;
    active?: boolean;
    onToggle: () => void;
}) {
    const { isDark, colors } = useTheme();
    const progress = useSharedValue(active ? 1 : 0);
    const lastPressRef = useRef(0);

    const activeColor = colors[500];
    const inactiveBg = isDark ? 'rgb(24, 24, 27)' : 'rgb(243, 244, 246)';
    const inactiveBorder = isDark ? 'rgb(63, 63, 70)' : 'rgb(229, 231, 235)';

    useEffect(() => {
        progress.value = withTiming(active ? 1 : 0, { duration: 150 });
    }, [active]);

    const animatedStyle = useAnimatedStyle(() => {
        return {
            backgroundColor: interpolateColor(progress.value, [0, 1], [inactiveBg, activeColor]),
            borderColor: interpolateColor(progress.value, [0, 1], [inactiveBorder, activeColor]),
            borderWidth: 1.5,
        };
    });

    const handleToggle = useCallback(() => {
        const now = Date.now();
        if (now - lastPressRef.current < INTERACTION_COOLDOWN_MS) return;
        lastPressRef.current = now;
        onToggle();
    }, [onToggle]);

    return (
        <TouchableOpacity
            onPress={handleToggle}
            activeOpacity={0.7}
        >
            <Animated.View
                style={[{
                    flexDirection: 'row',
                    alignItems: 'center',
                    paddingHorizontal: 6,
                    paddingVertical: 3,
                    borderRadius: 6,
                    gap: 3,
                }, animatedStyle]}
            >
                <View style={{ opacity: active ? 1 : 0.5 }}>
                    {React.isValidElement(icon) &&
                        React.cloneElement(icon as React.ReactElement<any>, {
                            color: active ? '#fff' : isDark ? '#9ca3af' : '#6b7280',
                        })}
                </View>
                <Typography
                    style={{
                        fontSize: 9,
                        fontWeight: 'bold',
                        color: active ? '#fff' : isDark ? '#9ca3af' : '#6b7280',
                    }}
                >
                    {label}
                </Typography>
            </Animated.View>
        </TouchableOpacity>
    );
});

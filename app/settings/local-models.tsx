import React, { useEffect, useState, useCallback } from 'react';
import { View, Text, ScrollView, TouchableOpacity, Alert } from 'react-native';
import { Stack, useRouter } from 'expo-router';
import { PageLayout, Switch, GlassHeader, ConfirmDialog } from '../../src/components/ui';
import { SettingsSection } from '../../src/features/settings/components/SettingsSection';
import { SettingsItem } from '../../src/features/settings/components/SettingsItem';
import { useSettingsStore } from '../../src/store/settings-store';
import { useApiStore, ProviderConfig, ModelConfig } from '../../src/store/api-store';
import { useLocalModelStore } from '../../src/lib/local-inference/LocalModelServer';
import { ModelStorageManager, LocalModelFile } from '../../src/lib/local-inference/ModelStorageManager';
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';
import * as Haptics from '../../src/lib/haptics';
import { Box, FileDown, Trash2, Cpu, HardDrive, ChevronLeft, BrainCircuit } from 'lucide-react-native';
import { Card } from '../../src/components/ui/Card';
import { Zap } from 'lucide-react-native';

const HardwareBadge = ({ info }: { info: any }) => {
    if (!info) return <Text style={{ color: '#9ca3af', fontSize: 12 }}>Unknown</Text>;

    if (info.gpu) {
        return (
            <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(139, 92, 246, 0.1)', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4 }}>
                <Zap size={10} color="#8b5cf6" style={{ marginRight: 4 }} />
                <Text style={{ color: '#8b5cf6', fontSize: 10, fontWeight: '700' }}>
                    GPU/NPU ACCELERATED
                </Text>
            </View>
        );
    }

    return (
        <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(245, 158, 11, 0.1)', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4 }}>
            <Cpu size={10} color="#f59e0b" style={{ marginRight: 4 }} />
            <Text style={{ color: '#f59e0b', fontSize: 10, fontWeight: '600' }}>
                CPU ({info.reasonNoGPU ? info.reasonNoGPU.substring(0, 15) + '...' : 'Software'})
            </Text>
        </View>
    );
};

export default function LocalModelSettingsScreen() {
    const router = useRouter();
    const { t } = useI18n();
    const { colors, isDark } = useTheme();

    const { localModelsEnabled, setLocalModelsEnabled } = useSettingsStore();
    const { addProvider, providers, toggleProvider, updateProvider } = useApiStore();
    const localStore = useLocalModelStore();

    const [models, setModels] = useState<LocalModelFile[]>([]);
    const [loading, setLoading] = useState(false);
    const [deleteConfirm, setDeleteConfirm] = useState<{ visible: boolean; model: LocalModelFile | null }>({
        visible: false,
        model: null
    });

    // Load models list
    const refreshModels = useCallback(async () => {
        try {
            const list = await ModelStorageManager.listModels();
            setModels(list);
            // Sync models with provider config
            syncProviderModels(list);
        } catch (e) {
            console.error('Failed to list models', e);
        }
    }, []);

    useEffect(() => {
        refreshModels();
    }, [refreshModels]);

    // Sync available files to the Provider's model list
    const syncProviderModels = (files: LocalModelFile[]) => {
        const localProvider = providers.find(p => p.type === 'local');
        if (localProvider) {
            const newModels: ModelConfig[] = files.map(f => ({
                uuid: `local-${f.name}`,
                id: f.path, // Use full path as ID for the client
                name: f.name,
                type: 'chat', // Default to chat
                capabilities: { reason: false, vision: false, tools: true },
                enabled: true,
                isAutoFetched: true
            }));

            // Update provider if different
            if (JSON.stringify(newModels) !== JSON.stringify(localProvider.models)) {
                updateProvider(localProvider.id, { models: newModels });
            }
        }
    };

    // Toggle Logic
    const handleToggle = (enabled: boolean) => {
        setLocalModelsEnabled(enabled);
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);

        if (enabled) {
            // Ensure provider exists
            const exists = providers.find(p => p.type === 'local');
            if (!exists) {
                const newProvider: Omit<ProviderConfig, 'id'> = {
                    name: 'Local Models (GGUF)',
                    type: 'local',
                    apiKey: 'none',
                    baseUrl: 'local',
                    enabled: true,
                    models: [], // Will be populated by sync
                };
                addProvider(newProvider);
                setTimeout(refreshModels, 500); // Sync after add
            } else {
                toggleProvider(exists.id, true);
                refreshModels();
            }
        } else {
            // Disable provider but don't delete
            const exists = providers.find(p => p.type === 'local');
            if (exists) {
                toggleProvider(exists.id, false);
            }
        }
    };

    // Import Action
    const handleImport = async () => {
        try {
            setLoading(true);
            const result = await ModelStorageManager.importModel();
            if (result) {
                Alert.alert(t.settings.localModels.success, t.settings.localModels.importSuccess.replace('{name}', result.name));
                refreshModels();
            }
        } catch (e: any) {
            Alert.alert(t.settings.localModels.importFail, e.message);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = (model: LocalModelFile) => {
        setDeleteConfirm({ visible: true, model });
    };

    const confirmDelete = async () => {
        const model = deleteConfirm.model;
        if (!model) return;
        setDeleteConfirm({ visible: false, model: null });
        await ModelStorageManager.deleteModel(model.name);
        refreshModels();
        // If this was loaded, unload it
        if (localStore.main.modelPath === model.path) localStore.unloadModel('main');
        if (localStore.embedding.modelPath === model.path) localStore.unloadModel('embedding');
        if (localStore.rerank.modelPath === model.path) localStore.unloadModel('rerank');
    };

    // Load Model Action
    const handleLoad = async (model: LocalModelFile, slot: 'main' | 'embedding' | 'rerank' = 'main') => {
        const currentPath =
            slot === 'main' ? localStore.main.modelPath :
                slot === 'embedding' ? localStore.embedding.modelPath :
                    localStore.rerank.modelPath;

        const isLoaded =
            slot === 'main' ? localStore.isModelLoaded :
                slot === 'embedding' ? localStore.embedding.isLoaded :
                    localStore.rerank.isLoaded;

        if (currentPath === model.path && isLoaded) {
            // Already loaded in this slot, toggle means unload
            try {
                await localStore.unloadModel(slot);
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                return;
            } catch (e: any) {
                Alert.alert(t.settings.localModels.loadFail, e.message);
                return;
            }
        }
        try {
            await localStore.loadModel(model.path, slot);
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        } catch (e: any) {
            Alert.alert(t.settings.localModels.loadFail, e.message);
        }
    };

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />
            <GlassHeader
                title={t.settings.localModels.title}
                subtitle={t.settings.localModels.subtitle}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back()
                }}
            />

            <ScrollView contentContainerStyle={{ paddingTop: 110, paddingBottom: 100, paddingHorizontal: 16 }}>
                <SettingsSection title={t.settings.localModels.configuration}>
                    <SettingsItem
                        icon={Cpu}
                        title={t.settings.localModels.enableTitle}
                        subtitle={t.settings.localModels.enableDesc}
                        rightElement={
                            <Switch value={localModelsEnabled} onValueChange={handleToggle} />
                        }
                    />
                </SettingsSection>

                {localModelsEnabled && (
                    <>
                        <SettingsSection title={t.settings.localModels.management}>
                            <TouchableOpacity
                                onPress={handleImport}
                                style={{
                                    flexDirection: 'row',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    backgroundColor: isDark ? '#27272a' : '#f3f4f6',
                                    padding: 16,
                                    borderRadius: 16,
                                    marginBottom: 16,
                                    borderWidth: 1,
                                    borderColor: isDark ? '#3f3f46' : '#e5e7eb',
                                    borderStyle: 'dashed'
                                }}
                            >
                                <FileDown size={20} color={colors[500]} style={{ marginRight: 8 }} />
                                <Text style={{ fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                                    {loading ? t.settings.localModels.importing : t.settings.localModels.import}
                                </Text>
                            </TouchableOpacity>

                            {models.length === 0 ? (
                                <Text style={{ textAlign: 'center', color: '#9ca3af', padding: 20 }}>
                                    {t.settings.localModels.noModels}
                                </Text>
                            ) : (
                                models.map(model => (
                                    <Card key={model.uri} variant="glass" className="mb-3">
                                        <View className="p-4">
                                            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                                                <View style={{ flex: 1 }}>
                                                    <Text style={{ fontWeight: 'bold', fontSize: 16, color: isDark ? '#fff' : '#111' }}>
                                                        {model.name}
                                                    </Text>
                                                    <Text style={{ fontSize: 12, color: '#6b7280', marginTop: 4 }}>
                                                        {(model.size / 1024 / 1024).toFixed(2)} MB
                                                    </Text>

                                                    {/* Status Indicators */}
                                                    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
                                                        {localStore.main.modelPath === model.path && (
                                                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                                                <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: '#22c55e', marginRight: 6 }} />
                                                                <Text style={{ fontSize: 12, color: '#22c55e', fontWeight: '600' }}>
                                                                    Main ({Math.round(localStore.main.loadProgress)}%)
                                                                </Text>
                                                            </View>
                                                        )}
                                                        {localStore.embedding.modelPath === model.path && (
                                                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                                                <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: '#3b82f6', marginRight: 6 }} />
                                                                <Text style={{ fontSize: 12, color: '#3b82f6', fontWeight: '600' }}>
                                                                    Emb ({Math.round(localStore.embedding.loadProgress)}%)
                                                                </Text>
                                                            </View>
                                                        )}
                                                        {localStore.rerank.modelPath === model.path && (
                                                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                                                <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: '#8b5cf6', marginRight: 6 }} />
                                                                <Text style={{ fontSize: 12, color: '#8b5cf6', fontWeight: '600' }}>
                                                                    Rerank ({Math.round(localStore.rerank.loadProgress)}%)
                                                                </Text>
                                                            </View>
                                                        )}
                                                    </View>
                                                </View>

                                                <View style={{ flexDirection: 'row', gap: 4 }}>
                                                    {/* Load Main Button */}
                                                    <TouchableOpacity
                                                        onPress={() => handleLoad(model, 'main')}
                                                        activeOpacity={0.7}
                                                        style={{
                                                            padding: 8,
                                                            backgroundColor: localStore.main.modelPath === model.path ? (isDark ? 'rgba(34, 197, 94, 0.2)' : '#dcfce7') : 'transparent',
                                                            borderRadius: 8
                                                        }}
                                                    >
                                                        <HardDrive size={18} color={localStore.main.modelPath === model.path ? '#22c55e' : colors[500]} />
                                                    </TouchableOpacity>

                                                    {/* Load Embedding Button */}
                                                    <TouchableOpacity
                                                        onPress={() => handleLoad(model, 'embedding')}
                                                        activeOpacity={0.7}
                                                        style={{
                                                            padding: 8,
                                                            backgroundColor: localStore.embedding.modelPath === model.path ? (isDark ? 'rgba(59, 130, 246, 0.2)' : '#dbeafe') : 'transparent',
                                                            borderRadius: 8
                                                        }}
                                                    >
                                                        <Box size={18} color={localStore.embedding.modelPath === model.path ? '#3b82f6' : colors[400]} />
                                                    </TouchableOpacity>

                                                    {/* Load Rerank Button */}
                                                    <TouchableOpacity
                                                        onPress={() => handleLoad(model, 'rerank')}
                                                        activeOpacity={0.7}
                                                        style={{
                                                            padding: 8,
                                                            backgroundColor: localStore.rerank.modelPath === model.path ? (isDark ? 'rgba(139, 92, 246, 0.2)' : '#ede9fe') : 'transparent',
                                                            borderRadius: 8
                                                        }}
                                                    >
                                                        <BrainCircuit size={18} color={localStore.rerank.modelPath === model.path ? '#8b5cf6' : colors[400]} />
                                                    </TouchableOpacity>

                                                    <TouchableOpacity
                                                        onPress={() => handleDelete(model)}
                                                        style={{ padding: 8 }}
                                                    >
                                                        <Trash2 size={18} color="#ef4444" />
                                                    </TouchableOpacity>
                                                </View>
                                            </View>
                                        </View>
                                    </Card>
                                ))
                            )}
                        </SettingsSection>

                        <SettingsSection title={t.settings.localModels.status}>
                            <Card variant="glass">
                                <View className="p-4 gap-3">
                                    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: isDark ? '#3f3f46' : '#e5e7eb', paddingBottom: 8 }}>
                                        <Text style={{ color: isDark ? '#fff' : '#111', fontWeight: '600' }}>
                                            {t.settings.localModels.engine}: llama.rn (v0.10)
                                        </Text>
                                    </View>

                                    {/* Main Slot Status */}
                                    <View className="gap-1">
                                        <View className="flex-row justify-between items-center">
                                            <Text style={{ color: '#22c55e', fontWeight: 'bold', fontSize: 13 }}>Main Slot</Text>
                                            <Text style={{ color: isDark ? '#d1d5db' : '#4b5563', fontSize: 12 }}>
                                                {localStore.main.isLoaded ? 'Loaded' : 'Empty'}
                                            </Text>
                                        </View>
                                        {localStore.main.isLoaded && (
                                            <View className="flex-row justify-between items-center pl-2">
                                                <Text style={{ color: '#9ca3af', fontSize: 12 }}>Hardware:</Text>
                                                <HardwareBadge info={localStore.main.accelerationInfo} />
                                            </View>
                                        )}
                                    </View>

                                    {/* Embedding Slot Status */}
                                    <View className="gap-1">
                                        <View className="flex-row justify-between items-center">
                                            <Text style={{ color: '#3b82f6', fontWeight: 'bold', fontSize: 13 }}>Embedding Slot</Text>
                                            <Text style={{ color: isDark ? '#d1d5db' : '#4b5563', fontSize: 12 }}>
                                                {localStore.embedding.isLoaded ? 'Loaded' : 'Empty'}
                                            </Text>
                                        </View>
                                        {localStore.embedding.isLoaded && (
                                            <View className="flex-row justify-between items-center pl-2">
                                                <Text style={{ color: '#9ca3af', fontSize: 12 }}>Hardware:</Text>
                                                <HardwareBadge info={localStore.embedding.accelerationInfo} />
                                            </View>
                                        )}
                                    </View>

                                    {/* Rerank Slot Status */}
                                    <View className="gap-1">
                                        <View className="flex-row justify-between items-center">
                                            <Text style={{ color: '#8b5cf6', fontWeight: 'bold', fontSize: 13 }}>Rerank Slot</Text>
                                            <Text style={{ color: isDark ? '#d1d5db' : '#4b5563', fontSize: 12 }}>
                                                {localStore.rerank.isLoaded ? 'Loaded' : 'Empty'}
                                            </Text>
                                        </View>
                                        {localStore.rerank.isLoaded && (
                                            <View className="flex-row justify-between items-center pl-2">
                                                <Text style={{ color: '#9ca3af', fontSize: 12 }}>Hardware:</Text>
                                                <HardwareBadge info={localStore.rerank.accelerationInfo} />
                                            </View>
                                        )}
                                    </View>

                                    {localStore.error && (
                                        <Text style={{ color: '#ef4444', marginTop: 4, fontSize: 12 }}>
                                            Error: {localStore.error}
                                        </Text>
                                    )}
                                </View>
                            </Card>
                        </SettingsSection>
                    </>
                )}
            </ScrollView>

            {/* Delete Confirmation Dialog */}
            <ConfirmDialog
                visible={deleteConfirm.visible}
                title={t.settings.localModels.deleteTitle}
                message={deleteConfirm.model ? t.settings.localModels.deleteDesc.replace('{name}', deleteConfirm.model.name) : ''}
                confirmText={t.common.delete}
                cancelText={t.common.cancel}
                onConfirm={confirmDelete}
                onCancel={() => setDeleteConfirm({ visible: false, model: null })}
                isDestructive
            />
        </PageLayout>
    );
}

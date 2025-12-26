import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, Modal, TextInput, ScrollView, KeyboardAvoidingView, Platform } from 'react-native';
import { X } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { ProviderConfig, ApiProviderType } from '../../store/api-store';

interface ProviderModalProps {
    visible: boolean;
    onClose: () => void;
    onSave: (provider: Omit<ProviderConfig, 'id'>) => void;
    editingProvider?: ProviderConfig | null;
}

const PROVIDER_PRESETS: Record<string, { name: string; baseUrl: string; type: ApiProviderType }> = {
    openai: { name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', type: 'openai' },
    google: { name: 'VertexAI (Google)', baseUrl: '', type: 'google' },
    gemini: { name: 'Gemini (Google)', baseUrl: 'https://generativelanguage.googleapis.com', type: 'gemini' },
    siliconflow: { name: '硅基流动 (SiliconFlow)', baseUrl: 'https://api.siliconflow.cn/v1', type: 'siliconflow' },
    github: { name: 'GitHub Models', baseUrl: 'https://models.inference.ai.azure.com', type: 'github' },
    zhipu: { name: '智谱 (ZhiPu AI)', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', type: 'zhipu' },
    moonshot: { name: '月之暗面 (Moonshot)', baseUrl: 'https://api.moonshot.cn/v1', type: 'moonshot' },
    deepseek: { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', type: 'deepseek' },
    anthropic: { name: 'Claude (Anthropic)', baseUrl: 'https://api.anthropic.com/v1', type: 'anthropic' },
};

export function ProviderModal({ visible, onClose, onSave, editingProvider }: ProviderModalProps) {
    const { t } = useI18n();
    const { theme } = useTheme();

    const [name, setName] = useState('');
    const [type, setType] = useState<ApiProviderType>('openai');
    const [baseUrl, setBaseUrl] = useState('');
    const [apiKey, setApiKey] = useState('');
    const [region, setRegion] = useState('us-central1');
    const [vertexProject, setVertexProject] = useState('');
    const [jsonInput, setJsonInput] = useState('');
    const [errors, setErrors] = useState<{ name?: string; apiKey?: string }>({});

    useEffect(() => {
        if (editingProvider) {
            setName(editingProvider.name);
            setType(editingProvider.type);
            setBaseUrl(editingProvider.baseUrl || '');
            setApiKey(editingProvider.apiKey);
            setRegion(editingProvider.vertexLocation || 'us-central1');
            setVertexProject(editingProvider.vertexProject || '');
            setJsonInput(editingProvider.vertexKeyJson || '');
        } else {
            setName('');
            setType('openai');
            setBaseUrl(PROVIDER_PRESETS.openai.baseUrl);
            setApiKey('');
            setRegion('us-central1');
            setVertexProject('');
            setJsonInput('');
        }
        setErrors({});
    }, [editingProvider, visible]);

    // 当地区改变时，自动更新 VertexAI 的 Base URL
    useEffect(() => {
        if (type === 'google' && !editingProvider) {
            setBaseUrl(`https://${region}-aiplatform.googleapis.com/v1`);
        }
    }, [region, type, editingProvider]);

    const handlePresetSelect = (presetKey: string) => {
        const preset = PROVIDER_PRESETS[presetKey];
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            setName(preset.name);
            setType(preset.type);
            if (preset.type === 'google') {
                setRegion('us-central1');
                setBaseUrl(`https://us-central1-aiplatform.googleapis.com/v1`);
            } else if (preset.type === 'gemini') {
                setBaseUrl(preset.baseUrl);
            } else {
                setBaseUrl(preset.baseUrl);
            }
        }, 10);
    };

    const handleSave = () => {
        const newErrors: { name?: string; apiKey?: string } = {};

        if (!name.trim()) {
            newErrors.name = t.settings.providerModal.nameRequired;
        }
        // VertexAI 下，由于使用 JSON 密钥，API Key 可以是占位符或特定令牌
        if (!apiKey.trim() && type !== 'google') {
            newErrors.apiKey = t.settings.providerModal.apiKeyRequired;
        }

        if (Object.keys(newErrors).length > 0) {
            setErrors(newErrors);
            return;
        }

        setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
            onSave({
                name: name.trim(),
                type,
                baseUrl: baseUrl.trim() || undefined,
                apiKey: apiKey.trim() || (type === 'google' ? 'vertex-ready' : ''),
                enabled: true,
                models: editingProvider?.models || [],
                vertexProject: type === 'google' ? vertexProject : undefined,
                vertexLocation: type === 'google' ? region : undefined,
                vertexKeyJson: type === 'google' ? jsonInput : undefined,
            });
            onClose();
        }, 10);
    };

    const handleCancel = () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onClose();
        }, 10);
    };

    return (
        <Modal
            visible={visible}
            animationType="slide"
            transparent={false}
            onRequestClose={handleCancel}
        >
            <KeyboardAvoidingView
                style={{ flex: 1, backgroundColor: theme === 'dark' ? '#000' : '#fff' }}
                behavior={Platform.OS === 'ios' ? 'padding' : undefined}
            >
                {/* Header */}
                <View style={{ paddingTop: 60, paddingHorizontal: 24, paddingBottom: 16, borderBottomWidth: 1, borderBottomColor: theme === 'dark' ? '#27272a' : '#e5e7eb' }}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                        <Text style={{ fontSize: 24, fontWeight: '900', color: theme === 'dark' ? '#fff' : '#111' }}>
                            {editingProvider ? t.settings.providerModal.editTitle : t.settings.providerModal.addTitle}
                        </Text>
                        <TouchableOpacity onPress={handleCancel} style={{ padding: 8 }}>
                            <X size={24} color={theme === 'dark' ? '#9ca3af' : '#6b7280'} />
                        </TouchableOpacity>
                    </View>
                </View>

                <ScrollView style={{ flex: 1, paddingHorizontal: 24, paddingTop: 24 }} showsVerticalScrollIndicator={false}>
                    {/* 快速配置 (仅添加模式) */}
                    {!editingProvider && (
                        <View style={{ marginBottom: 24 }}>
                            <Text style={{ fontSize: 14, fontWeight: 'bold', color: '#9ca3af', marginBottom: 12 }}>
                                {t.settings.providerModal.presets}
                            </Text>
                            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                                {Object.entries(PROVIDER_PRESETS).map(([key, preset]) => (
                                    <TouchableOpacity
                                        key={key}
                                        onPress={() => handlePresetSelect(key)}
                                        style={{
                                            paddingHorizontal: 16,
                                            paddingVertical: 10,
                                            backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                            borderRadius: 12,
                                            borderWidth: 1,
                                            borderColor: type === preset.type ? '#6366f1' : (theme === 'dark' ? '#27272a' : '#e5e7eb'),
                                        }}
                                    >
                                        <Text style={{ fontSize: 14, fontWeight: '600', color: type === preset.type ? '#6366f1' : (theme === 'dark' ? '#fff' : '#111') }}>
                                            {preset.name}
                                        </Text>
                                    </TouchableOpacity>
                                ))}
                            </View>
                        </View>
                    )}

                    {/* 表单字段 */}
                    <View style={{ gap: 20 }}>
                        {/* 名称 */}
                        <View>
                            <Text style={{ fontSize: 14, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111', marginBottom: 8 }}>
                                {t.settings.providerModal.name}
                            </Text>
                            <TextInput
                                value={name}
                                onChangeText={setName}
                                placeholder={t.settings.providerModal.namePlaceholder}
                                placeholderTextColor="#9ca3af"
                                style={{
                                    backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                    borderWidth: 1,
                                    borderColor: errors.name ? '#ef4444' : (theme === 'dark' ? '#27272a' : '#e5e7eb'),
                                    borderRadius: 12,
                                    paddingHorizontal: 16,
                                    paddingVertical: 12,
                                    fontSize: 16,
                                    color: theme === 'dark' ? '#fff' : '#111',
                                }}
                            />
                            {errors.name && (
                                <Text style={{ color: '#ef4444', fontSize: 12, marginTop: 4 }}>{errors.name}</Text>
                            )}
                        </View>

                        {/* VertexAI 特有: 地区选框 */}
                        {type === 'google' && (
                            <View>
                                <Text style={{ fontSize: 14, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111', marginBottom: 8 }}>
                                    {t.settings.providerModal.region}
                                </Text>
                                <TextInput
                                    value={region}
                                    onChangeText={setRegion}
                                    placeholder={t.settings.providerModal.regionPlaceholder}
                                    placeholderTextColor="#9ca3af"
                                    autoCapitalize="none"
                                    style={{
                                        backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                        borderWidth: 1,
                                        borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb',
                                        borderRadius: 12,
                                        paddingHorizontal: 16,
                                        paddingVertical: 12,
                                        fontSize: 16,
                                        color: theme === 'dark' ? '#fff' : '#111',
                                    }}
                                />
                            </View>
                        )}

                        {/* Base URL */}
                        <View>
                            <Text style={{ fontSize: 14, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111', marginBottom: 8 }}>
                                {t.settings.providerModal.baseUrl}
                            </Text>
                            <TextInput
                                value={baseUrl}
                                onChangeText={setBaseUrl}
                                placeholder={t.settings.providerModal.baseUrlPlaceholder}
                                placeholderTextColor="#9ca3af"
                                autoCapitalize="none"
                                keyboardType="url"
                                style={{
                                    backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                    borderWidth: 1,
                                    borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb',
                                    borderRadius: 12,
                                    paddingHorizontal: 16,
                                    paddingVertical: 12,
                                    fontSize: 16,
                                    color: theme === 'dark' ? '#fff' : '#111',
                                    opacity: type === 'google' ? 0.6 : 1
                                }}
                                editable={type !== 'google'} // VertexAI 下通常由地区自动生成
                            />
                        </View>

                        {/* API Key (对于 VertexAI 隐藏或显示为非必填) */}
                        <View style={{ display: type === 'google' ? 'none' : 'flex' }}>
                            <Text style={{ fontSize: 14, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111', marginBottom: 8 }}>
                                {t.settings.providerModal.apiKey}
                            </Text>
                            <TextInput
                                value={apiKey}
                                onChangeText={setApiKey}
                                placeholder={t.settings.providerModal.apiKeyPlaceholder}
                                placeholderTextColor="#9ca3af"
                                autoCapitalize="none"
                                secureTextEntry
                                style={{
                                    backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                    borderWidth: 1,
                                    borderColor: errors.apiKey ? '#ef4444' : (theme === 'dark' ? '#27272a' : '#e5e7eb'),
                                    borderRadius: 12,
                                    paddingHorizontal: 16,
                                    paddingVertical: 12,
                                    fontSize: 16,
                                    color: theme === 'dark' ? '#fff' : '#111',
                                    fontFamily: 'monospace',
                                }}
                            />
                            {errors.apiKey && (
                                <Text style={{ color: '#ef4444', fontSize: 12, marginTop: 4 }}>{errors.apiKey}</Text>
                            )}
                        </View>

                        {/* VertexAI JSON Import */}
                        {type === 'google' && (
                            <View>
                                <Text style={{ fontSize: 14, fontWeight: '600', color: theme === 'dark' ? '#fff' : '#111', marginBottom: 8 }}>
                                    {t.settings.providerModal.importVertexJson}
                                </Text>
                                <TextInput
                                    value={jsonInput}
                                    multiline
                                    numberOfLines={6}
                                    placeholder={t.settings.providerModal.importPlaceholder}
                                    placeholderTextColor="#9ca3af"
                                    onChangeText={(v) => {
                                        setJsonInput(v);
                                        try {
                                            const json = JSON.parse(v);
                                            if (json.project_id) {
                                                if (!name) setName(`VertexAI - ${json.project_id}`);
                                                setVertexProject(json.project_id);
                                                // 自动从 JSON 中恢复私钥到 apiKey 字段作为通用存储，
                                                // 但 UI 上优先显示 JSON 区域
                                                setApiKey(json.private_key || '');
                                            }
                                        } catch (e) {
                                            // 忽略无效 JSON
                                        }
                                    }}
                                    style={{
                                        backgroundColor: theme === 'dark' ? '#18181b' : '#f9fafb',
                                        borderWidth: 1,
                                        borderColor: theme === 'dark' ? '#27272a' : '#e5e7eb',
                                        borderRadius: 12,
                                        paddingHorizontal: 16,
                                        paddingVertical: 12,
                                        fontSize: 10,
                                        color: theme === 'dark' ? '#fff' : '#111',
                                        height: 120,
                                        textAlignVertical: 'top',
                                        fontFamily: 'monospace'
                                    }}
                                />
                            </View>
                        )}
                    </View>

                    <View style={{ height: 40 }} />
                </ScrollView>

                {/* Footer Buttons */}
                <View style={{ paddingHorizontal: 24, paddingVertical: 16, borderTopWidth: 1, borderTopColor: theme === 'dark' ? '#27272a' : '#e5e7eb', gap: 12 }}>
                    <TouchableOpacity
                        onPress={handleSave}
                        style={{
                            backgroundColor: '#6366f1',
                            borderRadius: 12,
                            paddingVertical: 14,
                            alignItems: 'center',
                        }}
                    >
                        <Text style={{ color: '#fff', fontSize: 16, fontWeight: '600' }}>
                            {t.settings.providerModal.save}
                        </Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                        onPress={handleCancel}
                        style={{
                            backgroundColor: theme === 'dark' ? '#27272a' : '#f3f4f6',
                            borderRadius: 12,
                            paddingVertical: 14,
                            alignItems: 'center',
                        }}
                    >
                        <Text style={{ color: theme === 'dark' ? '#fff' : '#111', fontSize: 16, fontWeight: '600' }}>
                            {t.settings.providerModal.cancel}
                        </Text>
                    </TouchableOpacity>
                </View>
            </KeyboardAvoidingView>
        </Modal>
    );
}

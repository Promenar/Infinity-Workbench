import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
    View,
    Text,
    TextInput,
    ScrollView,
    KeyboardAvoidingView,
    Platform,
    StyleSheet,
    TouchableOpacity,
    ActivityIndicator,
} from 'react-native';
import Animated, { FadeIn, FadeInDown, useSharedValue, useAnimatedStyle, withSpring, withTiming, interpolateColor } from 'react-native-reanimated';
import { ChevronDown, ChevronRight, ChevronLeft } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useI18n } from '../../../lib/i18n';
import { useTheme } from '../../../theme/ThemeProvider';
import { GlassHeader } from '../../../components/ui/GlassHeader';
import { PageLayout } from '../../../components/ui/PageLayout';
import { ProviderConfig, ApiProviderType, useApiStore } from '../../../store/api-store';
import { ParsedInput } from '../components/ParsedInput';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { BrandIcon } from '../../../components/icons/BrandIcons';
import { ModelIconRenderer } from '../../../components/icons/ModelIconRenderer';
import { SettingsItem } from '../components/SettingsItem';

const PROVIDER_PRESETS: Record<string, { name: string; baseUrl: string; type: ApiProviderType }> = {
    // 国际主流
    openai: { name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', type: 'openai' },
    anthropic: {
        name: 'Claude (Anthropic)',
        baseUrl: 'https://api.anthropic.com/v1',
        type: 'anthropic',
    },
    gemini: {
        name: 'Gemini (Google)',
        baseUrl: 'https://generativelanguage.googleapis.com',
        type: 'gemini',
    },
    google: { name: 'VertexAI (Google)', baseUrl: '', type: 'google' },

    // 中国主流服务商
    zhipu: {
        name: '智谱 (ZhiPu AI)',
        baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
        type: 'zhipu',
    },
    moonshot: { name: 'Kimi (Moonshot)', baseUrl: 'https://api.moonshot.cn/v1', type: 'moonshot' },
    baichuan: { name: '百川 (Baichuan)', baseUrl: 'https://api.baichuan-ai.com/v1', type: 'openai' },
    qwen: {
        name: '通义千问 (Qwen)',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        type: 'openai',
    },
    ernie: {
        name: '文心一言 (ERNIE)',
        baseUrl: 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1',
        type: 'openai',
    },
    doubao: {
        name: '豆包 (Doubao)',
        baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
        type: 'openai',
    },
    yi: { name: '零一万物 (Yi)', baseUrl: 'https://api.lingyiwanwu.com/v1', type: 'openai' },
    minimax: { name: 'MiniMax', baseUrl: 'https://api.minimax.chat/v1', type: 'openai' },

    // 开发者友好
    deepseek: { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', type: 'deepseek' },
    github: {
        name: 'GitHub Models',
        baseUrl: 'https://models.inference.ai.azure.com',
        type: 'github',
    },
    siliconflow: {
        name: '硅基流动 (SiliconFlow)',
        baseUrl: 'https://api.siliconflow.cn/v1',
        type: 'siliconflow',
    },
    groq: { name: 'Groq', baseUrl: 'https://api.groq.com/openai/v1', type: 'openai' },
    ollama: {
        name: 'Ollama',
        baseUrl: 'http://localhost:11434/v1',
        type: 'openai',
    },
    'openai-compatible': { name: 'OpenAI Compatible (NewAPI/OneAPI)', baseUrl: '', type: 'openai-compatible' },
};

export default function ProviderFormScreen() {
    const router = useRouter();
    const params = useLocalSearchParams();
    const providerId = params.id as string;

    const { t } = useI18n();
    const { isDark, colors } = useTheme();
    const insets = useSafeAreaInsets();

    const { providers, addProvider, updateProvider } = useApiStore();
    const editingProvider = useMemo(() =>
        providers.find(p => p.id === providerId),
        [providers, providerId]);

    const [name, setName] = useState('');
    const [type, setType] = useState<ApiProviderType>('openai');
    const [baseUrl, setBaseUrl] = useState('');
    const [apiKey, setApiKey] = useState('');
    const [region, setRegion] = useState('us-central1');
    const [vertexProject, setVertexProject] = useState('');
    const [jsonInput, setJsonInput] = useState('');
    const [selectedPreset, setSelectedPreset] = useState<string>('');
    const [errors, setErrors] = useState<{
        name?: string;
        apiKey?: string;
        vertexProject?: string;
        jsonInput?: string;
    }>({});

    const [isSaving, setIsSaving] = useState(false);
    const saveScale = useSharedValue(1);
    const nameFocusProgress = useSharedValue(0);
    const apiKeyFocusProgress = useSharedValue(0);
    const baseUrlFocusProgress = useSharedValue(0);

    // Reset state on load
    useEffect(() => {
        if (editingProvider) {
            setName(editingProvider.name);
            setType(editingProvider.type);
            setBaseUrl(editingProvider.baseUrl || '');
            setApiKey(editingProvider.apiKey);
            setRegion(editingProvider.vertexLocation || 'us-central1');
            setVertexProject(editingProvider.vertexProject || '');
            setJsonInput(editingProvider.vertexKeyJson || '');

            // Try to match preset
            const presetEntry = Object.entries(PROVIDER_PRESETS).find(([_, p]) => p.type === editingProvider.type && p.baseUrl === editingProvider.baseUrl);
            if (presetEntry) {
                setSelectedPreset(presetEntry[0]);
            }
        } else {
            // Default new state
            setName('');
            setType('openai');
            setBaseUrl(PROVIDER_PRESETS.openai.baseUrl);
            setApiKey('');
            setRegion('us-central1');
            setVertexProject('');
            setJsonInput('');
            setSelectedPreset('');
        }
        setErrors({});
    }, [editingProvider]);

    // Update BaseURL for Google
    useEffect(() => {
        if (type === 'google' && !editingProvider) {
            setBaseUrl(`https://${region}-aiplatform.googleapis.com/v1`);
        }
    }, [region, type, editingProvider]);

    const handleJSONParsed = useCallback((json: any) => {
        // Fill in fields if detected
        if (json.project_id) {
            if (!vertexProject) setVertexProject(json.project_id);
            if (!name) setName(`VertexAI - ${json.project_id}`);
        }
    }, [vertexProject, name]);

    const handleJSONError = useCallback((err: string | null) => {
        if (err) {
            setErrors(prev => ({ ...prev, jsonInput: err }));
        } else {
            setErrors(prev => ({ ...prev, jsonInput: undefined }));
        }
    }, []);

    const handleSave = () => {
        const newErrors: typeof errors = {};

        if (!name.trim()) {
            newErrors.name = t.settings.providerModal.nameRequired;
        }

        if (type === 'google') {
            if (!vertexProject.trim()) {
                newErrors.vertexProject = 'Project ID is required';
            }
            if (!jsonInput.trim()) {
                newErrors.jsonInput = 'Service Account JSON is required';
            } else {
                try {
                    const json = JSON.parse(jsonInput);
                    if (!json.private_key || !json.client_email) {
                        newErrors.jsonInput = 'Invalid JSON: missing private_key or client_email';
                    }
                } catch (e) {
                    newErrors.jsonInput = 'Invalid JSON format';
                }
            }
        } else {
            if (!apiKey.trim()) {
                newErrors.apiKey = t.settings.providerModal.apiKeyRequired;
            }
        }

        if (Object.keys(newErrors).length > 0) {
            setErrors(newErrors);
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
            return;
        }

        setIsSaving(true);
        saveScale.value = withSpring(0.95, { damping: 15 });

        setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);

            let finalApiKey = apiKey.trim();
            if (type === 'google' && jsonInput) {
                try {
                    const json = JSON.parse(jsonInput);
                    finalApiKey = json.private_key || 'vertex-placeholder';
                } catch (e) {
                    finalApiKey = 'vertex-placeholder';
                }
            }

            const providerData = {
                name: name.trim(),
                type,
                baseUrl: baseUrl.trim() || undefined,
                apiKey: finalApiKey || (type === 'google' ? 'vertex-placeholder' : ''),
                enabled: true,
                models: editingProvider?.models || [],
                vertexProject: type === 'google' ? vertexProject.trim() : undefined,
                vertexLocation: type === 'google' ? region.trim() : undefined,
                vertexKeyJson: type === 'google' ? jsonInput.trim() : undefined,
            };

            if (editingProvider) {
                updateProvider(editingProvider.id, providerData);
            } else {
                addProvider(providerData);
            }

            saveScale.value = withSpring(1, { damping: 15 });
            router.back();
        }, 150);
    };

    const saveAnimatedStyle = useAnimatedStyle(() => ({
        transform: [{ scale: saveScale.value }],
    }));

    const nameFocusStyle = useAnimatedStyle(() => ({
        borderColor: interpolateColor(nameFocusProgress.value, [0, 1], [
            isDark ? '#27272a' : '#e5e7eb',
            colors[500],
        ]),
    }));

    const apiKeyFocusStyle = useAnimatedStyle(() => ({
        borderColor: interpolateColor(apiKeyFocusProgress.value, [0, 1], [
            isDark ? '#27272a' : '#e5e7eb',
            colors[500],
        ]),
    }));

    const baseUrlFocusStyle = useAnimatedStyle(() => ({
        borderColor: interpolateColor(baseUrlFocusProgress.value, [0, 1], [
            isDark ? '#27272a' : '#e5e7eb',
            colors[500],
        ]),
    }));

    const handlePresetSelect = useCallback((presetKey: string) => {
        const preset = PROVIDER_PRESETS[presetKey];
        if (!preset) return;
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);

        setSelectedPreset(presetKey);
        setName(preset.name);
        setType(preset.type);

        if (preset.type === 'google') {
            setRegion('us-central1');
            setBaseUrl(`https://us-central1-aiplatform.googleapis.com/v1`);
        } else {
            setBaseUrl(preset.baseUrl);
        }
    }, []);

    const inputStyle = useMemo(() => ({
        backgroundColor: isDark ? '#18181b' : '#f9fafb',
        borderColor: isDark ? '#27272a' : '#e5e7eb',
        color: isDark ? '#fff' : '#111',
    }), [isDark]);

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <GlassHeader
                title={editingProvider ? t.settings.providerModal.editTitle : t.settings.providerModal.addTitle}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                }}
            />

            <KeyboardAvoidingView
                style={{ flex: 1 }}
                behavior={Platform.OS === 'ios' ? 'padding' : undefined}
                keyboardVerticalOffset={Platform.OS === 'ios' ? 88 : 0}
            >
                <ScrollView
                    style={styles.scrollView}
                    contentContainerStyle={{ paddingTop: 100, paddingBottom: 100 }}
                    showsVerticalScrollIndicator={false}
                >
                    {/* Presets */}
                    {!editingProvider && (
                        <View style={styles.section}>
                            <Text style={styles.sectionTitle}>{t.settings.providerModal.presets}</Text>
                            <View style={styles.presetsGrid}>
                                {Object.entries(PROVIDER_PRESETS).map(([key, preset], index) => {
                                    const iconKey = key === 'openai-compatible' ? 'openai' : key;
                                    const isSelected = selectedPreset === key;

                                    return (
                                        <Animated.View
                                            key={key}
                                            entering={FadeInDown.duration(200).delay(index * 30).springify()}
                                            style={{ width: '48%' }}
                                        >
                                            <TouchableOpacity
                                                onPress={() => handlePresetSelect(key)}
                                                style={[
                                                    styles.presetCard,
                                                    {
                                                        backgroundColor: isSelected
                                                            ? (isDark ? '#27272a' : '#f3f4f6')
                                                            : (isDark ? '#18181b' : '#fff'),
                                                        borderColor: isSelected
                                                            ? colors[500]
                                                            : (isDark ? '#27272a' : '#e5e7eb')
                                                    }
                                                ]}
                                            >
                                                <View style={styles.presetIcon}>
                                                    <ModelIconRenderer
                                                        icon={iconKey as any}
                                                        size={24}
                                                        color={isSelected ? colors[500] : (isDark ? '#fff' : '#000')}
                                                    />
                                                </View>
                                                <Text
                                                    numberOfLines={1}
                                                    style={[
                                                        styles.presetName,
                                                        {
                                                            color: isSelected
                                                                ? colors[500]
                                                                : (isDark ? '#fff' : '#111'),
                                                            fontWeight: isSelected ? '600' : '400'
                                                        }
                                                    ]}
                                                >
                                                    {preset.name}
                                                </Text>
                                            </TouchableOpacity>
                                        </Animated.View>
                                    );
                                })}
                            </View>
                        </View>
                    )}

                    <View style={styles.formGap}>
                        {/* Name */}
                        <ParsedInput
                            label={t.settings.providerModal.name}
                            value={name}
                            onValueChange={setName}
                            placeholder={t.settings.providerModal.namePlaceholder}
                            error={errors.name}
                            required
                        />

                        {/* Vertex AI Fields */}
                        {type === 'google' && (
                            <>
                                <ParsedInput
                                    label="Google Cloud Project ID"
                                    value={vertexProject}
                                    onValueChange={setVertexProject}
                                    placeholder="my-project-123456"
                                    error={errors.vertexProject}
                                    required
                                />
                                <ParsedInput
                                    label={t.settings.providerModal.region}
                                    value={region}
                                    onValueChange={setRegion}
                                    placeholder={t.settings.providerModal.regionPlaceholder}
                                />
                            </>
                        )}

                        {/* Base URL */}
                        <View>
                            <Text style={[styles.label, { color: isDark ? '#fff' : '#111' }]}>
                                {t.settings.providerModal.baseUrl}
                            </Text>
                            <Animated.View style={baseUrlFocusStyle}>
                                <TextInput
                                    value={baseUrl}
                                    onChangeText={setBaseUrl}
                                    placeholder={t.settings.providerModal.baseUrlPlaceholder}
                                    placeholderTextColor="#9ca3af"
                                    autoCapitalize="none"
                                    keyboardType="url"
                                    editable={type !== 'google'}
                                    onFocus={() => { baseUrlFocusProgress.value = withTiming(1, { duration: 200 }); }}
                                    onBlur={() => { baseUrlFocusProgress.value = withTiming(0, { duration: 200 }); }}
                                    style={[styles.input, inputStyle, { opacity: type === 'google' ? 0.6 : 1, borderWidth: 1.5 }]}
                                />
                            </Animated.View>
                        </View>

                        {/* API Key */}
                        {type !== 'google' && (
                            <View>
                                <Text style={[styles.label, { color: isDark ? '#fff' : '#111' }]}>
                                    {t.settings.providerModal.apiKey}
                                </Text>
                                <Animated.View style={apiKeyFocusStyle}>
                                    <TextInput
                                        value={apiKey}
                                        onChangeText={setApiKey}
                                        placeholder={t.settings.providerModal.apiKeyPlaceholder}
                                        placeholderTextColor="#9ca3af"
                                        secureTextEntry
                                        onFocus={() => { apiKeyFocusProgress.value = withTiming(1, { duration: 200 }); }}
                                        onBlur={() => { apiKeyFocusProgress.value = withTiming(0, { duration: 200 }); }}
                                        style={[styles.input, inputStyle, { borderWidth: 1.5 }]}
                                    />
                                </Animated.View>
                                {errors.apiKey && <Text style={styles.errorText}>{errors.apiKey}</Text>}
                            </View>
                        )}

                        {/* Vertex JSON - Optimized */}
                        {type === 'google' && (
                            <ParsedInput
                                label={t.settings.providerModal.importVertexJson}
                                value={jsonInput}
                                onValueChange={setJsonInput}
                                placeholder={t.settings.providerModal.importPlaceholder}
                                multiline
                                numberOfLines={6}
                                inputStyle={{ height: 120, textAlignVertical: 'top', fontSize: 10, fontFamily: 'monospace' }}
                                parser={(text) => JSON.parse(text)}
                                onParsed={handleJSONParsed}
                                onError={handleJSONError}
                                required
                            />
                        )}
                    </View>
                </ScrollView>
            </KeyboardAvoidingView>

            <View style={[styles.footer, {
                backgroundColor: isDark ? '#000' : '#fff',
                borderTopColor: isDark ? '#27272a' : '#e5e7eb'
            }]}>
                <Animated.View style={[{ width: '100%' }, saveAnimatedStyle]}>
                    <TouchableOpacity 
                        onPress={handleSave} 
                        disabled={isSaving}
                        style={[styles.saveBtn, { backgroundColor: colors[500] }]}
                    >
                        {isSaving ? (
                            <ActivityIndicator color="#fff" />
                        ) : (
                            <Text style={styles.btnText}>{t.settings.providerModal.save}</Text>
                        )}
                    </TouchableOpacity>
                </Animated.View>
            </View>
        </PageLayout>
    );
}

const styles = StyleSheet.create({
    scrollView: {
        flex: 1,
        paddingHorizontal: 24,
    },
    section: {
        marginBottom: 20,
    },
    sectionTitle: {
        fontSize: 13,
        fontWeight: 'bold',
        color: '#9ca3af',
        marginBottom: 10,
    },
    presetsGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
    },
    presetCard: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        borderRadius: 12,
        borderWidth: 1,
        marginBottom: 4,
    },
    presetIcon: {
        marginRight: 8,
    },
    presetName: {
        fontSize: 13,
        flex: 1,
    },
    formGap: {
        gap: 16,
    },
    label: {
        fontSize: 14,
        fontWeight: '600',
        marginBottom: 8,
    },
    input: {
        borderWidth: 1,
        borderRadius: 12,
        paddingHorizontal: 16,
        paddingVertical: 10,
        fontSize: 16,
    },
    errorText: {
        color: '#ef4444',
        fontSize: 12,
        marginTop: 4,
    },
    footer: {
        paddingHorizontal: 24,
        paddingVertical: 16,
        borderTopWidth: 1,
        // position: 'absolute',
        // bottom: 0,
        // left: 0, 
        // right: 0,
        paddingBottom: Platform.OS === 'ios' ? 40 : 20,
    },
    saveBtn: {
        borderRadius: 12,
        paddingVertical: 14,
        alignItems: 'center',
    },
    btnText: {
        color: '#fff',
        fontSize: 16,
        fontWeight: '600',
    }
});

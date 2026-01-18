import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  Modal,
  TextInput,
  ScrollView,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
} from 'react-native';
import { X, ChevronDown, ChevronLeft } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { GlassHeader } from '../../components/ui/GlassHeader';
import { ProviderConfig, ApiProviderType } from '../../store/api-store';
import { ParsedInput } from './components/ParsedInput';

interface ProviderModalProps {
  visible: boolean;
  onClose: () => void;
  onSave: (provider: Omit<ProviderConfig, 'id'>) => void;
  editingProvider?: ProviderConfig | null;
}

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
  'openai-compatible': { name: 'OpenAI Compatible (NewAPI/OneAPI)', baseUrl: '', type: 'openai-compatible' },
};

export function ProviderModal({ visible, onClose, onSave, editingProvider }: ProviderModalProps) {
  const { t } = useI18n();
  const { isDark, colors } = useTheme();
  const insets = useSafeAreaInsets();

  const [name, setName] = useState('');
  const [type, setType] = useState<ApiProviderType>('openai');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [region, setRegion] = useState('us-central1');
  const [vertexProject, setVertexProject] = useState('');
  const [jsonInput, setJsonInput] = useState('');
  const [selectedPreset, setSelectedPreset] = useState<string>('');
  const [showPresetPicker, setShowPresetPicker] = useState(false);
  const [errors, setErrors] = useState<{
    name?: string;
    apiKey?: string;
    vertexProject?: string;
    jsonInput?: string;
  }>({});

  // Reset state on open
  useEffect(() => {
    if (visible) {
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
        setSelectedPreset('');
      }
      setErrors({});
    }
  }, [editingProvider, visible]);

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
    // We defer the rigorous check to Save, but ParsedInput handles inline debounced validation
    // Here we could update 'errors' state if we wanted real-time error messages in strict mode
    // For now, let ParsedInput handle its own display, we just clear/set errors for the modal if needed
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
        // Double check JSON validity before save
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
      return;
    }

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

      onSave({
        name: name.trim(),
        type,
        baseUrl: baseUrl.trim() || undefined,
        apiKey: finalApiKey || (type === 'google' ? 'vertex-placeholder' : ''),
        enabled: true,
        models: editingProvider?.models || [],
        vertexProject: type === 'google' ? vertexProject.trim() : undefined,
        vertexLocation: type === 'google' ? region.trim() : undefined,
        vertexKeyJson: type === 'google' ? jsonInput.trim() : undefined,
      });
      onClose();
    }, 10);
  };

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
    setShowPresetPicker(false);
  }, []);

  // Memoized Styles
  const containerStyle = useMemo(() => ({
    flex: 1,
    backgroundColor: isDark ? '#000' : '#fff'
  }), [isDark]);

  const presetButtonStyle = useMemo(() => ({
    backgroundColor: isDark ? '#18181b' : '#f9fafb',
    borderWidth: 1,
    borderColor: isDark ? '#27272a' : '#e5e7eb',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    justifyContent: 'space-between' as const,
  }), [isDark]);

  const inputStyle = useMemo(() => ({
    backgroundColor: isDark ? '#18181b' : '#f9fafb',
    borderColor: isDark ? '#27272a' : '#e5e7eb',
    color: isDark ? '#fff' : '#111',
  }), [isDark]);

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent={false}
      onRequestClose={onClose}
    >
      <KeyboardAvoidingView
        style={containerStyle}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <GlassHeader
          title={editingProvider ? t.settings.providerModal.editTitle : t.settings.providerModal.addTitle}
          leftAction={{
            icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
            onPress: onClose,
          }}
          intensity={isDark ? 40 : 60}
        />

        <ScrollView
          style={styles.scrollView}
          contentContainerStyle={{ paddingTop: 64 + insets.top + 20, paddingBottom: 40 }}
          showsVerticalScrollIndicator={false}
        >
          {/* Presets */}
          {!editingProvider && (
            <View style={styles.section}>
              <Text style={styles.sectionTitle}>{t.settings.providerModal.presets}</Text>

              <TouchableOpacity
                onPress={() => setShowPresetPicker(!showPresetPicker)}
                style={presetButtonStyle}
              >
                <Text style={{
                  fontSize: 14,
                  color: selectedPreset ? (isDark ? '#fff' : '#111') : '#9ca3af'
                }}>
                  {selectedPreset ? PROVIDER_PRESETS[selectedPreset].name : t.settings.selectProvider}
                </Text>
                <ChevronDown size={20} color="#9ca3af" />
              </TouchableOpacity>

              {showPresetPicker && (
                <View style={[styles.dropdown, {
                  backgroundColor: isDark ? '#18181b' : '#fff',
                  borderColor: isDark ? '#27272a' : '#e5e7eb'
                }]}>
                  <ScrollView nestedScrollEnabled>
                    {Object.entries(PROVIDER_PRESETS).map(([key, preset]) => (
                      <TouchableOpacity
                        key={key}
                        onPress={() => handlePresetSelect(key)}
                        style={[styles.dropdownItem, {
                          borderBottomColor: isDark ? '#27272a' : '#f3f4f6',
                          backgroundColor: selectedPreset === key ? (isDark ? '#27272a' : '#f3f4f6') : 'transparent'
                        }]}
                      >
                        <Text style={{
                          color: selectedPreset === key ? colors[500] : (isDark ? '#fff' : '#111'),
                          fontWeight: selectedPreset === key ? '600' : '400'
                        }}>
                          {preset.name}
                        </Text>
                      </TouchableOpacity>
                    ))}
                  </ScrollView>
                </View>
              )}
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
              <TextInput
                value={baseUrl}
                onChangeText={setBaseUrl}
                placeholder={t.settings.providerModal.baseUrlPlaceholder}
                placeholderTextColor="#9ca3af"
                autoCapitalize="none"
                keyboardType="url"
                editable={type !== 'google'}
                style={[styles.input, inputStyle, { opacity: type === 'google' ? 0.6 : 1 }]}
              />
            </View>

            {/* API Key */}
            {type !== 'google' && (
              <View>
                <Text style={[styles.label, { color: isDark ? '#fff' : '#111' }]}>
                  {t.settings.providerModal.apiKey}
                </Text>
                <TextInput
                  value={apiKey}
                  onChangeText={setApiKey}
                  placeholder={t.settings.providerModal.apiKeyPlaceholder}
                  placeholderTextColor="#9ca3af"
                  secureTextEntry
                  style={[styles.input, inputStyle]}
                />
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

          <View style={{ height: 40 }} />
        </ScrollView>

        <View style={[styles.footer, { borderTopColor: isDark ? '#27272a' : '#e5e7eb' }]}>
          <TouchableOpacity onPress={handleSave} style={[styles.saveBtn, { backgroundColor: colors[500] }]}>
            <Text style={styles.btnText}>{t.settings.providerModal.save}</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={onClose} style={[styles.cancelBtn, { backgroundColor: isDark ? '#27272a' : '#f3f4f6' }]}>
            <Text style={[styles.btnText, { color: isDark ? '#fff' : '#111' }]}>{t.settings.providerModal.cancel}</Text>
          </TouchableOpacity>
        </View>
      </KeyboardAvoidingView>
    </Modal>
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
  dropdown: {
    marginTop: 8,
    borderWidth: 1,
    borderRadius: 12,
    maxHeight: 300,
    overflow: 'hidden',
  },
  dropdownItem: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
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
    gap: 12,
  },
  saveBtn: {
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  cancelBtn: {
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

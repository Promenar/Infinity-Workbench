import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  Modal,
  TextInput,
  ScrollView,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { X, ChevronDown, ChevronLeft } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { GlassHeader } from '../../components/ui/GlassHeader';
import { ProviderConfig, ApiProviderType } from '../../store/api-store';

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
};

export function ProviderModal({ visible, onClose, onSave, editingProvider }: ProviderModalProps) {
  const { t } = useI18n();
  const { theme, isDark } = useTheme();
  const insets = useSafeAreaInsets();

  const [name, setName] = useState('');
  const [type, setType] = useState<ApiProviderType>('openai');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [region, setRegion] = useState('us-central1');
  const [vertexProject, setVertexProject] = useState('');
  const [jsonInput, setJsonInput] = useState('');
  const [selectedPreset, setSelectedPreset] = useState<string>(''); // 新增：预设选择
  const [showPresetPicker, setShowPresetPicker] = useState(false); // 新增：显示选择器
  const [errors, setErrors] = useState<{
    name?: string;
    apiKey?: string;
    vertexProject?: string;
    jsonInput?: string;
  }>({});

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
      setSelectedPreset(''); // 重置预设选择
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
    if (!presetKey) return;

    const preset = PROVIDER_PRESETS[presetKey];
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      setSelectedPreset(presetKey);
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
      setShowPresetPicker(false); // 关闭选择器
    }, 10);
  };

  const handleSave = () => {
    const newErrors: {
      name?: string;
      apiKey?: string;
      vertexProject?: string;
      jsonInput?: string;
    } = {};

    if (!name.trim()) {
      newErrors.name = t.settings.providerModal.nameRequired;
    }

    // VertexAI 特殊验证
    if (type === 'google') {
      if (!vertexProject.trim()) {
        newErrors.vertexProject = 'Project ID is required';
      }
      if (jsonInput && jsonInput.trim()) {
        try {
          const json = JSON.parse(jsonInput);
          if (!json.private_key || !json.client_email) {
            newErrors.jsonInput = 'Invalid JSON: missing private_key or client_email';
          }
        } catch (e) {
          newErrors.jsonInput = 'Invalid JSON format';
        }
      } else {
        newErrors.jsonInput = 'Service Account JSON is required';
      }
    } else {
      // 其他服务商需要 API Key
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

      // VertexAI 特殊处理
      let finalApiKey = apiKey.trim();
      if (type === 'google' && jsonInput) {
        try {
          const json = JSON.parse(jsonInput);
          finalApiKey = json.private_key || 'vertex-placeholder';
        } catch (e) {
          // JSON 解析失败，使用占位符
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
        style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff' }}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <GlassHeader
          title={
            editingProvider ? t.settings.providerModal.editTitle : t.settings.providerModal.addTitle
          }
          leftAction={{
            icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
            onPress: handleCancel,
          }}
          intensity={isDark ? 40 : 60}
        />

        <ScrollView
          style={{ flex: 1, paddingHorizontal: 24 }}
          contentContainerStyle={{ paddingTop: 64 + insets.top + 20, paddingBottom: 40 }}
          showsVerticalScrollIndicator={false}
        >
          {/* 快速配置 (仅添加模式) */}
          {!editingProvider && (
            <View style={{ marginBottom: 20 }}>
              <Text
                style={{ fontSize: 13, fontWeight: 'bold', color: '#9ca3af', marginBottom: 10 }}
              >
                {t.settings.providerModal.presets}
              </Text>

              {/* 自定义下拉选择器 */}
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    setShowPresetPicker(!showPresetPicker);
                  }, 10);
                }}
                style={{
                  backgroundColor: isDark ? '#18181b' : '#f9fafb',
                  borderWidth: 1,
                  borderColor: isDark ? '#27272a' : '#e5e7eb',
                  borderRadius: 12,
                  paddingHorizontal: 16,
                  paddingVertical: 12,
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <Text
                  style={{
                    fontSize: 14,
                    color: selectedPreset ? (isDark ? '#fff' : '#111') : '#9ca3af',
                  }}
                >
                  {selectedPreset
                    ? PROVIDER_PRESETS[selectedPreset].name
                    : t.settings.selectProvider}
                </Text>
                <ChevronDown size={20} color="#9ca3af" />
              </TouchableOpacity>

              {/* 下拉选项列表 */}
              {showPresetPicker && (
                <View
                  style={{
                    marginTop: 8,
                    backgroundColor: isDark ? '#18181b' : '#fff',
                    borderWidth: 1,
                    borderColor: isDark ? '#27272a' : '#e5e7eb',
                    borderRadius: 12,
                    maxHeight: 300,
                    overflow: 'hidden',
                  }}
                >
                  <ScrollView
                    nestedScrollEnabled={true}
                    onStartShouldSetResponder={() => true}
                    onMoveShouldSetResponder={() => true}
                  >
                    {Object.entries(PROVIDER_PRESETS).map(([key, preset]) => (
                      <TouchableOpacity
                        key={key}
                        onPress={() => handlePresetSelect(key)}
                        style={{
                          paddingHorizontal: 16,
                          paddingVertical: 12,
                          backgroundColor:
                            selectedPreset === key
                              ? isDark
                                ? '#27272a'
                                : '#f3f4f6'
                              : 'transparent',
                          borderBottomWidth: 1,
                          borderBottomColor: isDark ? '#27272a' : '#f3f4f6',
                        }}
                      >
                        <Text
                          style={{
                            fontSize: 14,
                            fontWeight: selectedPreset === key ? '600' : '400',
                            color: selectedPreset === key ? '#6366f1' : isDark ? '#fff' : '#111',
                          }}
                        >
                          {preset.name}
                        </Text>
                      </TouchableOpacity>
                    ))}
                  </ScrollView>
                </View>
              )}
            </View>
          )}

          {/* 表单字段 */}
          <View style={{ gap: 16 }}>
            {/* 名称 */}
            <View>
              <Text
                style={{
                  fontSize: 14,
                  fontWeight: '600',
                  color: isDark ? '#fff' : '#111',
                  marginBottom: 8,
                }}
              >
                {t.settings.providerModal.name}
              </Text>
              <TextInput
                value={name}
                onChangeText={setName}
                placeholder={t.settings.providerModal.namePlaceholder}
                placeholderTextColor="#9ca3af"
                style={{
                  backgroundColor: isDark ? '#18181b' : '#f9fafb',
                  borderWidth: 1,
                  borderColor: errors.name ? '#ef4444' : isDark ? '#27272a' : '#e5e7eb',
                  borderRadius: 12,
                  paddingHorizontal: 16,
                  paddingVertical: 12,
                  fontSize: 16,
                  color: isDark ? '#fff' : '#111',
                }}
              />
              {errors.name && (
                <Text style={{ color: '#ef4444', fontSize: 12, marginTop: 4 }}>{errors.name}</Text>
              )}
            </View>

            {/* VertexAI 特有字段 */}
            {type === 'google' && (
              <>
                {/* Project ID（必填） */}
                <View>
                  <Text
                    style={{
                      fontSize: 14,
                      fontWeight: '600',
                      color: isDark ? '#fff' : '#111',
                      marginBottom: 8,
                    }}
                  >
                    Google Cloud Project ID *
                  </Text>
                  <TextInput
                    value={vertexProject}
                    onChangeText={setVertexProject}
                    placeholder="my-project-123456"
                    placeholderTextColor="#9ca3af"
                    autoCapitalize="none"
                    style={{
                      backgroundColor: isDark ? '#18181b' : '#f9fafb',
                      borderWidth: 1,
                      borderColor: errors.vertexProject
                        ? '#ef4444'
                        : isDark
                          ? '#27272a'
                          : '#e5e7eb',
                      borderRadius: 12,
                      paddingHorizontal: 16,
                      paddingVertical: 10,
                      fontSize: 16,
                      color: isDark ? '#fff' : '#111',
                    }}
                  />
                  {errors.vertexProject && (
                    <Text style={{ color: '#ef4444', fontSize: 12, marginTop: 4 }}>
                      {errors.vertexProject}
                    </Text>
                  )}
                </View>

                {/* Region */}
                <View>
                  <Text
                    style={{
                      fontSize: 14,
                      fontWeight: '600',
                      color: isDark ? '#fff' : '#111',
                      marginBottom: 8,
                    }}
                  >
                    {t.settings.providerModal.region}
                  </Text>
                  <TextInput
                    value={region}
                    onChangeText={setRegion}
                    placeholder={t.settings.providerModal.regionPlaceholder}
                    placeholderTextColor="#9ca3af"
                    autoCapitalize="none"
                    style={{
                      backgroundColor: isDark ? '#18181b' : '#f9fafb',
                      borderWidth: 1,
                      borderColor: isDark ? '#27272a' : '#e5e7eb',
                      borderRadius: 12,
                      paddingHorizontal: 16,
                      paddingVertical: 10,
                      fontSize: 16,
                      color: isDark ? '#fff' : '#111',
                    }}
                  />
                </View>
              </>
            )}

            {/* Base URL */}
            <View>
              <Text
                style={{
                  fontSize: 14,
                  fontWeight: '600',
                  color: isDark ? '#fff' : '#111',
                  marginBottom: 8,
                }}
              >
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
                  backgroundColor: isDark ? '#18181b' : '#f9fafb',
                  borderWidth: 1,
                  borderColor: isDark ? '#27272a' : '#e5e7eb',
                  borderRadius: 12,
                  paddingHorizontal: 16,
                  paddingVertical: 10,
                  fontSize: 16,
                  color: isDark ? '#fff' : '#111',
                  opacity: type === 'google' ? 0.6 : 1,
                }}
                editable={type !== 'google'}
              />
            </View>

            {/* API Key (对于 VertexAI 隐藏) */}
            <View style={{ display: type === 'google' ? 'none' : 'flex' }}>
              <Text
                style={{
                  fontSize: 14,
                  fontWeight: '600',
                  color: isDark ? '#fff' : '#111',
                  marginBottom: 8,
                }}
              >
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
                  backgroundColor: isDark ? '#18181b' : '#f9fafb',
                  borderWidth: 1,
                  borderColor: errors.apiKey ? '#ef4444' : isDark ? '#27272a' : '#e5e7eb',
                  borderRadius: 12,
                  paddingHorizontal: 16,
                  paddingVertical: 10,
                  fontSize: 16,
                  color: isDark ? '#fff' : '#111',
                  fontFamily: 'monospace',
                }}
              />
              {errors.apiKey && (
                <Text style={{ color: '#ef4444', fontSize: 12, marginTop: 4 }}>
                  {errors.apiKey}
                </Text>
              )}
            </View>

            {/* VertexAI Service Account JSON */}
            {type === 'google' && (
              <View>
                <Text
                  style={{
                    fontSize: 14,
                    fontWeight: '600',
                    color: isDark ? '#fff' : '#111',
                    marginBottom: 8,
                  }}
                >
                  {t.settings.providerModal.importVertexJson} *
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
                      if (json.project_id && !vertexProject) {
                        setVertexProject(json.project_id);
                      }
                      if (json.project_id && !name) {
                        setName(`VertexAI - ${json.project_id}`);
                      }
                    } catch (e) {
                      // 无效 JSON，忽略
                    }
                  }}
                  style={{
                    backgroundColor: isDark ? '#18181b' : '#f9fafb',
                    borderWidth: 1,
                    borderColor: errors.jsonInput ? '#ef4444' : isDark ? '#27272a' : '#e5e7eb',
                    borderRadius: 12,
                    paddingHorizontal: 16,
                    paddingVertical: 10,
                    fontSize: 10,
                    color: isDark ? '#fff' : '#111',
                    height: 120,
                    textAlignVertical: 'top',
                    fontFamily: 'monospace',
                  }}
                />
                {errors.jsonInput && (
                  <Text style={{ color: '#ef4444', fontSize: 12, marginTop: 4 }}>
                    {errors.jsonInput}
                  </Text>
                )}
              </View>
            )}
          </View>

          <View style={{ height: 40 }} />
        </ScrollView>

        {/* Footer Buttons */}
        <View
          style={{
            paddingHorizontal: 24,
            paddingVertical: 16,
            borderTopWidth: 1,
            borderTopColor: isDark ? '#27272a' : '#e5e7eb',
            gap: 12,
          }}
        >
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
              backgroundColor: isDark ? '#27272a' : '#f3f4f6',
              borderRadius: 12,
              paddingVertical: 14,
              alignItems: 'center',
            }}
          >
            <Text style={{ color: isDark ? '#fff' : '#111', fontSize: 16, fontWeight: '600' }}>
              {t.settings.providerModal.cancel}
            </Text>
          </TouchableOpacity>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

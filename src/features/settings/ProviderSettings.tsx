import React, { useState } from 'react';
import { View, ScrollView, TouchableOpacity, Text, TextInput, Modal } from 'react-native';
import { Typography, ConfirmDialog } from '../../components/ui';
import {
  Plus,
  Trash2,
  Edit3,
  ChevronRight,
  FileJson,
  RefreshCw,
  Check,
  X,
} from 'lucide-react-native';
import { useApiStore, ApiProviderType, ProviderConfig } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';
import { ModelService, parseVertexAIConfig } from '../../lib/provider-parser';
import * as DocumentPicker from 'expo-document-picker';
import { clsx } from 'clsx';
import * as Haptics from '../../lib/haptics';

/**
 * 供应商设置组件
 * 支持供应商增删改查、JSON 导入、模型管理
 */
export const ProviderSettings = () => {
  const {
    providers,
    addProvider,
    updateProvider,
    deleteProvider,
    globalStats,
    resetStats,
    enabledModels,
    toggleModel,
  } = useApiStore();
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [editingProvider, setEditingProvider] = useState<ProviderConfig | null>(null);
  // State for managing available models modal
  const [activeProviderModels, setActiveProviderModels] = useState<{
    id: string;
    models: import('../../store/api-store').ModelConfig[];
  } | null>(null);

  // 添加/编辑供应商表单状态
  const [formData, setFormData] = useState<Partial<ProviderConfig>>({
    name: '',
    type: 'openai',
    apiKey: '',
    baseUrl: '',
    enabled: true,
  });

  // 确认弹窗状态
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
    onConfirm: () => {},
    isDestructive: false,
  });

  const handlePickJson = async () => {
    setTimeout(async () => {
      try {
        const result = await DocumentPicker.getDocumentAsync({ type: 'application/json' });
        if (!result.canceled && result.assets && result.assets[0]) {
          const response = await fetch(result.assets[0].uri);
          const content = await response.text();
          const config = parseVertexAIConfig(content);
          setFormData({
            ...formData,
            type: 'google',
            vertexProject: config.projectId,
            vertexKeyJson: content,
            vertexLocation: config.location,
          });
        }
      } catch (error) {
        setConfirmState({
          visible: true,
          title: '错误',
          message: '读取文件失败',
          onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
        });
      }
    }, 10);
  };

  const handleSave = () => {
    setTimeout(() => {
      if (!formData.name) {
        setConfirmState({
          visible: true,
          title: '提示',
          message: '请输入名称',
          onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
        });
        return;
      }
      if (editingProvider) {
        updateProvider(editingProvider.id, formData);
      } else {
        addProvider(formData as any);
      }
      setIsAddModalOpen(false);
      setEditingProvider(null);
      setFormData({ name: '', type: 'openai', apiKey: '', baseUrl: '', enabled: true });
    }, 10);
  };

  const fetchModels = async (provider: ProviderConfig) => {
    setTimeout(async () => {
      try {
        console.log(
          '[ProviderSettings] fetchModels clicked for provider:',
          provider.name,
          'type:',
          provider.type,
        );
        const models = await ModelService.fetchModels(
          provider.type,
          provider.apiKey,
          provider.baseUrl,
        );
        console.log('[ProviderSettings] Fetched models count:', models.length);
        console.log(
          '[ProviderSettings] First 3 model IDs:',
          models.slice(0, 3).map((m) => m.id),
        );

        // IMPORTANT: Persist the fetched model metadata (with correct types/capabilities) to the store
        updateProvider(provider.id, { models });

        setActiveProviderModels({ id: provider.id, models });
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      } catch (error) {
        // If fetch fails, fallback to existing models or preset
        console.error('[ProviderSettings] Model fetch failed', error);
        setActiveProviderModels({ id: provider.id, models: provider.models || [] });
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
      }
    }, 10);
  };

  return (
    <View className="flex-1">
      <View className="flex-row justify-between items-center mb-4 px-2">
        <Typography variant="sectionHeader">已配置的供应商</Typography>
        <TouchableOpacity
          onPress={() => {
            setTimeout(() => {
              setEditingProvider(null);
              setFormData({ name: '', type: 'openai', apiKey: '', baseUrl: '', enabled: true });
              setIsAddModalOpen(true);
            }, 10);
          }}
          className="bg-indigo-600 p-2 rounded-full"
        >
          <Plus size={20} color="white" />
        </TouchableOpacity>
      </View>

      {providers.length === 0 ? (
        <View className="items-center py-10 bg-gray-50 dark:bg-zinc-900 rounded-3xl border border-dashed border-gray-300 dark:border-zinc-700">
          <Typography className="text-gray-400">尚未添加供应商</Typography>
        </View>
      ) : (
        providers.map((p) => (
          <View
            key={p.id}
            className="bg-white dark:bg-zinc-900 rounded-2xl p-4 mb-3 border border-gray-100 dark:border-zinc-800 shadow-sm"
          >
            <View className="flex-row justify-between items-start">
              <View>
                <Typography className="font-bold text-lg">{p.name}</Typography>
                <Typography className="text-gray-400 text-xs uppercase tracking-tighter">
                  {p.type} • {p.enabled ? '已启用' : '已禁用'}
                </Typography>
              </View>
              <View className="flex-row">
                <TouchableOpacity onPress={() => fetchModels(p)} className="p-2 mr-1">
                  <RefreshCw size={18} color="#6366f1" />
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={() => {
                    setTimeout(() => {
                      setEditingProvider(p);
                      setFormData(p);
                      setIsAddModalOpen(true);
                    }, 10);
                  }}
                  className="p-2 mr-1"
                >
                  <Edit3 size={18} color="#94a3b8" />
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={() => {
                    setConfirmState({
                      visible: true,
                      title: '删除供应商',
                      message: `确定要删除 "${p.name}" 吗？此操作将移除该供应商下的所有模型配置。`,
                      isDestructive: true,
                      onConfirm: () => {
                        deleteProvider(p.id);
                        setConfirmState((prev) => ({ ...prev, visible: false }));
                      },
                    });
                  }}
                  className="p-2"
                >
                  <Trash2 size={18} color="#f43f5e" />
                </TouchableOpacity>
              </View>
            </View>

            {/* 简易统计 */}
            <View className="mt-3 pt-3 border-t border-gray-50 dark:border-zinc-800 flex-row justify-between items-center">
              <Typography className="text-xs text-gray-500">
                消耗: {globalStats[p.id]?.totalTokens || 0} Tokens
              </Typography>
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => resetStats(p.id), 10);
                }}
              >
                <Typography className="text-xs text-indigo-500 font-bold">重置统计</Typography>
              </TouchableOpacity>
            </View>
          </View>
        ))
      )}

      {/* 模型管理 Modal */}
      <Modal visible={!!activeProviderModels} animationType="slide" transparent={true}>
        <View className="flex-1 justify-end bg-black/50">
          <View className="bg-white dark:bg-zinc-900 rounded-t-3xl p-6 h-[70%]">
            <View className="flex-row justify-between items-center mb-6">
              <Typography variant="h2">管理可用模型</Typography>
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => setActiveProviderModels(null), 10);
                }}
              >
                <X size={24} color="#94a3b8" />
              </TouchableOpacity>
            </View>
            <ScrollView className="flex-1">
              {activeProviderModels?.models.map((m) => {
                const isEnabled = enabledModels[activeProviderModels.id]?.includes(m.id);
                return (
                  <TouchableOpacity
                    key={m.uuid}
                    onPress={() => {
                      setTimeout(() => toggleModel(activeProviderModels.id, m.id, !isEnabled), 10);
                    }}
                    className={clsx(
                      'flex-row justify-between items-center p-4 mb-2 rounded-xl border',
                      isEnabled
                        ? 'bg-indigo-50 border-indigo-200 dark:bg-indigo-900/20 dark:border-indigo-800'
                        : 'bg-gray-50 border-gray-100 dark:bg-zinc-800 dark:border-zinc-700',
                    )}
                  >
                    <View>
                      <Typography
                        className={clsx(
                          isEnabled
                            ? 'text-indigo-600 font-bold'
                            : 'text-gray-600 dark:text-gray-300',
                        )}
                      >
                        {m.name}
                      </Typography>
                      {m.type === 'reasoning' && (
                        <Typography className="text-[10px] text-indigo-500 mt-0.5">
                          Reasoning
                        </Typography>
                      )}
                    </View>
                    {isEnabled && <Check size={18} color="#4f46e5" />}
                  </TouchableOpacity>
                );
              })}
            </ScrollView>
          </View>
        </View>
      </Modal>

      {/* 添加/编辑供应商 Modal */}
      <Modal visible={isAddModalOpen} animationType="fade" transparent={true}>
        <View className="flex-1 justify-center items-center bg-black/50 p-6">
          <View className="bg-white dark:bg-zinc-900 rounded-3xl p-6 w-full">
            <Typography variant="h2" className="mb-4">
              {editingProvider ? '编辑供应商' : '添加供应商'}
            </Typography>

            <ScrollView className="max-h-[400px]">
              <Typography className="mb-1 text-xs font-bold text-gray-400">名称</Typography>
              <TextInput
                value={formData.name}
                onChangeText={(t) => setFormData({ ...formData, name: t })}
                className="bg-gray-100 dark:bg-zinc-800 p-3 rounded-xl mb-4 dark:text-white"
                placeholder="例如: 我的 OpenAI"
              />

              <Typography className="mb-1 text-xs font-bold text-gray-400">类型</Typography>
              <ScrollView horizontal showsHorizontalScrollIndicator={false} className="mb-4">
                {(
                  [
                    'openai',
                    'google',
                    'deepseek',
                    'anthropic',
                    'moonshot',
                    'zhipu',
                    'siliconflow',
                    'github',
                  ] as ApiProviderType[]
                ).map((t) => (
                  <TouchableOpacity
                    key={t}
                    onPress={() => setFormData({ ...formData, type: t })}
                    className={clsx(
                      'px-4 py-2 rounded-full mr-2',
                      formData.type === t ? 'bg-indigo-600' : 'bg-gray-100 dark:bg-zinc-800',
                    )}
                  >
                    <Text
                      className={clsx(
                        'font-bold text-xs',
                        formData.type === t ? 'text-white' : 'text-gray-400',
                      )}
                    >
                      {t.toUpperCase()}
                    </Text>
                  </TouchableOpacity>
                ))}
              </ScrollView>

              {formData.type === 'google' ? (
                <View>
                  <TouchableOpacity
                    onPress={handlePickJson}
                    className="bg-indigo-50 dark:bg-indigo-900/20 p-4 rounded-xl mb-4 border border-dashed border-indigo-200 dark:border-indigo-800 flex-row justify-center items-center"
                  >
                    <FileJson size={18} color="#4f46e5" className="mr-2" />
                    <Typography className="text-indigo-600 font-bold">
                      导入 Service Account JSON
                    </Typography>
                  </TouchableOpacity>
                  {formData.vertexProject && (
                    <Typography className="text-xs text-green-500 mb-4 text-center">
                      ✅ 已识别项目: {formData.vertexProject}
                    </Typography>
                  )}
                </View>
              ) : (
                <View>
                  <Typography className="mb-1 text-xs font-bold text-gray-400">API Key</Typography>
                  <TextInput
                    value={formData.apiKey}
                    onChangeText={(t) => setFormData({ ...formData, apiKey: t })}
                    secureTextEntry
                    className="bg-gray-100 dark:bg-zinc-800 p-3 rounded-xl mb-4 dark:text-white"
                    placeholder="sk-..."
                  />
                  <Typography className="mb-1 text-xs font-bold text-gray-400">
                    Base URL (可选)
                  </Typography>
                  <TextInput
                    value={formData.baseUrl}
                    onChangeText={(t) => setFormData({ ...formData, baseUrl: t })}
                    className="bg-gray-100 dark:bg-zinc-800 p-3 rounded-xl mb-4 dark:text-white"
                    placeholder="https://..."
                  />
                </View>
              )}
            </ScrollView>

            <View className="flex-row mt-4">
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => setIsAddModalOpen(false), 10);
                }}
                className="flex-1 py-3 bg-gray-100 dark:bg-zinc-800 rounded-xl mr-2 items-center"
              >
                <Text className="font-bold text-gray-500">取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                onPress={handleSave}
                className="flex-1 py-3 bg-indigo-600 rounded-xl items-center"
              >
                <Text className="font-bold text-white">确认</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      <ConfirmDialog
        visible={confirmState.visible}
        title={confirmState.title}
        message={confirmState.message}
        isDestructive={confirmState.isDestructive}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
      />
    </View>
  );
};

import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  Modal,
  TextInput,
  ActivityIndicator,
  Platform,
  ScrollView,
} from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Switch } from '../../components/ui/Switch';
import {
  X,
  Plus,
  Trash2,
  Cpu,
  Globe,
  Eye,
  Zap,
  RefreshCw,
  Search,
  CheckCircle2,
  AlertCircle,
  ChevronLeft,
  Edit2,
} from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import { GlassHeader } from '../../components/ui/GlassHeader';
import { Typography } from '../../components/ui/Typography';
import { Marquee } from '../../components/ui/Marquee';
import { FlashList } from '@shopify/flash-list';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { ProviderConfig, ModelConfig } from '../../store/api-store';
import { useToast } from '../../components/ui/Toast';
import { createLlmClient } from '../../lib/llm/factory';
import { findContextLength, extractContextLengthFromName } from '../../lib/llm/model-specs';
import { ModelService } from '../../lib/provider-parser';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';

// 使用 any 绕过某些环境下 FlashList 的类型检测问题
const TypedFlashList = FlashList as any;

interface ModelSettingsModalProps {
  visible: boolean;
  provider: ProviderConfig | null;
  onClose: () => void;
  onUpdateModels: (models: ModelConfig[]) => void;
}

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
      <View
        style={{
          backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : '#fff', // Slightly more transparent/glassy feel in dark mode
          borderRadius: 20, // Standard: 20px
          padding: 16, // Increased padding 10->16 for breathability
          marginBottom: 10,
          marginHorizontal: 16,
          borderWidth: 1,
          borderColor: isDark ? 'rgba(99, 102, 241, 0.1)' : 'rgba(0,0,0,0.05)', // Standard Glass Border
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
                  style={{ flex: 1, height: 22, justifyContent: 'center' }}
                >
                  <Marquee
                    text={localName || t.settings.modelSettings.unnamedModel}
                    className="font-bold text-sm text-gray-900 dark:text-white"
                  />
                </TouchableOpacity>
              ) : (
                <TextInput
                  value={localName}
                  autoFocus
                  onChangeText={setLocalName}
                  onBlur={() => {
                    setIsEditingName(false);
                    if (localName !== model.name) handleSync({ name: localName });
                  }}
                  style={{
                    fontSize: 14,
                    fontWeight: 'bold',
                    color: isDark ? '#fff' : '#111',
                    flex: 1,
                    height: 22,
                    padding: 0,
                  }}
                  placeholder={t.settings.modelSettings.namePlaceholder}
                  placeholderTextColor="#9ca3af"
                />
              )}
              <Edit2 size={12} color="#9ca3af" style={{ opacity: 0.5, marginLeft: 4 }} />
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <View style={{ flex: 1 }}>
                <Text
                  numberOfLines={1}
                  ellipsizeMode="tail"
                  style={{
                    fontSize: 10,
                    color: model.isAutoFetched ? '#6b7280' : isDark ? '#d1d5db' : '#4b5563',
                    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                  }}
                >
                  {localId}
                </Text>
              </View>
              {!model.isAutoFetched && <Edit2 size={10} color="#9ca3af" style={{ opacity: 0.5, marginLeft: 4 }} />}
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
            style={{
              flex: 1,
              fontSize: 11,
              color: isDark ? '#fff' : '#111',
              backgroundColor: isDark ? '#27272a' : '#f3f4f6',
              borderRadius: 4,
              paddingHorizontal: 5,
              paddingVertical: 2,
            }}
          />
        </View>
      </View>
    );
  },
  (prev, next) => {
    // 自定义 Memo 逻辑：深比较必要的属性
    return (
      prev.model.uuid === next.model.uuid &&
      prev.model.name === next.model.name &&
      prev.model.id === next.model.id &&
      prev.model.enabled === next.model.enabled &&
      prev.model.type === next.model.type &&
      prev.model.contextLength === next.model.contextLength &&
      prev.model.capabilities.vision === next.model.capabilities.vision &&
      prev.model.capabilities.internet === next.model.capabilities.internet &&
      prev.model.capabilities.reasoning === next.model.capabilities.reasoning &&
      prev.testStatus === next.testStatus
    );
  },
);

export function ModelSettingsModal({
  visible,
  provider,
  onClose,
  onUpdateModels,
}: ModelSettingsModalProps) {
  const { t } = useI18n();
  const { theme, isDark, colors } = useTheme();
  const { showToast } = useToast();
  const insets = useSafeAreaInsets();
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isFetching, setIsFetching] = useState(false);
  const [testResults, setTestResults] = useState<
    Record<string, { loading: boolean; success?: boolean; latency?: number; error?: string }>
  >({});

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

  useEffect(() => {
    if (visible && provider) {
      // Defer list processing to allow modal animation to complete smoothly
      const task = setTimeout(() => {
        // 对没有 uuid 的旧数据进行迁移
        const initialModels = (provider.models || []).map((m) =>
          m.uuid ? m : { ...m, uuid: m.id + '-' + Math.random().toString(36).substr(2, 9) },
        );
        setModels(initialModels);
        setSearchQuery('');
        setTestResults({});
        setIsReady(true);
      }, 10); // 10ms delay (minimal) for animation to start, preventing "blank" feel

      return () => clearTimeout(task);
    } else {
      setIsReady(false);
      setModels([]);
    }
  }, [visible, provider]);

  const handleTestModel = useCallback(
    async (model: ModelConfig) => {
      if (!provider) return;

      setTestResults((prev) => ({
        ...prev,
        [model.uuid]: { loading: true },
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

        // 根据模型类型选择正确的测试方法
        let result;
        if (model.type === 'rerank' && client.testRerankConnection) {
          result = await client.testRerankConnection();
        } else {
          result = await client.testConnection();
        }

        setTestResults((prev) => ({
          ...prev,
          [model.uuid]: {
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
          [model.uuid]: { loading: false, success: false, error: (e as Error).message },
        }));
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
      }
    },
    [provider],
  );

  const handleToggleModel = useCallback(
    (uuid: string, enabled: boolean) => {
      setModels((prev) => {
        const next = prev.map((m) => (m.uuid === uuid ? { ...m, enabled } : m));
        // Defer the parent update to next tick or strictly outside certain render phases
        requestAnimationFrame(() => onUpdateModels(next));
        return next;
      });
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    },
    [onUpdateModels],
  );

  const handleDeleteModel = useCallback(
    (uuid: string) => {
      setModels((prev) => {
        const next = prev.filter((m) => m.uuid !== uuid);
        requestAnimationFrame(() => onUpdateModels(next));
        return next;
      });
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    },
    [onUpdateModels],
  );

  const handleUpdateModel = useCallback(
    (uuid: string, updates: Partial<ModelConfig>) => {
      setModels((prev) => {
        const next = prev.map((m) => (m.uuid === uuid ? { ...m, ...updates } : m));
        requestAnimationFrame(() => onUpdateModels(next));
        return next;
      });
    },
    [onUpdateModels],
  );

  const handleAutoFetch = async () => {
    if (!provider?.apiKey) {
      showToast(t.settings.modelSettings.fetchError, 'error');
      return;
    }

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
            // 模型已存在，更新时保留用户设置
            const existing = updatedModels[existingIndex];
            if (existing.isAutoFetched) {
              updatedModels[existingIndex] = {
                ...existing,
                contextLength: nm.contextLength || existing.contextLength,
                // 保留用户手动设置的类型，仅在无旧类型时使用新检测的
                type: existing.type || nm.type,
                capabilities: {
                  ...existing.capabilities,
                  // 只添加新的能力，不覆盖已有的
                  vision: existing.capabilities.vision || nm.capabilities.vision,
                  internet: existing.capabilities.internet || nm.capabilities.internet,
                  reasoning: existing.capabilities.reasoning || nm.capabilities.reasoning,
                },
              };
            }
          } else {
            // 新模型，直接添加
            updatedModels.push(nm);
          }
        });
        // Defer parent update
        requestAnimationFrame(() => onUpdateModels(updatedModels));
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
  };

  const handleManualAdd = () => {
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
      const next = [newModel, ...prev]; // Prepend new model
      onUpdateModels(next);
      return next;
    });
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  };

  const handleDisableAll = () => {
    setConfirmState({
      visible: true,
      title: t.settings.modelSettings.disableAll,
      message: t.settings.modelSettings.disableAllConfirm,
      isDestructive: false,
      onConfirm: () => {
        setModels((prev) => {
          const next = prev.map((m) => ({ ...m, enabled: false }));
          onUpdateModels(next);
          return next;
        });
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        showToast(t.settings.modelSettings.disableAllSuccess, 'info');
        setConfirmState((prev) => ({ ...prev, visible: false }));
      },
    });
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  };

  const handleDeleteAll = () => {
    setConfirmState({
      visible: true,
      title: t.settings.modelSettings.deleteAll,
      message: t.settings.modelSettings.deleteAllConfirm,
      isDestructive: true,
      onConfirm: () => {
        setModels([]);
        onUpdateModels([]);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        showToast(t.settings.modelSettings.deleteAllSuccess, 'info');
        setConfirmState((prev) => ({ ...prev, visible: false }));
      },
    });
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
  };

  const filteredModels = useMemo(() => {
    if (!searchQuery) return models;
    const query = searchQuery.toLowerCase();
    return models.filter(
      (m) => m.name.toLowerCase().includes(query) || m.id.toLowerCase().includes(query),
    );
  }, [models, searchQuery]);

  const renderItem = useCallback(
    ({ item }: { item: ModelConfig }) => (
      <ModelItem
        model={item}
        onToggle={handleToggleModel}
        onDelete={handleDeleteModel}
        onUpdate={handleUpdateModel}
        onTest={handleTestModel}
        testStatus={testResults[item.uuid]}
      />
    ),
    [
      handleToggleModel,
      handleDeleteModel,
      handleUpdateModel,
      handleTestModel,
      testResults,
    ],
  );

  const renderHeader = useCallback(
    () => (
      <View>
        <View style={{ paddingHorizontal: 16, marginBottom: 12 }}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: isDark ? 'rgba(24, 24, 27, 0.8)' : '#f3f4f6', // Zinc-900 with transparency
              borderRadius: 14,
              paddingHorizontal: 12,
              height: 48,
              borderWidth: 1,
              borderColor: isDark ? 'rgba(255, 255, 255, 0.1)' : '#e5e7eb',
            }}
          >
            <Search size={18} color="#9ca3af" />
            <TextInput
              placeholder={t.settings.modelSettings.searchPlaceholder}
              placeholderTextColor="#9ca3af"
              value={searchQuery}
              onChangeText={setSearchQuery}
              style={{ flex: 1, marginLeft: 8, fontSize: 14, color: isDark ? '#fff' : '#111' }}
            />
            {searchQuery !== '' && (
              <TouchableOpacity onPress={() => setSearchQuery('')}>
                <X size={16} color="#9ca3af" />
              </TouchableOpacity>
            )}
          </View>
        </View>

        <View style={{ paddingHorizontal: 16, marginBottom: 16 }}>
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

            {/* 手动添加 */}
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
      </View>
    ),
    [
      isDark,
      theme,
      searchQuery,
      isFetching,
      t,
      handleAutoFetch,
      handleManualAdd,
      handleDisableAll,
      handleDeleteAll,
    ],
  );

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent={true}
      onRequestClose={onClose}
      statusBarTranslucent={true}
    >
      <View style={{ flex: 1, backgroundColor: isDark ? '#09090b' : '#f9fafb' }}>
        <GlassHeader
          title={provider?.name || ''}
          subtitle={t.settings.modelSettings.title}
          leftAction={{
            icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
            onPress: onClose,
          }}
          intensity={isDark ? 0 : 60} // Disable blur in dark mode to avoid gray overlay
        />
        <View style={{ flex: 1 }}>

          {isReady ? (
            <TypedFlashList
              data={filteredModels}
              renderItem={renderItem}
              estimatedItemSize={195}
              removeClippedSubviews={false}
              keyExtractor={(item: ModelConfig) => item.uuid}
              contentContainerStyle={{
                paddingBottom: 450, // 💡 增加底部间距，允许用户手动向上滚动以避开键盘 (Rule 1)
                paddingTop: insets.top + 64 + 16,
              }}
              ListHeaderComponent={renderHeader}
              extraData={testResults}
              getItemType={() => 'model'}
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
        </View>
      </View>
    </Modal>
  );
}

function TypeButton({
  label,
  active,
  onPress,
}: {
  label: string;
  active: boolean;
  onPress: () => void;
}) {
  const { isDark, colors } = useTheme();
  return (
    <TouchableOpacity
      onPress={onPress}
      style={{
        paddingHorizontal: 8,
        paddingVertical: 3,
        borderRadius: 7,
        backgroundColor: active ? colors[500] : isDark ? 'rgba(255, 255, 255, 0.05)' : '#f3f4f6',
      }}
    >
      <Typography
        style={{
          fontSize: 9,
          fontWeight: 'bold',
          color: active ? '#fff' : isDark ? '#9ca3af' : '#6b7280',
        }}
      >
        {label}
      </Typography>
    </TouchableOpacity>
  );
}

function CapabilityTag({
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
  return (
    <TouchableOpacity
      onPress={onToggle}
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 6,
        paddingVertical: 3,
        borderRadius: 6,
        backgroundColor: active
          ? colors.opacity20
          : isDark
            ? 'rgba(24, 24, 27, 0.5)'
            : '#f3f4f6',
        borderWidth: 1,
        borderColor: active ? colors[500] : isDark ? 'rgba(255, 255, 255, 0.05)' : 'transparent',
        gap: 3,
      }}
    >
      <View style={{ opacity: active ? 1 : 0.5 }}>
        {React.isValidElement(icon) &&
          React.cloneElement(icon as React.ReactElement<any>, {
            color: active ? colors[500] : isDark ? '#9ca3af' : '#6b7280',
          })}
      </View>
      <Typography
        style={{
          fontSize: 9,
          fontWeight: 'bold',
          color: active ? colors[500] : isDark ? '#9ca3af' : '#6b7280',
        }}
      >
        {label}
      </Typography>
    </TouchableOpacity>
  );
}

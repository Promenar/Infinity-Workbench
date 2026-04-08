import React, { useMemo, useState, useCallback, useRef } from 'react';
import { View, TouchableOpacity, StyleSheet, ScrollView, TextInput } from 'react-native';
import { Check, Cpu, Search, X } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { useChatStore } from '../../../../store/chat-store';
import { useApiStore, ModelConfig } from '../../../../store/api-store';
import { Spacing } from '../../../../theme/glass';
import { impactAsync, ImpactFeedbackStyle } from 'expo-haptics';
import { ModelIconRenderer } from '../../../../components/icons/ModelIconRenderer';

interface ModelSelectorPanelProps {
  sessionId: string;
}

const formatContextLength = (length?: number): string | null => {
  if (!length) return null;
  if (length >= 1000) return `${Math.round(length / 1000)}K`;
  return length.toString();
};

const getModelTags = (item: ModelConfig): { text: string; color: string }[] => {
  const tags: { text: string; color: string }[] = [];

  if (item.type === 'reasoning' || item.capabilities?.reasoning) {
    tags.push({ text: '推理', color: '#7c3aed' });
  }

  if (item.type === 'image' || item.capabilities?.vision) {
    tags.push({ text: '视觉', color: '#db2777' });
  }

  if (item.capabilities?.internet) {
    tags.push({ text: '联网', color: '#0ea5e9' });
  }

  if (item.capabilities?.tools) {
    tags.push({ text: '工具', color: '#f59e0b' });
  }

  if (tags.length === 0 && item.type !== 'rerank' && item.type !== 'embedding') {
    tags.push({ text: '对话', color: '#059669' });
  }

  const contextStr = formatContextLength(item.contextLength);
  if (contextStr) {
    tags.push({ text: contextStr, color: '#2563eb' });
  }

  return tags;
};

export const ModelSelectorPanel: React.FC<ModelSelectorPanelProps> = ({ sessionId }) => {
  const { isDark } = useTheme();
  const { providers } = useApiStore();
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const updateSession = useChatStore((s) => s.updateSession);
  const [searchQuery, setSearchQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const currentModelId = session?.modelId;

  const handleSearchChange = useCallback((text: string) => {
    setSearchQuery(text);
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = setTimeout(() => {
      setDebouncedQuery(text);
    }, 150);
  }, []);

  const allModels = useMemo(() => {
    const models: (ModelConfig & { providerId: string; providerName: string; providerType: string })[] = [];
    for (const provider of providers) {
      if (!provider.enabled) continue;
      for (const model of provider.models) {
        if (!model.enabled) continue;

        const isExplicitMatch = model.type === 'chat' || model.type === 'reasoning' || model.type === 'image';
        const isChatLogicMatch = !model.type || model.type === 'reasoning';

        if (!isExplicitMatch && !isChatLogicMatch) continue;

        if (
          model.id.toLowerCase().includes('embedding') ||
          model.id.toLowerCase().includes('embed') ||
          model.id.toLowerCase().includes('audio') ||
          model.id.toLowerCase().includes('tts') ||
          model.id.toLowerCase().includes('whisper') ||
          model.id.toLowerCase().includes('rerank')
        ) {
          continue;
        }

        models.push({
          ...model,
          providerId: provider.id,
          providerName: provider.name || provider.type,
          providerType: provider.type,
        });
      }
    }
    return models;
  }, [providers]);

  const filteredModels = useMemo(() => {
    if (!debouncedQuery) return allModels;
    const q = debouncedQuery.toLowerCase();
    return allModels.filter(
      (m) =>
        m.name.toLowerCase().includes(q) ||
        m.id.toLowerCase().includes(q) ||
        m.providerName.toLowerCase().includes(q)
    );
  }, [allModels, debouncedQuery]);

  const currentModel = allModels.find((m) => m.uuid === currentModelId);

  const groupedModels = useMemo(() => {
    const groups: Record<string, typeof filteredModels> = {};
    for (const model of filteredModels) {
      const provider = model.providerName || 'other';
      if (!groups[provider]) groups[provider] = [];
      groups[provider].push(model);
    }
    return groups;
  }, [filteredModels]);

  const handleSelectModel = (modelUuid: string) => {
    impactAsync(ImpactFeedbackStyle.Light);
    updateSession(sessionId, { modelId: modelUuid } as any);
  };

  return (
    <View style={styles.container}>
      <View style={[styles.searchContainer, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f4f4f5' }]}>
        <Search size={16} color={isDark ? '#71717a' : '#9ca3af'} />
        <TextInput
          style={[styles.searchInput, { color: isDark ? '#fff' : '#111' }]}
          placeholder="搜索模型..."
          placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
          value={searchQuery}
          onChangeText={handleSearchChange}
        />
        {searchQuery.length > 0 && (
          <TouchableOpacity onPress={() => { setSearchQuery(''); setDebouncedQuery(''); }}>
            <X size={16} color={isDark ? '#71717a' : '#9ca3af'} />
          </TouchableOpacity>
        )}
      </View>

      <ScrollView style={styles.list} showsVerticalScrollIndicator={false} contentContainerStyle={styles.listContent}>
        {Object.entries(groupedModels).map(([provider, providerModels]) => {
          return (
            <View key={provider} style={styles.providerSection}>
              <View style={styles.providerHeader}>
                <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', fontWeight: '600', letterSpacing: 0.5 }}>
                  {provider.toUpperCase()}
                </Typography>
                <Typography style={{ fontSize: 10, color: isDark ? '#52525b' : '#9ca3af', marginLeft: 6 }}>
                  {providerModels.length}
                </Typography>
              </View>

              {providerModels.map((model) => {
                const isSelected = model.uuid === currentModelId;
                const tags = getModelTags(model);
                const iconSlug = model.icon || model.id.split('-')[0] || model.providerType;

                return (
                  <TouchableOpacity
                    key={model.uuid}
                    onPress={() => handleSelectModel(model.uuid)}
                    style={[
                      styles.modelItem,
                      {
                        backgroundColor: isSelected
                          ? isDark ? 'rgba(99, 102, 241, 0.12)' : '#EEF2FF'
                          : isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.01)',
                        borderColor: isSelected
                          ? isDark ? 'rgba(99, 102, 241, 0.3)' : 'rgba(99, 102, 241, 0.2)'
                          : isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                      },
                    ]}
                  >
                    <View style={[styles.modelIcon, { backgroundColor: isDark ? 'rgba(255,255,255,0.06)' : '#f3f4f6' }]}>
                      <ModelIconRenderer icon={iconSlug} size={20} />
                    </View>
                    <View style={styles.modelInfo}>
                      <Typography style={{ fontSize: 13, fontWeight: isSelected ? '600' : '500', color: isDark ? '#fff' : '#111' }} numberOfLines={1}>
                        {model.name}
                      </Typography>
                      <View style={styles.tagsRow}>
                        {tags.slice(0, 3).map((tag, idx) => (
                          <View key={idx} style={[styles.miniTag, { backgroundColor: `${tag.color}12` }]}>
                            <Typography style={{ fontSize: 9, color: tag.color }}>{tag.text}</Typography>
                          </View>
                        ))}
                      </View>
                    </View>
                    {isSelected && (
                      <View style={[styles.checkIcon, { backgroundColor: '#6366f1' }]}>
                        <Check size={12} color="#fff" />
                      </View>
                    )}
                  </TouchableOpacity>
                );
              })}
            </View>
          );
        })}

        {filteredModels.length === 0 && (
          <View style={styles.emptyState}>
            <Typography style={{ color: isDark ? '#71717a' : '#6b7280', fontSize: 13 }}>
              未找到匹配的模型
            </Typography>
          </View>
        )}
        
        <View style={{ height: 20 }} />
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  searchContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: Spacing[4],
    marginVertical: Spacing[3],
    paddingHorizontal: Spacing[3],
    paddingVertical: Spacing[2],
    borderRadius: 12,
    gap: 8,
  },
  searchInput: {
    flex: 1,
    fontSize: 14,
    paddingVertical: 4,
  },
  list: {
    flex: 1,
  },
  listContent: {
    paddingBottom: Spacing[6],
  },
  providerSection: {
    marginBottom: Spacing[3],
  },
  providerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[2],
  },
  modelItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
    marginHorizontal: Spacing[3],
    marginVertical: 2,
    borderRadius: 14,
    borderWidth: 1,
  },
  modelIcon: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modelInfo: {
    flex: 1,
    marginLeft: Spacing[3],
  },
  tagsRow: {
    flexDirection: 'row',
    marginTop: 4,
    gap: 4,
  },
  miniTag: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  checkIcon: {
    width: 20,
    height: 20,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyState: {
    alignItems: 'center',
    paddingVertical: Spacing[8],
  },
});

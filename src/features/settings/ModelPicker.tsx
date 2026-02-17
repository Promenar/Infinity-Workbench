import React, { useMemo, useState, useRef, useCallback } from 'react';
import { View, Text, TouchableOpacity, Modal, TextInput } from 'react-native';
import { FlashList } from '@shopify/flash-list';

import { X, Search, Check, Cpu, Server } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';
import { useApiStore, ModelConfig, ProviderConfig } from '../../store/api-store';
import * as Haptics from '../../lib/haptics';
import { GlassBottomSheet } from '../../components/ui/GlassBottomSheet';
import Animated, {
  FadeIn,
} from 'react-native-reanimated';

import { findModelSpec } from '../../lib/llm/model-utils';
import { ModelIconRenderer } from '../../components/icons/ModelIconRenderer';

// 使用 any 绕过某些环境下 FlashList 的类型检测问题
const TypedFlashList = FlashList as any;

interface ModelPickerProps {
  visible: boolean;
  onClose: () => void;
  onSelect: (uuid: string) => void;
  selectedUuid?: string;
  title: string;
  filterType?: 'chat' | 'reasoning' | 'image' | 'embedding' | 'rerank';
}

import { useSafeAreaInsets } from 'react-native-safe-area-context';

export const ModelPicker: React.FC<ModelPickerProps> = ({
  visible,
  onClose,
  onSelect,
  selectedUuid,
  title,
  filterType,
}) => {
  const { theme, isDark, colors } = useTheme();
  const { t } = useI18n();
  const { providers } = useApiStore();
  const insets = useSafeAreaInsets();
  const [searchQuery, setSearchQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 防抖搜索
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
    const models: (ModelConfig & { providerName: string })[] = [];
    providers.forEach((p) => {
      p.models.forEach((m) => {
        if (m.enabled) {
          // Smart filtering: If filterType is 'chat', we carefully exclude non-chat models
          // even if they don't have an explicit type set.
          // We explicitly ALLOW 'reasoning' models when filter is 'chat'.
          const isExplicitMatch = m.type === filterType;
          const isChatLogicMatch = filterType === 'chat' && (m.type === 'reasoning' || !m.type);

          if (!filterType || isExplicitMatch || isChatLogicMatch) {
            // Extra safety check for chat: exclude embedding/audio by ID keywords
            if (
              filterType === 'chat' &&
              (m.id.toLowerCase().includes('embedding') ||
                m.id.toLowerCase().includes('embed') ||
                m.id.toLowerCase().includes('audio') ||
                m.id.toLowerCase().includes('tts') ||
                m.id.toLowerCase().includes('whisper'))
            ) {
              return;
            }
            models.push({ ...m, providerName: p.name });
          }
        }
      });
    });
    return models;
  }, [providers, filterType]);

  const filteredModels = useMemo(() => {
    if (!debouncedQuery) return allModels;
    const q = debouncedQuery.toLowerCase();
    return allModels.filter(
      (m) =>
        m.name.toLowerCase().includes(q) ||
        m.id.toLowerCase().includes(q) ||
        m.providerName.toLowerCase().includes(q),
    );
  }, [allModels, debouncedQuery]);

  const formatContextLength = (length?: number) => {
    if (!length) return null;
    if (length >= 1000) return `${Math.round(length / 1000)}k`;
    return length.toString();
  };

  const getModelTags = (item: ModelConfig) => {
    const tags: { text: string; color: string; bg: string }[] = [];

    // Reasoning Tag
    if (item.type === 'reasoning' || item.capabilities?.reasoning) {
      tags.push({ text: 'Reasoning', color: '#7c3aed', bg: '#f5f3ff' }); // Violet
    }

    // Vision Tag
    if (item.type === 'image' || item.capabilities?.vision) {
      tags.push({ text: 'Vision', color: '#db2777', bg: '#fdf2f8' }); // Pink
    }

    // Web/Internet Tag
    if (item.capabilities?.internet) {
      tags.push({ text: 'Web', color: '#0ea5e9', bg: '#e0f2fe' }); // Sky Blue
    }

    // Rerank Model Tag
    if (item.type === 'rerank') {
      tags.push({ text: 'Rerank', color: '#ea580c', bg: '#ffedd5' }); // Orange
    }

    // Embedding Model Tag
    if (item.type === 'embedding') {
      tags.push({ text: 'Embedding', color: '#0891b2', bg: '#cffafe' }); // Cyan
    }

    // Default 'Chat' tag only if no other specific capability tags exist and it's not a text processing model
    if (tags.length === 0 && item.type !== 'rerank' && item.type !== 'embedding') {
      tags.push({ text: 'Chat', color: '#059669', bg: '#ecfdf5' }); // Emerald
    }

    // Context Length Tag (Always shown if available)
    const contextStr = formatContextLength(item.contextLength);
    if (contextStr) {
      tags.push({ text: contextStr, color: '#2563eb', bg: '#eff6ff' }); // Blue
    }

    return tags;
  };

  const renderItem = ({ item }: { item: ModelConfig & { providerName: string } }) => {
    const isSelected = item.uuid === selectedUuid;
    const tags = getModelTags(item);

    return (
      <TouchableOpacity
        onPress={() => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onSelect(item.uuid);
            onClose();
          }, 10);
        }}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          padding: 10,
          paddingHorizontal: 12,
          backgroundColor:
            theme === 'dark'
              ? isSelected
                ? colors.opacity20
                : 'rgba(255, 255, 255, 0.03)'
              : isSelected
                ? colors.opacity10
                : 'rgba(0, 0, 0, 0.02)',
          borderRadius: 14,
          marginBottom: 6,
          borderWidth: 1,
          borderColor: isSelected
            ? colors.opacity30
            : isDark
              ? 'rgba(255, 255, 255, 0.05)'
              : 'rgba(0, 0, 0, 0.03)',
          minHeight: 76, // Unified Height Baseline
        }}
      >
        <View
          style={{
            width: 36,
            height: 36,
            borderRadius: 10,
            backgroundColor: isSelected ? '#fff' : isDark ? '#27272a' : '#f3f4f6',
            alignItems: 'center',
            justifyContent: 'center',
            marginRight: 12,
            borderWidth: isSelected ? 2 : 0,
            borderColor: colors[500],
          }}
        >
          <ModelIconRenderer
            icon={findModelSpec(item.id)?.icon || findModelSpec(item.name)?.icon}
            size={22}
            color={isSelected ? colors[500] : '#6b7280'}
          />
        </View>

        <View style={{ flex: 1 }}>
          <Text
            style={{
              fontSize: 15,
              fontWeight: isSelected ? '700' : '600',
              color: isDark ? '#fff' : '#111',
            }}
          >
            {item.name}
          </Text>

          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              marginTop: 4,
              flexWrap: 'wrap',
              gap: 6,
            }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', marginRight: 4 }}>
              <Server size={10} color="#9ca3af" />
              <Text style={{ fontSize: 11, color: '#9ca3af', marginLeft: 3 }}>
                {item.providerName}
              </Text>
            </View>

            {tags.map((tag, index) => (
              <View
                key={index}
                style={{
                  backgroundColor: isDark ? tag.color + '15' : tag.color + '10',
                  paddingHorizontal: 5,
                  paddingVertical: 1,
                  borderRadius: 4,
                  borderWidth: 0.5,
                  borderColor: tag.color + '30',
                }}
              >
                <Text
                  style={{
                    fontSize: 9,
                    fontWeight: '700',
                    color: tag.color,
                  }}
                >
                  {tag.text}
                </Text>
              </View>
            ))}
          </View>
        </View>
        {isSelected && (
          <Animated.View entering={FadeIn}>
            <Check size={18} color={colors[500]} />
          </Animated.View>
        )}
      </TouchableOpacity>
    );
  };

  return (
    <GlassBottomSheet
      visible={visible}
      onClose={onClose}
      title={title}
      subtitle={t.settings.modelPresets.select}
    >
      <View style={{ paddingHorizontal: 20, marginBottom: 20 }}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            backgroundColor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.04)',
            borderRadius: 20,
            paddingHorizontal: 16,
            height: 48,
            borderWidth: 0.5,
            borderColor: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.08)',
          }}
        >
          <Search size={16} color="#9ca3af" />
          <TextInput
            placeholder={t.settings.modelSettings.searchPlaceholder}
            placeholderTextColor="#9ca3af"
            value={searchQuery}
            onChangeText={handleSearchChange}
            style={{ flex: 1, marginLeft: 10, fontSize: 16, color: isDark ? '#fff' : '#111' }}
          />
        </View>
      </View>

      <View style={{ flex: 1 }}>
        {allModels.length === 0 ? (
          <View
            style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 40 }}
          >
            <Cpu size={40} color="#9ca3af" style={{ opacity: 0.3, marginBottom: 16 }} />
            <Text style={{ fontSize: 14, color: '#9ca3af', textAlign: 'center' }}>
              暂无可用模型，请先配置服务商。
            </Text>
          </View>
        ) : (
          <TypedFlashList
            data={filteredModels}
            renderItem={renderItem}
            estimatedItemSize={64}
            keyExtractor={(item: any) => item.uuid}
            contentContainerStyle={{ paddingHorizontal: 12, paddingBottom: 40 }}
          />
        )}
      </View>
    </GlassBottomSheet>
  );
};

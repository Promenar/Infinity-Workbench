import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { View, TouchableOpacity, Text, Modal, TextInput, ActivityIndicator, BackHandler, StyleSheet, FlatList } from 'react-native';
import { PageLayout, Typography, LargeTitleHeader, AnimatedSearchBar } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { Search, Plus, ChevronRight } from 'lucide-react-native';
import * as Haptics from '../../src/lib/haptics';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';
import { BlurView } from 'expo-blur';
import { useAgentStore } from '../../src/store/agent-store';
import { useChatStore } from '../../src/store/chat-store';
import { Agent } from '../../src/types/chat';
import { AgentAvatar } from '../../src/components/chat/AgentAvatar';
import { useSettingsStore } from '../../src/store/settings-store';
import { Keyboard } from 'react-native';
import { preventDoubleTap } from '../../src/lib/navigation-utils';
import { SuperAssistantFAB } from '../../src/components/chat/SuperAssistantFAB';
import { SwipeableAgentItem } from '../../src/components/chat/SwipeableAgentItem';
import { ConfirmDialog } from '../../src/components/ui/ConfirmDialog';
import { Colors } from '../../src/theme/colors';

export default function AgentExplorerScreen() {
  const router = useRouter();
  const { t } = useI18n();
  const { isDark, colors } = useTheme();
  const { agents } = useAgentStore();
  // Filter out super_assistant - it's only accessible via floating button
  const displayAgents = agents.filter((a) => a.id !== 'super_assistant');
  const [searchQuery, setSearchQuery] = useState('');

  const filteredAgents = useMemo(() => {
    let result = displayAgents;
    if (searchQuery) {
      result = displayAgents.filter(
        (a) =>
          a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
          a.description.toLowerCase().includes(searchQuery.toLowerCase()),
      );
    }

    // Sort by pinned first, then by creation date (newest first)
    return [...result].sort((a, b) => {
      if (a.isPinned && !b.isPinned) return -1;
      if (!a.isPinned && b.isPinned) return 1;
      return b.created - a.created;
    });
  }, [displayAgents, searchQuery]);

  const [agentToConfirmDelete, setAgentToConfirmDelete] = useState<Agent | null>(null);

  const togglePin = (id: string) => {
    useAgentStore.getState().togglePinAgent(id);
  };

  const handleDeletePress = (agent: Agent) => {
    setAgentToConfirmDelete(agent);
  };

  const confirmDelete = () => {
    if (agentToConfirmDelete) {
      useAgentStore.getState().deleteAgent(agentToConfirmDelete.id);
      setAgentToConfirmDelete(null);
    }
  };

  const handleCreateAgent = () => {
    const newId = `agent_${Date.now()}`;
    const globalRagConfig = useSettingsStore.getState().globalRagConfig;
    const newAgent = {
      id: newId,
      name: 'New Assistant',
      description: 'Define your new partner...',
      avatar: 'Sparkles',
      color: '#6366f1',
      systemPrompt: 'You are a helpful assistant.',
      defaultModel: 'gpt-4o',
      params: { temperature: 0.7 },
      ragConfig: { ...globalRagConfig }, // ✅ 显式复制全局 RAG 配置
      created: Date.now(),
    };
    // @ts-ignore
    useAgentStore.getState().addAgent(newAgent);
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      router.push(`/chat/agent/edit/${newId}`);
    }, 10);
  };

  const renderItem = ({ item, index }: { item: Agent; index: number }) => {
    return (
      <Animated.View entering={FadeInDown.delay(index * 50).duration(400)}>
        <SwipeableAgentItem
          item={item}
          isDark={isDark}
          onPress={() => {
            preventDoubleTap(() => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                router.push(`/chat/agent/${item.id}`);
              }, 10);
            });
          }}
          onPin={() => togglePin(item.id)}
          onDelete={() => handleDeletePress(item)}
        />
      </Animated.View>
    );
  };

  const inputRef = React.useRef<TextInput>(null);

  useEffect(() => {
    const keyboardDidHideListener = Keyboard.addListener('keyboardDidHide', () => {
      inputRef.current?.blur();
    });
    return () => {
      keyboardDidHideListener.remove();
    };
  }, []);

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      {/* Fixed Title Header */}
      <LargeTitleHeader
        title={t.chat.title}
        subtitle={t.chat.subtitle}
        rightElement={
          <TouchableOpacity
            onPress={handleCreateAgent}
            style={{
              width: 48,
              height: 48,
              backgroundColor: isDark ? 'rgba(15, 17, 26, 0.4)' : colors[50],
              borderWidth: 1,
              borderColor: isDark ? 'rgba(99, 102, 241, 0.15)' : colors[200],
              borderRadius: 16,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Plus size={24} color={colors[500]} strokeWidth={2.5} />
          </TouchableOpacity>
        }
      />

      {/* 固定搜索栏：不跟随列表滚动 */}
      <View style={{ paddingHorizontal: 24, paddingBottom: 12 }}>
        <AnimatedSearchBar
          value={searchQuery}
          onChangeText={setSearchQuery}
          placeholder={t.chat.searchPlaceholder}
          inputRef={inputRef}
        />
      </View>

      <FlatList
        data={filteredAgents}
        renderItem={renderItem}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ paddingBottom: 160 }}
        keyboardDismissMode="on-drag"
        keyboardShouldPersistTaps="handled"
        onScrollBeginDrag={() => {
          Keyboard.dismiss();
          inputRef.current?.blur();
        }}
        ItemSeparatorComponent={() => (
          <View
            style={{
              height: 1,
              backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#f9fafb', // Using lighter gray for light mode to match design
              marginHorizontal: 16, // Reduced 24 -> 16
            }}
          />
        )}
      />

      <SuperAssistantFAB
        onPress={() => {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);

          // Super Assistant uses a persistent single session
          const spaSessionId = 'super_assistant';
          const chatStore = useChatStore.getState();
          const existingSession = chatStore.getSession(spaSessionId);

          if (!existingSession) {
            const newSession = {
              id: spaSessionId,
              agentId: 'super_assistant',
              title: 'Super Personal Assistant',
              lastMessage: 'Ready to help with global context.',
              time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
              unread: 0,
              messages: [],
              options: {
                reasoning: true,
                webSearch: false,
              },
              ragOptions: {
                enableKnowledgeGraph: false, // ✅ 超级助手默认也关闭图谱抽取
              },
            };
            chatStore.addSession(newSession as any);
          }

          setTimeout(() => {
            router.push(`/chat/${spaSessionId}`);
          }, 50);
        }}
      />

      <ConfirmDialog
        visible={!!agentToConfirmDelete}
        title={t.agent?.deleteConfirmTitle || '确认删除'}
        message={`${t.agent?.deleteConfirmMessage || '确定要删除此对话助手吗？'}\n"${agentToConfirmDelete?.name}"`}
        confirmText={t.common?.delete || '删除'}
        cancelText={t.common?.cancel || '取消'}
        isDestructive
        onConfirm={confirmDelete}
        onCancel={() => setAgentToConfirmDelete(null)}
      />
    </PageLayout>
  );
}

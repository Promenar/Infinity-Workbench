import React, { useMemo, useState, useCallback } from 'react';
import { View, TouchableOpacity, TextInput, Keyboard, FlatList } from 'react-native';
import { PageLayout, Typography, GlassHeader, AnimatedSearchBar } from '../../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { MessageSquare, ChevronLeft, Plus, Settings2, Search } from 'lucide-react-native';
import { BlurView } from 'expo-blur';
import * as Haptics from '../../../src/lib/haptics';
import { useAgentStore } from '../../../src/store/agent-store';
import { useChatStore } from '../../../src/store/chat-store';
import { Session } from '../../../src/types/chat';
import { useI18n } from '../../../src/lib/i18n';
import { useTheme } from '../../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Colors } from '../../../src/theme/colors';
import { SwipeableSessionItem } from '../../../src/features/chat/components/SwipeableSessionItem';

const ITEM_HEIGHT = 72;

export default function AgentSessionsScreen() {
  const { agentId } = useLocalSearchParams<{ agentId: string }>();
  const router = useRouter();
  const { t } = useI18n();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { getAgent } = useAgentStore();
  const { getSessionsByAgent, addSession, deleteSession, toggleSessionPin } = useChatStore();

  const allSessions = useChatStore((state) => state.sessions);

  const agent = useMemo(() => getAgent(agentId), [agentId, getAgent]);
  const sessions = useMemo(
    () => getSessionsByAgent(agentId),
    [agentId, allSessions, getSessionsByAgent],
  );

  const [searchQuery, setSearchQuery] = useState('');

  const filteredSessions = useMemo(() => {
    if (!searchQuery) return sessions;
    const lowerQuery = searchQuery.toLowerCase();
    return sessions.filter(
      (s) =>
        s.title.toLowerCase().includes(lowerQuery) ||
        s.lastMessage.toLowerCase().includes(lowerQuery)
    );
  }, [sessions, searchQuery]);

  const handleCreateSession = useCallback(() => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    const newSession: Session = {
      id: `session_${Date.now()}`,
      agentId: agentId,
      title: t.agent.newConversation,
      lastMessage: 'Starting...',
      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      unread: 0,
      messages: [],
      executionMode: 'semi',
      loopStatus: 'idle',
      ragOptions: {
        enableKnowledgeGraph: false,
      },
    };
    addSession(newSession);
    setTimeout(() => {
      router.push(`/chat/${newSession.id}`);
    }, 10);
  }, [agentId, t.agent.newConversation, addSession, router]);

  const renderItem = useCallback(({ item }: { item: Session }) => (
    <SwipeableSessionItem
      item={item}
      onPress={() => {
        setTimeout(() => {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
          router.push(`/chat/${item.id}`);
        }, 10);
      }}
      onPin={() => {
        toggleSessionPin(item.id);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      }}
      onDelete={() => {
        deleteSession(item.id);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
      }}
      agentId={agentId}
      agentAvatar={agent?.avatar}
      agentColor={agent?.color || '#6366f1'}
      isDark={isDark}
    />
  ), [agentId, agent?.avatar, agent?.color, isDark, toggleSessionPin, deleteSession, router]);

  const getItemLayout = useCallback((_: any, index: number) => ({
    length: ITEM_HEIGHT,
    offset: ITEM_HEIGHT * index,
    index,
  }), []);

  const inputRef = React.useRef<TextInput>(null);

  React.useEffect(() => {
    const keyboardDidHideListener = Keyboard.addListener('keyboardDidHide', () => {
      inputRef.current?.blur();
    });
    return () => {
      keyboardDidHideListener.remove();
    };
  }, []);

  if (!agent) return null;

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={agent.name}
        subtitle={`${sessions.length} ${t.agent.conversations}`}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
        rightAction={{
          icon: <Settings2 size={20} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.push(`/chat/agent/edit/${agentId}`),
          label: t.common.settings,
        }}
      />

      {/* 固定搜索栏：不跟随列表滚动 */}
      {/* 🔑 布局修复：GlassHeader 是 absolute 定位，容器必须预留 header 高度 (64 + insets.top) */}
      <View style={{
        paddingHorizontal: 24,
        paddingTop: 64 + insets.top + 8,
        paddingBottom: 8
      }}>
        <AnimatedSearchBar
          value={searchQuery}
          onChangeText={setSearchQuery}
          placeholder={t.chat.searchSessionPlaceholder || 'Search conversations...'}
          inputRef={inputRef}
        />
      </View>

      <FlatList
        data={filteredSessions}
        renderItem={renderItem}
        keyExtractor={(item) => item.id}
        getItemLayout={getItemLayout}
        removeClippedSubviews={true}
        maxToRenderPerBatch={8}
        windowSize={5}
        initialNumToRender={10}
        keyboardDismissMode="on-drag"
        keyboardShouldPersistTaps="handled"
        onScrollBeginDrag={() => {
          Keyboard.dismiss();
          inputRef.current?.blur();
        }}
        contentContainerStyle={{
          paddingBottom: 110,
        }}
        ListEmptyComponent={
          <View className="flex-1 items-center justify-center pt-20 px-10">
            <View className="w-20 h-20 rounded-full bg-gray-50/80 dark:bg-zinc-900/60 items-center justify-center mb-6">
              <MessageSquare size={32} color="#cbd5e1" />
            </View>
            <Typography className="text-gray-900 dark:text-white font-bold text-center mb-2">
              {t.agent.noHistory}
            </Typography>
            <Typography className="text-gray-400 text-center text-sm leading-5">
              {t.agent.noHistoryDesc}
            </Typography>
          </View>
        }
        ItemSeparatorComponent={() => (
          <View
            style={{
              height: 1,
              backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#f3f4f6',
              marginHorizontal: 16,
            }}
          />
        )}
      />

      {/* Floating Action Button */}
      <TouchableOpacity
        onPress={() => {
          setTimeout(() => {
            handleCreateSession();
          }, 10);
        }}
        className="absolute bottom-8 right-6 w-16 h-16 rounded-full shadow-xl items-center justify-center"
        style={{ backgroundColor: agent.color, shadowColor: agent.color }}
      >
        <Plus size={32} color="white" strokeWidth={2.5} />
      </TouchableOpacity>
    </PageLayout>
  );
}

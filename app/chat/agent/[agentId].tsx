import React, { useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { PageLayout, Typography, GlassHeader } from '../../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import { MessageSquare, ChevronLeft, Plus, Settings2 } from 'lucide-react-native';
import * as Haptics from '../../../src/lib/haptics';
import { useAgentStore } from '../../../src/store/agent-store';
import { useChatStore } from '../../../src/store/chat-store';
import { Session } from '../../../src/types/chat';
import { useI18n } from '../../../src/lib/i18n';
import { useTheme } from '../../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { SwipeableSessionItem } from '../../../src/features/chat/components/SwipeableSessionItem';

export default function AgentSessionsScreen() {
  const { agentId } = useLocalSearchParams<{ agentId: string }>();
  const router = useRouter();
  const { t } = useI18n();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { getAgent } = useAgentStore();
  const { getSessionsByAgent, addSession, deleteSession, toggleSessionPin } = useChatStore();

  // Subscribe to sessions to trigger re-renders when they change
  const allSessions = useChatStore((state) => state.sessions);

  const agent = useMemo(() => getAgent(agentId), [agentId, getAgent]);
  const sessions = useMemo(
    () => getSessionsByAgent(agentId),
    [agentId, allSessions, getSessionsByAgent],
  );

  if (!agent) return null;

  const handleCreateSession = () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    const newSession: Session = {
      id: `session_${Date.now()}`,
      agentId: agentId,
      title: t.agent.newConversation,
      lastMessage: 'Starting...',
      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      unread: 0,
      messages: [],
    };
    addSession(newSession);
    setTimeout(() => {
      router.push(`/chat/${newSession.id}`);
    }, 10);
  };

  const renderItem = ({ item }: { item: Session }) => (
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
      agentAvatar={agent.avatar}
      agentColor={agent.color}
      isDark={isDark}
    />
  );

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

      <FlashList
        data={sessions}
        renderItem={renderItem}
        keyExtractor={(item) => item.id}
        // @ts-ignore
        estimatedItemSize={90}
        contentContainerStyle={{
          paddingTop: 84 + insets.top,
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
        ItemSeparatorComponent={() => null}
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

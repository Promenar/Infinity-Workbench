import React, { useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { PageLayout, Typography, Header } from '../../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import { MessageSquare, ChevronLeft, Plus, Settings2 } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { useAgentStore } from '../../../src/store/agent-store';
import { useChatStore } from '../../../src/store/chat-store';
import { Session } from '../../../src/types/chat';

export default function AgentSessionsScreen() {
    const { agentId } = useLocalSearchParams<{ agentId: string }>();
    const router = useRouter();
    const { getAgent } = useAgentStore();
    const { getSessionsByAgent, addSession } = useChatStore();

    const agent = useMemo(() => getAgent(agentId), [agentId]);
    const sessions = useMemo(() => getSessionsByAgent(agentId), [agentId]);

    const handleCreateSession = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        const newSession: Session = {
            id: `session_${Date.now()}`,
            agentId: agentId,
            title: 'New Conversation',
            lastMessage: 'Starting a new chat...',
            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
            unread: 0,
            messages: []
        };
        addSession(newSession);
        router.push(`/chat/${newSession.id}`);
    };

    const renderItem = ({ item }: { item: Session }) => (
        <TouchableOpacity
            activeOpacity={0.7}
            className="flex-row items-center px-6 py-4 bg-white dark:bg-black w-full"
            onPress={() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                router.push(`/chat/${item.id}`);
            }}
        >
            <View className="w-[52px] h-[52px] rounded-2xl items-center justify-center bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 mr-4">
                <MessageSquare size={24} color={agent?.color || '#64748b'} strokeWidth={1.5} />
            </View>

            <View className="flex-1 justify-center py-1">
                <View className="flex-row justify-between items-baseline mb-1 pr-1">
                    <Typography variant="h3" className="text-[17px] font-bold text-gray-900 dark:text-gray-100 leading-tight" numberOfLines={1}>{item.title}</Typography>
                    <Typography variant="caption" className="text-gray-400 text-[11px] font-medium">{item.time}</Typography>
                </View>
                <Typography variant="body" className="text-gray-500 leading-5 text-[13px]" numberOfLines={2}>
                    {item.lastMessage}
                </Typography>
            </View>
        </TouchableOpacity>
    );

    if (!agent) return null;

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />

            <Header
                title={agent.name}
                subtitle={`${sessions.length} CONVERSATIONS`}
                leftAction={
                    <TouchableOpacity onPress={() => router.back()} className="p-2 -ml-2">
                        <ChevronLeft size={24} color="#64748b" />
                    </TouchableOpacity>
                }
                rightAction={
                    <TouchableOpacity onPress={() => router.push(`/chat/agent/edit/${agentId}`)} className="p-2 -mr-2">
                        <Settings2 size={22} color="#64748b" />
                    </TouchableOpacity>
                }
            />

            <FlashList
                data={sessions}
                renderItem={renderItem}
                keyExtractor={item => item.id}
                // @ts-ignore
                estimatedItemSize={90}
                contentContainerStyle={{ paddingBottom: 100 }}
                ListEmptyComponent={
                    <View className="flex-1 items-center justify-center pt-20 px-10">
                        <View className="w-20 h-20 rounded-full bg-gray-50 dark:bg-zinc-900 items-center justify-center mb-6">
                            <MessageSquare size={32} color="#cbd5e1" />
                        </View>
                        <Typography className="text-gray-900 dark:text-white font-bold text-center mb-2">No history yet</Typography>
                        <Typography className="text-gray-400 text-center text-sm leading-5">Start a new conversation with {agent.name} to see it here.</Typography>
                    </View>
                }
                ItemSeparatorComponent={() => <View className="h-[1px] bg-gray-50 dark:bg-zinc-900/50 mx-6" />}
            />

            {/* Floating Action Button */}
            <TouchableOpacity
                onPress={handleCreateSession}
                className="absolute bottom-8 right-6 w-16 h-16 rounded-full shadow-xl items-center justify-center"
                style={{ backgroundColor: agent.color, shadowColor: agent.color }}
            >
                <Plus size={32} color="white" strokeWidth={2.5} />
            </TouchableOpacity>
        </PageLayout>
    );
}

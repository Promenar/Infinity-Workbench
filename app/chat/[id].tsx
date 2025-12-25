import React, { useRef, useMemo } from 'react';
import { View, KeyboardAvoidingView, Platform, TouchableOpacity } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { PageLayout, Typography, Header } from '../../src/components/ui';
import { ChatBubble, ChatInput, useChat } from '../../src/features/chat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft, Info } from 'lucide-react-native';
import { useChatStore } from '../../src/store/chat-store';
import { useAgentStore } from '../../src/store/agent-store';

export default function ChatDetailScreen() {
    const { id } = useLocalSearchParams<{ id: string }>();
    const router = useRouter();
    const { getSession } = useChatStore();
    const { getAgent } = useAgentStore();

    const session = useMemo(() => getSession(id), [id]);
    const agent = useMemo(() => session ? getAgent(session.agentId) : undefined, [session]);

    // @ts-ignore
    const { messages, sendMessage, loading } = useChat(id);
    const listRef = useRef<any>(null);

    const handleContentSizeChange = () => {
        listRef.current?.scrollToEnd({ animated: true });
    };

    if (!session || !agent) return null;

    return (
        <PageLayout safeArea={false} className="bg-surface-secondary dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />
            <View style={{ flex: 1 }}>

                <Header
                    title={agent.name}
                    subtitle={agent.defaultModel.toUpperCase()}
                    leftAction={
                        <TouchableOpacity
                            onPress={() => router.back()}
                            className="p-2 -ml-2"
                        >
                            <ChevronLeft size={24} color="#64748b" />
                        </TouchableOpacity>
                    }
                    rightAction={
                        <TouchableOpacity
                            className="w-10 h-10 items-center justify-center rounded-full bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800"
                            onPress={() => { }}
                        >
                            <Info size={20} color={agent.color} />
                        </TouchableOpacity>
                    }
                />

                <KeyboardAvoidingView
                    behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
                    style={{ flex: 1 }}
                    keyboardVerticalOffset={0}
                >
                    <FlashList
                        ref={listRef}
                        data={messages}
                        renderItem={({ item }) => <ChatBubble message={item} />}
                        // @ts-ignore
                        estimatedItemSize={100}
                        contentContainerStyle={{ padding: 16, paddingBottom: 20 }}
                        onContentSizeChange={handleContentSizeChange}
                        keyboardDismissMode="on-drag"
                        style={{ flex: 1 }}
                    />
                    <View style={{ paddingBottom: 10 }}>
                        <ChatInput onSend={sendMessage} disabled={loading} />
                    </View>
                </KeyboardAvoidingView>

            </View>
        </PageLayout>
    );
}

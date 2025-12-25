import React, { useRef } from 'react';
import { View, KeyboardAvoidingView, Platform, TouchableOpacity } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { PageLayout, Typography, Header, useToast } from '../../src/components/ui';
import { ChatBubble, ChatInput, useChat } from '../../src/features/chat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { BookOpen, MessageSquare, ChevronLeft } from 'lucide-react-native';

import { MOCK_CONVERSATIONS } from '../../src/data/mock';

export default function ChatDetailScreen() {
    const { id } = useLocalSearchParams();
    const router = useRouter();
    const { messages, sendMessage, mode, setMode } = useChat();
    const listRef = useRef<any>(null);
    const insets = useSafeAreaInsets();

    const conversation = MOCK_CONVERSATIONS.find(c => c.id === id);
    const title = conversation ? conversation.title : `Chat ${id}`;

    // Auto scroll to bottom
    const handleContentSizeChange = () => {
        listRef.current?.scrollToEnd({ animated: true });
    };

    const toggleMode = () => {
        setMode(mode === 'chat' ? 'writer' : 'chat');
    };

    return (
        <PageLayout safeArea={false} className="bg-surface-secondary dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />
            <View style={{ flex: 1 }}>

                <Header
                    title={title}
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
                            className="flex-row items-center bg-surface-secondary dark:bg-slate-800 px-3 py-1.5 rounded-full border border-border-default dark:border-slate-700 active:bg-surface-tertiary"
                            onPress={toggleMode}
                        >
                            {mode === 'chat' ? (
                                <>
                                    <MessageSquare size={16} color="#6366f1" />
                                    <Typography variant="label" className="ml-2 font-bold text-primary-500">CHAT</Typography>
                                </>
                            ) : (
                                <>
                                    <BookOpen size={16} color="#ec4899" />
                                    <Typography variant="label" className="ml-2 font-bold text-pink-500">WRITER</Typography>
                                </>
                            )}
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
                    {/* Fixed padding since no tab bar */}
                    <View style={{ paddingBottom: 10 }}>
                        <ChatInput onSend={sendMessage} />
                    </View>
                </KeyboardAvoidingView>

            </View>
        </PageLayout>
    );
}

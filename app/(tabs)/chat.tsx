import React, { useRef } from 'react';
import { View, KeyboardAvoidingView, Platform, TouchableOpacity, Keyboard } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { PageLayout, Typography, Header, useToast } from '../../src/components/ui';
import { ChatBubble, ChatInput, useChat, Message } from '../../src/features/chat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Stack } from 'expo-router';
import { BookOpen, MessageSquare } from 'lucide-react-native';

export default function ChatScreen() {
    const { messages, sendMessage, mode, setMode } = useChat();
    const listRef = useRef<any>(null);
    const insets = useSafeAreaInsets();
    const [keyboardVisible, setKeyboardVisible] = React.useState(false);
    const { showToast } = useToast();

    const handleMenu = () => showToast("Session History\nComing Soon", "info"); // Placeholder for now

    React.useEffect(() => {
        const showSubscription = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow', () => setKeyboardVisible(true));
        const hideSubscription = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide', () => setKeyboardVisible(false));
        return () => {
            showSubscription.remove();
            hideSubscription.remove();
        };
    }, []);

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
                    title="NeuralFlow"
                    showMenu
                    onMenuPress={handleMenu}
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
                    <View style={{ paddingBottom: 65 }}>
                        <ChatInput onSend={sendMessage} />
                    </View>
                </KeyboardAvoidingView>

            </View>
        </PageLayout>
    );
}

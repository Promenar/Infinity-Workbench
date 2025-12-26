import React, { useRef, useMemo } from 'react';
import { View, Platform, TouchableOpacity } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { FlashList } from '@shopify/flash-list';
import { PageLayout, Typography, Header } from '../../src/components/ui';
import { ChatBubble, ChatInput, useChat } from '../../src/features/chat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft, Info } from 'lucide-react-native';
import { useChatStore } from '../../src/store/chat-store';
import { useAgentStore } from '../../src/store/agent-store';
import { useApiStore } from '../../src/store/api-store';
import { useTheme } from '../../src/theme/ThemeProvider';
import { ModelPicker } from '../../src/features/settings/ModelPicker';
import * as Haptics from 'expo-haptics';

export default function ChatDetailScreen() {
    const { id } = useLocalSearchParams<{ id: string }>();
    const router = useRouter();
    const { isDark } = useTheme();
    const { getSession, updateSession } = useChatStore();
    const { getAgent } = useAgentStore();
    const { providers } = useApiStore();

    const session = useMemo(() => getSession(id), [id]);
    const agent = useMemo(() => session ? getAgent(session.agentId) : undefined, [session]);

    const [showModelPicker, setShowModelPicker] = React.useState(false);

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
                    subtitle={(() => {
                        const modelId = session.modelId || agent.defaultModel;
                        for (const p of providers) {
                            const m = p.models.find(m => m.uuid === modelId || m.id === modelId);
                            if (m) return m.name.toUpperCase();
                        }
                        return modelId.split('/').pop()?.toUpperCase();
                    })()}
                    onTitlePress={() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                        setShowModelPicker(true);
                    }}
                    leftAction={
                        <TouchableOpacity
                            onPress={() => router.back()}
                            className="p-2 -ml-2"
                        >
                            <ChevronLeft size={24} color={isDark ? '#94a3b8' : '#64748b'} />
                        </TouchableOpacity>
                    }
                    rightAction={
                        <TouchableOpacity
                            className="w-10 h-10 items-center justify-center rounded-xl bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800"
                            onPress={() => { }}
                        >
                            <Info size={20} color={agent.color} strokeWidth={2.5} />
                        </TouchableOpacity>
                    }
                />


                <KeyboardAvoidingView
                    behavior={Platform.OS === 'ios' ? 'padding' : undefined}
                    style={{ flex: 1 }}
                >
                    <FlashList
                        ref={listRef}
                        data={messages}
                        renderItem={({ item }) => <ChatBubble message={item} agent={agent} />}
                        // @ts-ignore
                        estimatedItemSize={120}
                        contentContainerStyle={{ paddingVertical: 16 }}
                        onContentSizeChange={handleContentSizeChange}
                        keyboardDismissMode="on-drag"
                        style={{ flex: 1 }}
                    />
                    <View className="bg-surface-secondary dark:bg-black px-4 pb-4 pt-1">
                        <ChatInput onSend={sendMessage} disabled={loading} />
                    </View>
                </KeyboardAvoidingView>

                <ModelPicker
                    visible={showModelPicker}
                    onClose={() => setShowModelPicker(false)}
                    onSelect={(uuid) => {
                        updateSession(id, { modelId: uuid });
                    }}
                    selectedUuid={session.modelId || agent.defaultModel}
                    title="Switch Model"
                    filterType="chat"
                />
            </View>
        </PageLayout>
    );
}

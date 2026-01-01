import React, { useState, useMemo } from 'react';
import { View, TouchableOpacity, Text, TextInput } from 'react-native';
import { PageLayout, Typography, LargeTitleHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { Search, Plus, ChevronRight } from 'lucide-react-native';
import * as Haptics from '../../src/lib/haptics';
import { FlashList } from '@shopify/flash-list';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useAgentStore } from '../../src/store/agent-store';
import { useChatStore } from '../../src/store/chat-store';
import { Agent } from '../../src/types/chat';
import { AgentAvatar } from '../../src/components/chat/AgentAvatar';
import { useSettingsStore } from '../../src/store/settings-store';
import { preventDoubleTap } from '../../src/lib/navigation-utils';
import { SuperAssistantFAB } from '../../src/components/chat/SuperAssistantFAB';
import { SwipeableAgentItem } from '../../src/components/chat/SwipeableAgentItem';
import { ConfirmDialog } from '../../src/components/ui/ConfirmDialog';
import { Colors } from '../../src/theme/colors';

export default function AgentExplorerScreen() {
    const router = useRouter();
    const { t } = useI18n();
    const { isDark } = useTheme();
    const { agents } = useAgentStore();
    // Filter out super_assistant - it's only accessible via floating button
    const displayAgents = agents.filter(a => a.id !== 'super_assistant');
    const [searchQuery, setSearchQuery] = useState('');

    const filteredAgents = useMemo(() => {
        let result = displayAgents;
        if (searchQuery) {
            result = displayAgents.filter(a =>
                a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
                a.description.toLowerCase().includes(searchQuery.toLowerCase())
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
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
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
        router.push(`/chat/agent/edit/${newId}`);
    };

    const renderItem = ({ item, index }: { item: Agent, index: number }) => {
        return (
            <Animated.View entering={FadeInDown.delay(index * 50).duration(400)}>
                <SwipeableAgentItem
                    item={item}
                    isDark={isDark}
                    onPress={() => {
                        preventDoubleTap(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                            router.push(`/chat/agent/${item.id}`);
                        });
                    }}
                    onPin={() => togglePin(item.id)}
                    onDelete={() => handleDeletePress(item)}
                />
            </Animated.View>
        );
    };

    const ListHeader = () => (
        <View className="px-6 pb-6 bg-white dark:bg-black">
            {/* Flat Modern Search Bar */}
            <View className="flex-row items-center bg-gray-50 dark:bg-zinc-900 px-4 h-12 rounded-2xl border border-gray-100 dark:border-zinc-800">
                <Search size={18} color="#94a3b8" />
                <TextInput
                    className="flex-1 ml-3 text-gray-900 dark:text-gray-100 font-medium text-[14px] p-0"
                    placeholder={t.chat.searchPlaceholder}
                    placeholderTextColor="#94a3b8"
                    value={searchQuery}
                    onChangeText={setSearchQuery}
                    autoCorrect={false}
                    clearButtonMode="while-editing"
                />
            </View>
        </View>
    );

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
                            backgroundColor: isDark ? '#18181b' : '#eef2ff',
                            borderWidth: 1,
                            borderColor: isDark ? '#27272a' : '#e0e7ff',
                            borderRadius: 16,
                            alignItems: 'center',
                            justifyContent: 'center'
                        }}
                    >
                        <Plus size={24} color={Colors.primary} strokeWidth={2.5} />
                    </TouchableOpacity>
                }
            />

            <FlashList
                data={filteredAgents}
                renderItem={renderItem}
                keyExtractor={item => item.id}
                ListHeaderComponent={ListHeader}
                // @ts-ignore
                estimatedItemSize={90}
                contentContainerStyle={{ paddingBottom: 160 }}
                ItemSeparatorComponent={() => (
                    <View style={{
                        height: 1,
                        backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#f9fafb', // Using lighter gray for light mode to match design
                        marginHorizontal: 24
                    }} />
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
                                webSearch: false
                            }
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
                title={t.agent?.deleteConfirmTitle || "确认删除"}
                message={`${t.agent?.deleteConfirmMessage || "确定要删除此对话助手吗？"}\n"${agentToConfirmDelete?.name}"`}
                confirmText={t.common?.delete || "删除"}
                cancelText={t.common?.cancel || "取消"}
                isDestructive
                onConfirm={confirmDelete}
                onCancel={() => setAgentToConfirmDelete(null)}
            />
        </PageLayout>
    );
}

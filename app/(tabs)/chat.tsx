import React, { useState, useMemo } from 'react';
import { View, TouchableOpacity, Text } from 'react-native';
import { PageLayout, Typography } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { Search, Plus, ChevronRight } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { FlashList } from '@shopify/flash-list';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { useI18n } from '../../src/lib/i18n';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useAgentStore } from '../../src/store/agent-store';
import { Agent } from '../../src/types/chat';
import { AgentAvatar } from '../../src/components/chat/AgentAvatar';

export default function AgentExplorerScreen() {
    const router = useRouter();
    const { t } = useI18n();
    const { isDark } = useTheme();
    const { agents } = useAgentStore();
    const [searchQuery, setSearchQuery] = useState('');

    const filteredAgents = useMemo(() => {
        if (!searchQuery) return agents;
        return agents.filter(a =>
            a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
            a.description.toLowerCase().includes(searchQuery.toLowerCase())
        );
    }, [agents, searchQuery]);

    const handleCreateAgent = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        const newId = `agent_${Date.now()}`;
        const newAgent = {
            id: newId,
            name: 'New Assistant',
            description: 'Define your new partner...',
            avatar: 'Sparkles',
            color: '#6366f1',
            systemPrompt: 'You are a helpful assistant.',
            defaultModel: 'gpt-4o',
            params: { temperature: 0.7 },
            created: Date.now(),
        };
        // @ts-ignore
        useAgentStore.getState().addAgent(newAgent);
        router.push(`/chat/agent/edit/${newId}`);
    };

    const renderItem = ({ item, index }: { item: Agent, index: number }) => {
        return (
            <Animated.View entering={FadeInDown.delay(index * 50).duration(400)}>
                <TouchableOpacity
                    activeOpacity={0.7}
                    className="flex-row items-center px-6 py-4 bg-white dark:bg-black w-full"
                    onPress={() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                        router.push(`/chat/agent/${item.id}`);
                    }}
                >
                    {/* Avatar Container */}
                    <View className="relative mr-4">
                        <AgentAvatar
                            id={item.id}
                            name={item.name}
                            avatar={item.avatar}
                            color={item.color}
                            size={52}
                        />
                    </View>

                    {/* Content */}
                    <View className="flex-1 justify-center py-1">
                        <View className="flex-row justify-between items-baseline mb-1 pr-1">
                            <Typography variant="h3" className="text-[18px] font-bold text-gray-900 dark:text-gray-100 leading-tight">
                                {item.name}
                            </Typography>
                            {item.isPreset && (
                                <View className="bg-indigo-50 dark:bg-indigo-500/10 px-1.5 py-0.5 rounded-md border border-indigo-100 dark:border-indigo-900/30">
                                    <Typography className="text-indigo-600 dark:text-indigo-400 font-bold text-[8px] uppercase tracking-tighter">PRESET</Typography>
                                </View>
                            )}
                        </View>
                        <Typography variant="body" className="text-gray-500 leading-5 text-[13px]" numberOfLines={1}>
                            {item.description}
                        </Typography>
                    </View>

                    <ChevronRight size={18} color="#cbd5e1" className="ml-2" />
                </TouchableOpacity>
            </Animated.View>
        );
    };

    const ListHeader = () => (
        <View className="px-6 pb-6 bg-white dark:bg-black">
            {/* Flat Modern Search Bar */}
            <View className="flex-row items-center bg-gray-50 dark:bg-zinc-900 px-4 h-12 rounded-2xl border border-gray-100 dark:border-zinc-800">
                <Search size={18} color="#94a3b8" />
                <Typography className="ml-3 text-gray-400 font-medium text-[14px]">{t.chat.searchPlaceholder}</Typography>
            </View>
        </View>
    );

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />

            {/* Fixed Title Header */}
            <View style={{ paddingTop: 64, paddingBottom: 8, paddingHorizontal: 24 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', height: 56, marginBottom: 24 }}>
                    <View>
                        <Text style={{ fontSize: 32, fontWeight: '900', color: isDark ? '#fff' : '#111', letterSpacing: -1.5, lineHeight: 32 }}>
                            {t.chat.title}
                        </Text>
                        <Text style={{ fontSize: 11, fontWeight: 'bold', color: '#9ca3af', textTransform: 'uppercase', letterSpacing: 2, marginTop: 4, lineHeight: 11 }}>
                            {t.chat.subtitle}
                        </Text>
                    </View>
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
                        <Plus size={24} color="#6366f1" strokeWidth={2.5} />
                    </TouchableOpacity>
                </View>
            </View>

            <FlashList
                data={filteredAgents}
                renderItem={renderItem}
                keyExtractor={item => item.id}
                ListHeaderComponent={ListHeader}
                // @ts-ignore
                estimatedItemSize={90}
                contentContainerStyle={{ paddingBottom: 100 }}
                ItemSeparatorComponent={() => <View className="h-[1px] bg-gray-50 dark:bg-zinc-900/50 mx-6" />}
            />
        </PageLayout>
    );
}

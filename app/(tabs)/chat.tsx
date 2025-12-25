import React, { useState, useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { PageLayout, Typography } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { Search, Plus, ChevronRight } from 'lucide-react-native';
import * as LucideIcons from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { FlashList } from '@shopify/flash-list';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { useI18n } from '../../src/lib/i18n';
import { useAgentStore } from '../../src/store/agent-store';
import { Agent } from '../../src/types/chat';

export default function AgentExplorerScreen() {
    const router = useRouter();
    const { t } = useI18n();
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
        const IconComponent = (LucideIcons as any)[item.avatar || 'MessageSquare'] || LucideIcons.MessageSquare;

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
                    {/* Avatar with Theme Color */}
                    <View className="relative mr-4">
                        <View
                            className="w-[52px] h-[52px] rounded-2xl items-center justify-center border"
                            style={{ backgroundColor: `${item.color}10`, borderColor: `${item.color}20` }}
                        >
                            <IconComponent size={24} color={item.color} strokeWidth={2} />
                        </View>
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
        <View className="pt-16 pb-6 px-6 bg-white dark:bg-black">
            <View className="flex-row justify-between items-center mb-6 h-14">
                <View>
                    <Typography variant="h1" className="text-[32px] font-black text-gray-900 dark:text-white tracking-tight leading-none whitespace-nowrap">AI Assistant</Typography>
                    <Typography variant="body" className="text-gray-400 font-bold uppercase text-[11px] tracking-widest mt-1 leading-none">Choose your partner</Typography>
                </View>
                <TouchableOpacity
                    onPress={handleCreateAgent}
                    className="w-12 h-12 bg-indigo-50 dark:bg-indigo-500/10 border border-indigo-100 dark:border-indigo-900/30 rounded-2xl items-center justify-center shadow-sm"
                >
                    <Plus size={24} color="#6366f1" strokeWidth={2.5} />
                </TouchableOpacity>
            </View>

            {/* Flat Modern Search Bar */}
            <View className="flex-row items-center bg-gray-50 dark:bg-zinc-900 px-4 h-12 rounded-2xl border border-gray-100 dark:border-zinc-800">
                <Search size={18} color="#94a3b8" />
                <Typography className="ml-3 text-gray-400 font-medium text-[14px]">Search assistants...</Typography>
            </View>
        </View>
    );

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />
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

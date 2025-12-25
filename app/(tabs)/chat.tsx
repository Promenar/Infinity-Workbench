import React from 'react';
import { View, TouchableOpacity, Text } from 'react-native';
import { PageLayout, Typography } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { MessageSquare, Search, Edit } from 'lucide-react-native';
import { FlashList } from '@shopify/flash-list';
import { MOCK_CONVERSATIONS } from '../../src/data/mock';
import * as Haptics from 'expo-haptics';
import Animated, { FadeIn } from 'react-native-reanimated';
import { useI18n } from '../../src/lib/i18n';

export default function ChatListScreen() {
    const router = useRouter();
    const { t } = useI18n();

    const renderItem = ({ item, index }: { item: typeof MOCK_CONVERSATIONS[0], index: number }) => (
        <TouchableOpacity
            activeOpacity={0.7}
            className="flex-row items-center px-6 py-4 bg-white dark:bg-black w-full"
            onPress={() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                router.push(`/chat/${item.id}`);
            }}
        >
            {/* Avatar - Clean monochrome circle */}
            <View className="relative mr-4">
                <View className="w-[52px] h-[52px] rounded-2xl items-center justify-center bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800">
                    <MessageSquare size={24} color="#64748b" strokeWidth={1.5} />
                </View>
                {/* Unread Dot - Moved onto icon */}
                {item.unread > 0 && (
                    <View className="w-3 h-3 rounded-full bg-indigo-500 absolute -top-1 -right-1 border-2 border-white dark:border-black shadow-sm" />
                )}
            </View>

            {/* Content */}
            <View className="flex-1 justify-center py-1">
                <View className="flex-row justify-between items-baseline mb-1 pr-1">
                    <Typography variant="h3" className="text-[18px] font-bold text-gray-900 dark:text-gray-100 leading-tight">{item.title}</Typography>
                    <Typography variant="caption" className="text-gray-400 text-[12px] font-medium">{item.time}</Typography>
                </View>
                <Typography variant="body" className="text-gray-500 leading-5 text-[14px]" numberOfLines={2}>
                    {item.subtitle}
                </Typography>
            </View>
        </TouchableOpacity>
    );

    const ListHeader = () => (
        <View className="pt-16 pb-2 px-6 bg-white dark:bg-black">
            <View className="flex-row justify-between items-center mb-6 h-14">
                <View>
                    <Typography variant="h1" className="text-[32px] font-black text-gray-900 dark:text-white tracking-tight leading-none">{t.chat.title}</Typography>
                    <Typography variant="body" className="text-gray-400 font-bold uppercase text-[11px] tracking-widest mt-1 leading-none">{t.chat.subtitle}</Typography>
                </View>
                <TouchableOpacity
                    onPress={() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium)}
                    className="w-12 h-12 bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 rounded-2xl items-center justify-center shadow-sm"
                >
                    <Edit size={22} color="#64748b" strokeWidth={1.5} />
                </TouchableOpacity>
            </View>

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
            <FlashList
                data={MOCK_CONVERSATIONS}
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

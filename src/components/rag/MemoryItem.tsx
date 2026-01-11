
import React, { memo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, ContextMenu } from '../ui';
import { Brain, MoreVertical, Trash2, Clock } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from 'expo-haptics';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';
import { formatDistanceToNow } from 'date-fns';
import { zhCN, enUS } from 'date-fns/locale';
import { useI18n } from '../../lib/i18n';

interface MemoryItemProps {
    id: string;
    content: string;
    createdAt: number;
    onDelete: () => void;
    onPress: () => void;
}

export const MemoryItem = memo<MemoryItemProps>(({ id, content, createdAt, onDelete, onPress }) => {
    const { isDark, colors } = useTheme();
    const { language } = useI18n();

    const timeAgo = formatDistanceToNow(createdAt, {
        addSuffix: true,
        locale: language === 'zh' ? zhCN : enUS
    });

    const menuItems = [
        {
            key: 'delete',
            label: '删除记忆',
            onPress: () => {
                setTimeout(() => {
                    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
                    onDelete();
                }, 10);
            },
            destructive: true,
        },
    ];

    return (
        <Animated.View
            entering={FadeIn.duration(200)}
            exiting={FadeOut.duration(150)}
            className="mx-6 mb-3 bg-gray-50 dark:bg-zinc-900/50 rounded-2xl border border-gray-100 dark:border-zinc-800 overflow-hidden"
        >
            <TouchableOpacity
                onPress={onPress}
                activeOpacity={0.7}
                className="p-4"
            >
                <View className="flex-row items-center mb-2">
                    <View className="w-6 h-6 rounded-full bg-orange-500/10 items-center justify-center mr-2">
                        <Brain size={14} color="#f97316" />
                    </View>
                    <View className="flex-row items-center flex-1">
                        <Clock size={12} color="#94a3b8" />
                        <Typography className="text-[10px] text-gray-400 ml-1 font-medium">
                            {timeAgo}
                        </Typography>
                    </View>

                    <ContextMenu items={menuItems as any} triggerOnPress>
                        <View className="p-1 -mr-1">
                            <MoreVertical size={16} color="#94a3b8" />
                        </View>
                    </ContextMenu>
                </View>

                <Typography
                    className="text-sm text-gray-700 dark:text-gray-300 leading-5"
                    numberOfLines={4}
                >
                    {content}
                </Typography>
            </TouchableOpacity>
        </Animated.View>
    );
});

MemoryItem.displayName = 'MemoryItem';

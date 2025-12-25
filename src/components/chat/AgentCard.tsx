import React from 'react';
import { View, TouchableOpacity, ViewStyle } from 'react-native';
import { Typography } from '../ui';
import { Agent } from '../../types/chat';
import * as LucideIcons from 'lucide-react-native';
import { clsx } from 'clsx';
import * as Haptics from 'expo-haptics';

interface AgentCardProps {
    agent: Agent;
    onPress: () => void;
    onLongPress?: () => void;
    style?: ViewStyle;
}

export const AgentCard = ({ agent, onPress, onLongPress, style }: AgentCardProps) => {
    // Dynamically get icon component
    const IconComponent = (LucideIcons as any)[agent.avatar || 'MessageSquare'] || LucideIcons.MessageSquare;

    return (
        <TouchableOpacity
            activeOpacity={0.8}
            onPress={() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                onPress();
            }}
            onLongPress={() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                onLongPress?.();
            }}
            style={style}
            className="mb-4"
        >
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-[28px] p-5 border border-gray-100 dark:border-zinc-800 shadow-sm">
                {/* Header: Icon & Color Dot */}
                <View className="flex-row justify-between items-start mb-4">
                    <View
                        className="w-12 h-12 rounded-2xl items-center justify-center border"
                        style={{ backgroundColor: `${agent.color}15`, borderColor: `${agent.color}30` }}
                    >
                        <IconComponent size={24} color={agent.color} strokeWidth={2} />
                    </View>
                    {agent.isPreset && (
                        <View className="bg-indigo-50 dark:bg-indigo-500/10 px-2 py-0.5 rounded-full border border-indigo-100 dark:border-indigo-900/30">
                            <Typography className="text-indigo-600 dark:text-indigo-400 font-bold text-[9px] uppercase tracking-tighter">PRESET</Typography>
                        </View>
                    )}
                </View>

                {/* Content */}
                <Typography variant="h3" className="text-gray-900 dark:text-white font-bold leading-tight mb-1" numberOfLines={1}>
                    {agent.name}
                </Typography>
                <Typography variant="body" className="text-gray-500 dark:text-gray-400 text-[13px] leading-5" numberOfLines={3}>
                    {agent.description}
                </Typography>

                {/* Footer: Model Badge */}
                <View className="mt-4 pt-3 border-t border-gray-100 dark:border-zinc-800/50 flex-row items-center">
                    <View className="w-1.5 h-1.5 rounded-full bg-green-500 mr-2" />
                    <Typography className="text-gray-400 font-bold text-[10px] uppercase tracking-widest">{agent.defaultModel}</Typography>
                </View>
            </View>
        </TouchableOpacity>
    );
};

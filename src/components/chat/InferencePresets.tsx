import React, { useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography } from '../ui/Typography';
import { InferenceParams } from '../../types/chat';
import { useI18n } from '../../lib/i18n';
import { Zap, Anchor, Sparkles } from 'lucide-react-native';
import { clsx } from 'clsx';
import * as Haptics from '../../lib/haptics';

interface Props {
    currentTemperature: number;
    onSelect: (params: Partial<InferenceParams>) => void;
}

export const InferencePresets: React.FC<Props> = ({ currentTemperature, onSelect }) => {
    const { t } = useI18n();

    const PRESETS = useMemo(
        () => [
            {
                id: 'precise',
                icon: Anchor,
                label: t.inference.precise === '精确' ? '精确 (逻辑)' : `${t.inference.precise} (Logic)`,
                color: '#10b981',
                params: { temperature: 0.1, topP: 0.9 },
                targetTemp: 0.1
            },
            {
                id: 'balanced',
                icon: Zap,
                label: t.inference.balanced === '平衡' ? '平衡 (通用)' : `${t.inference.balanced} (General)`,
                color: '#6366f1',
                params: { temperature: 0.7, topP: 1.0 },
                targetTemp: 0.7
            },
            {
                id: 'creative',
                icon: Sparkles,
                label: t.inference.creative === '创意' ? '创意 (写作)' : `${t.inference.creative} (Writing)`,
                color: '#f43f5e',
                params: { temperature: 1.2, topP: 0.95 },
                targetTemp: 1.2
            },
        ],
        [t.inference.precise, t.inference.balanced, t.inference.creative],
    );

    const handleSelect = (preset: typeof PRESETS[0]) => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onSelect(preset.params);
    };

    // Determine active preset loosely based on temperature
    const activeId = useMemo(() => {
        if (currentTemperature <= 0.3) return 'precise';
        if (currentTemperature > 0.8) return 'creative';
        return 'balanced';
    }, [currentTemperature]);

    return (
        <View className="flex-row justify-between gap-3 px-1">
            {PRESETS.map((item) => {
                const isActive = activeId === item.id;
                return (
                    <TouchableOpacity
                        key={item.id}
                        onPress={() => handleSelect(item)}
                        activeOpacity={0.7}
                        className={clsx(
                            "flex-1 items-center rounded-2xl p-4 border shadow-sm",
                            isActive
                                ? "bg-white dark:bg-zinc-800 border-indigo-500 dark:border-indigo-500"
                                : "bg-white dark:bg-zinc-900 border-gray-100 dark:border-zinc-800"
                        )}
                        style={isActive ? {
                            shadowColor: item.color,
                            shadowOffset: { width: 0, height: 2 },
                            shadowOpacity: 0.15,
                            shadowRadius: 4,
                            elevation: 3
                        } : undefined}
                    >
                        <item.icon
                            size={22}
                            color={isActive ? item.color : '#9ca3af'}
                            className="mb-2"
                        />
                        <Typography
                            variant="caption"
                            className={clsx(
                                "font-bold text-xs mt-1",
                                isActive ? "text-gray-900 dark:text-white" : "text-gray-500 dark:text-gray-400"
                            )}
                        >
                            {item.label}
                        </Typography>
                    </TouchableOpacity>
                );
            })}
        </View>
    );
};

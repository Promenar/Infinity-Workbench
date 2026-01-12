import React, { useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography } from '../ui/Typography';
import { InferenceParams } from '../../types/chat';
import { useI18n } from '../../lib/i18n';
import { Zap, Anchor, Sparkles } from 'lucide-react-native';
import { clsx } from 'clsx';
import * as Haptics from '../../lib/haptics';

import { useTheme } from '../../theme/ThemeProvider';

interface Props {
    currentTemperature: number;
    onSelect: (params: Partial<InferenceParams>) => void;
}

export const InferencePresets: React.FC<Props> = ({ currentTemperature, onSelect }) => {
    const { t } = useI18n();
    const { colors } = useTheme();

    const PRESETS = useMemo(
        () => [
            {
                id: 'precise',
                icon: Anchor,
                label: t.agent.inference.precise,
                color: '#10b981',
                params: { temperature: 0.1, topP: 0.9 },
                targetTemp: 0.1
            },
            {
                id: 'balanced',
                icon: Zap,
                label: t.agent.inference.balanced,
                color: colors[500],
                params: { temperature: 0.7, topP: 1.0 },
                targetTemp: 0.7,
            },
            {
                id: 'creative',
                icon: Sparkles,
                label: t.agent.inference.creative,
                color: '#f43f5e',
                params: { temperature: 1.2, topP: 0.95 },
                targetTemp: 1.2
            },
        ],
        [t.agent.inference.precise, t.agent.inference.balanced, t.agent.inference.creative],
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
                            'flex-1 items-center rounded-2xl p-4 border shadow-sm',
                            isActive
                                ? 'bg-white dark:bg-zinc-800'
                                : 'bg-white dark:bg-zinc-900 border-indigo-50 dark:border-indigo-500/10',
                        )}
                        style={
                            isActive
                                ? {
                                    borderColor: item.color,
                                    shadowColor: item.color,
                                    shadowOffset: { width: 0, height: 2 },
                                    shadowOpacity: 0.15,
                                    shadowRadius: 4,
                                    elevation: 3,
                                }
                                : undefined
                        }
                    >
                        <item.icon
                            size={22}
                            color={isActive ? item.color : '#9ca3af'}
                            className="mb-2"
                        />
                        <Typography
                            variant="caption"
                            className={clsx(
                                "font-bold text-xs mt-1 text-center",
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

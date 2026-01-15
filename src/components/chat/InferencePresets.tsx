import React, { useMemo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography } from '../ui/Typography';
import { Card } from '../ui/Card';
import { InferenceParams } from '../../types/chat';
import { useI18n } from '../../lib/i18n';
import { Zap, BookOpen, Code } from 'lucide-react-native';
import { clsx } from 'clsx';
import * as Haptics from '../../lib/haptics';

import { useTheme } from '../../theme/ThemeProvider';

interface Props {
    currentTemperature: number;
    onSelect: (params: Partial<InferenceParams>) => void;
}

export const InferencePresets: React.FC<Props> = ({ currentTemperature, onSelect }) => {
    const { t } = useI18n();
    const { isDark, colors } = useTheme();

    const PRESETS = useMemo(
        () => [
            {
                id: 'precise',
                icon: Code,
                label: t.rag.presetCode,
                color: '#6366f1',
                params: { temperature: 0.1, topP: 0.9 },
                targetTemp: 0.1
            },
            {
                id: 'balanced',
                icon: Zap,
                label: t.rag.presetBalanced,
                color: '#06b6d4',
                params: { temperature: 0.7, topP: 1.0 },
                targetTemp: 0.7,
            },
            {
                id: 'creative',
                icon: BookOpen,
                label: t.rag.presetWriting,
                color: '#f59e0b',
                params: { temperature: 1.2, topP: 0.95 },
                targetTemp: 1.2
            },
        ],
        [t.rag.presetCode, t.rag.presetBalanced, t.rag.presetWriting],
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
                    <Card
                        key={item.id}
                        variant="glass"
                        onPress={() => handleSelect(item)}
                        className="flex-1 p-0" // Remove padding from card to let inner view fill
                        style={
                            isActive
                                ? {
                                    borderColor: item.color,
                                    borderWidth: 1.5,
                                    overflow: 'hidden',
                                    borderRadius: 20,
                                }
                                : { overflow: 'hidden', borderRadius: 20 }
                        }
                    >
                        <View
                            className="p-4 items-center w-full rounded-[20px]"
                            style={{
                                backgroundColor: isActive
                                    ? (isDark ? `${item.color}15` : `${item.color}08`)
                                    : 'transparent',
                            }}
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
                                    isActive ? "" : "text-gray-500 dark:text-gray-400"
                                )}
                                style={isActive ? { color: item.color } : undefined}
                            >
                                {item.label}
                            </Typography>
                        </View>
                    </Card>
                );
            })}
        </View>
    );
};

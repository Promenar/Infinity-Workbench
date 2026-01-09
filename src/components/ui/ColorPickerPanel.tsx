import React from 'react';
import { View, TouchableOpacity, TextInput } from 'react-native';
import { Typography } from './Typography';
import { RainbowSlider } from './RainbowSlider';
import { Check } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';

export const PRESET_COLORS = [
    { key: 'indigo', value: '#6366f1' }, // Indigo
    { key: 'purple', value: '#a855f7' }, // Purple
    { key: 'emerald', value: '#10b981' }, // Emerald
    { key: 'blue', value: '#2563eb' }, // Blue
    { key: 'orange', value: '#f97316' },  // Orange
    { key: 'rose', value: '#f43f5e' },    // Rose
    { key: 'cyan', value: '#06b6d4' },    // Cyan
    { key: 'zinc', value: '#18181b' },  // Zinc/Black
    { key: 'yellow', value: '#a16207' },    // Yellow/Brown
    { key: 'teal', value: '#0d9488' },    // Teal
    { key: 'sky', value: '#0ea5e9' },    // Sky
    { key: 'violet', value: '#8b5cf6' },    // Violet
] as const;

interface ColorPickerPanelProps {
    color: string;
    onColorChange: (color: string) => void;
    title?: string;
}

export const ColorPickerPanel: React.FC<ColorPickerPanelProps> = ({ color: selectedColor, onColorChange, title }) => {
    const { isDark, colors } = useTheme();
    const { t } = useI18n();

    return (
        <View>
            <View className="flex-row items-center mb-6 px-1">
                <View className="w-1.5 h-4 rounded-full mr-3" style={{ backgroundColor: colors[500] }} />
                <Typography className="text-sm font-bold text-gray-900 dark:text-white tracking-tight uppercase">
                    {title || t.common.color.title}
                </Typography>
            </View>

            {/* Presets */}
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-2xl p-5 border border-gray-100 dark:border-zinc-800 mb-8">
                <View className="flex-row flex-wrap justify-between">
                    {PRESET_COLORS.map((preset) => {
                        const isSelected = selectedColor === preset.value;
                        return (
                            <TouchableOpacity
                                key={preset.value}
                                onPress={() => onColorChange(preset.value)}
                                className="w-[22%] mb-4 items-center"
                            >
                                <View
                                    className={`w-14 h-14 rounded-full items-center justify-center border-2 ${isSelected ? 'border-gray-900 dark:border-white' : 'border-transparent'}`}
                                    style={{ backgroundColor: preset.value }}
                                >
                                    {isSelected && <Check size={24} color={preset.value === '#18181b' ? '#fff' : '#fff'} />}
                                </View>
                                <Typography className="text-[10px] mt-2 text-gray-500 dark:text-gray-400 font-medium">
                                    {(t.common.color.presets as any)[preset.key]}
                                </Typography>
                            </TouchableOpacity>
                        );
                    })}
                </View>
            </View>

            {/* Custom Rainbow Slider & Input */}
            <View className="flex-row items-center mb-4 px-1">
                <View className="w-1.5 h-4 rounded-full mr-3" style={{ backgroundColor: colors[500] }} />
                <Typography className="text-sm font-bold text-gray-900 dark:text-white uppercase">
                    {t.common.color.custom}
                </Typography>
            </View>
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800">
                <RainbowSlider
                    value={selectedColor}
                    onValueChange={onColorChange}
                />

                <View className="flex-row items-center mt-3">
                    <View
                        className="w-10 h-10 rounded-xl mr-3 border-2 border-white dark:border-zinc-700 shadow-sm"
                        style={{ backgroundColor: selectedColor }}
                    />
                    <TextInput
                        className="flex-1 text-gray-900 dark:text-white font-mono font-bold py-3 px-4 bg-white dark:bg-black rounded-xl border border-gray-100 dark:border-zinc-800"
                        value={selectedColor.toUpperCase()}
                        onChangeText={(text) => {
                            if (text.startsWith('#') && text.length <= 7) {
                                onColorChange(text.toLowerCase());
                            } else if (!text.startsWith('#') && text.length <= 6) {
                                onColorChange('#' + text.toLowerCase());
                            }
                        }}
                        placeholder="#6366F1"
                        placeholderTextColor="#94a3b8"
                        maxLength={7}
                    />
                </View>
            </View>
        </View>
    );
};

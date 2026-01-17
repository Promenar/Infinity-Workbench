import React, { useState, useEffect } from 'react';
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
    const [inputValue, setInputValue] = useState(selectedColor);

    useEffect(() => {
        setInputValue(selectedColor);
    }, [selectedColor]);

    const handleTextChange = (text: string) => {
        setInputValue(text);
        // Only trigger update if valid hex
        const normalized = text.startsWith('#') ? text : `#${text}`;
        if (/^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(normalized)) {
            onColorChange(normalized.toLowerCase());
        }
    };

    return (
        <View>
            <View className="mb-3 px-1">
                <Typography className="text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider">
                    {title || t.common.color.title}
                </Typography>
            </View>

            {/* Presets */}
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-2xl p-4 border border-gray-100 dark:border-zinc-800 mb-6">
                <View className="flex-row flex-wrap">
                    {PRESET_COLORS.map((preset) => {
                        const isSelected = selectedColor === preset.value;
                        return (
                            <TouchableOpacity
                                key={preset.value}
                                onPress={() => onColorChange(preset.value)}
                                style={{
                                    width: '16.6%',
                                    marginBottom: 12,
                                    alignItems: 'center',
                                }}
                            >
                                <View
                                    style={{
                                        width: 40,
                                        height: 40,
                                        borderRadius: 20,
                                        alignItems: 'center',
                                        justifyContent: 'center',
                                        borderWidth: 2,
                                        backgroundColor: preset.value,
                                        borderColor: isSelected
                                            ? (isDark ? '#ffffff' : '#111827')
                                            : 'transparent',
                                        ...(isSelected ? {
                                            shadowColor: "#000",
                                            shadowOffset: {
                                                width: 0,
                                                height: 1,
                                            },
                                            shadowOpacity: 0.20,
                                            shadowRadius: 1.41,
                                            elevation: 2,
                                        } : {})
                                    }}
                                >
                                    {isSelected && <Check size={18} color="#fff" />}
                                </View>
                            </TouchableOpacity>
                        );
                    })}
                </View>
            </View>

            <View className="mb-3 mt-4 px-1">
                <Typography className="text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider">
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
                        value={inputValue.toUpperCase()}
                        onChangeText={handleTextChange}
                        placeholder="#6366F1"
                        placeholderTextColor="#94a3b8"
                        maxLength={7}
                        onBlur={() => {
                            // Reset to valid color on blur if invalid
                            setInputValue(selectedColor);
                        }}
                    />
                </View>
            </View>
        </View>
    );
};

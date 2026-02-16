import React, { useState, useEffect } from 'react';
import { View, TextInput, TouchableOpacity, StyleSheet, TextInputProps } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useAnimatedProps,
    withTiming,
    useSharedValue,
    interpolateColor,
} from 'react-native-reanimated';
import { Search, X } from 'lucide-react-native';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';

interface AnimatedSearchBarProps extends TextInputProps {
    value: string;
    onChangeText: (text: string) => void;
    placeholder?: string;
    inputRef?: React.RefObject<TextInput | null>;
    containerStyle?: any;
}

const AnimatedSearchIcon = Animated.createAnimatedComponent(Search);

export function AnimatedSearchBar({
    value,
    onChangeText,
    placeholder,
    inputRef,
    containerStyle,
    ...props
}: AnimatedSearchBarProps) {
    const { isDark, colors } = useTheme();
    const [isFocused, setIsFocused] = useState(false);

    const focusProgress = useSharedValue(0);

    useEffect(() => {
        focusProgress.value = withTiming(isFocused ? 1 : 0, { duration: 250 });
    }, [isFocused]);

    const animatedContainerStyle = useAnimatedStyle(() => {
        const focusedBg = isDark ? 'rgba(99, 102, 241, 0.15)' : colors[50];
        const blurredBg = isDark ? 'rgba(15, 17, 26, 0.4)' : '#f9fafb';
        const focusedBorder = colors[500];
        const blurredBorder = isDark ? 'rgba(99, 102, 241, 0.1)' : '#f3f4f6';

        return {
            backgroundColor: interpolateColor(
                focusProgress.value,
                [0, 1],
                [blurredBg, focusedBg]
            ),
            borderColor: interpolateColor(
                focusProgress.value,
                [0, 1],
                [blurredBorder, focusedBorder]
            ),
        };
    });

    const animatedIconStyle = useAnimatedStyle(() => {
        return {
            opacity: 0.7 + focusProgress.value * 0.3,
        };
    });

    const iconColor = isFocused ? colors[500] : '#94a3b8';

    return (
        <Animated.View
            className="h-12 border rounded-2xl flex-row items-center px-4 overflow-hidden"
            style={[
                containerStyle,
                animatedContainerStyle,
            ]}
        >
            {isDark && (
                <BlurView
                    intensity={20}
                    tint="dark"
                    style={StyleSheet.absoluteFill}
                />
            )}

            <Animated.View style={animatedIconStyle}>
                <Search
                    size={18}
                    color={iconColor}
                    strokeWidth={2}
                />
            </Animated.View>

            <TextInput
                ref={inputRef}
                value={value}
                onChangeText={onChangeText}
                onFocus={(e) => {
                    setIsFocused(true);
                    props.onFocus?.(e);
                }}
                onBlur={(e) => {
                    setIsFocused(false);
                    props.onBlur?.(e);
                }}
                placeholder={placeholder}
                placeholderTextColor="#94a3b8"
                className="flex-1 ml-3 text-gray-900 dark:text-white font-semibold text-base"
                {...props}
            />

            {value.length > 0 && (
                <TouchableOpacity onPress={() => onChangeText('')} activeOpacity={0.6}>
                    <X size={18} color="#94a3b8" />
                </TouchableOpacity>
            )}
        </Animated.View>
    );
}

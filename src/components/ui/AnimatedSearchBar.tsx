
import React, { useState, useEffect } from 'react';
import { View, TextInput, TouchableOpacity, StyleSheet, TextInputProps } from 'react-native';
import Animated, {
    useAnimatedStyle,
    withTiming,
    useSharedValue,
    interpolateColor
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

    // Animation value: 0 = blurred, 1 = focused
    const focusProgress = useSharedValue(0);

    useEffect(() => {
        focusProgress.value = withTiming(isFocused ? 1 : 0, { duration: 300 });
    }, [isFocused]);

    const animatedStyle = useAnimatedStyle(() => {
        // Background Color Interpolation
        const focusedBg = isDark ? 'rgba(99, 102, 241, 0.15)' : colors[50];
        const blurredBg = isDark ? 'rgba(15, 17, 26, 0.4)' : '#f9fafb';

        // Border Color Interpolation
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
            color: interpolateColor(
                focusProgress.value,
                [0, 1],
                ['#94a3b8', colors[500]]
            )
        };
    });

    // Since we can't easily animate the 'color' prop of a logical component like Lucide icons 
    // without a wrapper or native props, we'll conditionally render or just use standard color prop logic
    // actually Reanimated's interpolateColor works best on View styles. 
    // For the icon color, we'll use a derived JS value for the icon prop, OR wrap icon in Disabled TextInput/Text??
    // A simple way is to just control the icon color via JS state since icon color animation isn't super critical to be on UI thread,
    // BUT the user asked for SILKY.
    // Reanimated `createAnimatedComponent` might be overkill for the icon color if Lucide supports it?
    // Lucide icons take a `color` string. We can't pass a shared value directly.
    // We'll stick to JS state for icon color OR duplicate the icon and opacity fade? 
    // Let's use JS state for icon color for now, it's "fast enough" usually, or simple boolean switch.
    // The user mainly complained about the "突兀" (abrupt) background/border change.
    // Let's try to animate the container primarily.

    return (
        <Animated.View
            className="h-12 border rounded-2xl flex-row items-center px-4 overflow-hidden"
            style={[
                containerStyle,
                animatedStyle,
            ]}
        >
            {isDark && (
                <BlurView
                    intensity={20}
                    tint="dark"
                    style={StyleSheet.absoluteFill}
                />
            )}

            <Search
                size={18}
                color={isFocused ? colors[500] : '#94a3b8'}
                strokeWidth={2}
            />

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
                <TouchableOpacity onPress={() => onChangeText('')}>
                    <X size={18} color="#94a3b8" />
                </TouchableOpacity>
            )}
        </Animated.View>
    );
}

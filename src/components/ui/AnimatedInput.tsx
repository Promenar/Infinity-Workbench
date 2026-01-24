
import React, { useState, useEffect, forwardRef } from 'react';
import { TextInput, TextInputProps, Keyboard } from 'react-native';
import Animated, {
    useAnimatedStyle,
    withTiming,
    useSharedValue,
    interpolateColor
} from 'react-native-reanimated';
import { useTheme } from '../../theme/ThemeProvider';

interface AnimatedInputProps extends TextInputProps {
    containerStyle?: any;
    inputStyle?: any;
}

export const AnimatedInput = forwardRef<TextInput, AnimatedInputProps>(({
    containerStyle,
    inputStyle,
    onFocus,
    onBlur,
    ...props
}, ref) => {
    const { isDark, colors } = useTheme();
    const [isFocused, setIsFocused] = useState(false);
    const internalRef = React.useRef<TextInput>(null);

    // Combine refs
    const handleRef = (node: TextInput) => {
        internalRef.current = node;
        if (typeof ref === 'function') ref(node);
        else if (ref) (ref as React.MutableRefObject<TextInput | null>).current = node;
    };

    // Animation value: 0 = blurred, 1 = focused
    const focusProgress = useSharedValue(0);

    useEffect(() => {
        focusProgress.value = withTiming(isFocused ? 1 : 0, { duration: 300 });
    }, [isFocused]);

    // Keyboard dismissal logic
    useEffect(() => {
        const keyboardDidHideListener = Keyboard.addListener('keyboardDidHide', () => {
            if (internalRef.current?.isFocused()) {
                internalRef.current?.blur();
            }
        });
        return () => keyboardDidHideListener.remove();
    }, []);

    const animatedStyle = useAnimatedStyle(() => {
        // Default colors matching AnimatedSearchBar for consistency
        // But allow override via containerStyle if possible? 
        // Actually, we want to ENFORCE the "same effect" (theme color tint).

        // Background: Transparent or slight gray -> Tinted
        const focusedBg = isDark ? 'rgba(99, 102, 241, 0.15)' : colors[50];
        const blurredBg = isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0,0,0,0.02)'; // Subtle default

        // Border: Transparent or light gray -> Theme Color
        const focusedBorder = colors[500];
        const blurredBorder = isDark ? 'rgba(255, 255, 255, 0.1)' : 'transparent';

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

    return (
        <Animated.View
            style={[
                {
                    borderRadius: 8,
                    borderWidth: 1,
                    overflow: 'hidden',
                    justifyContent: 'center',
                },
                containerStyle,
                animatedStyle,
            ]}
        >
            <TextInput
                ref={handleRef}
                onFocus={(e) => {
                    setIsFocused(true);
                    onFocus?.(e);
                }}
                onBlur={(e) => {
                    setIsFocused(false);
                    onBlur?.(e);
                }}
                style={[
                    {
                        paddingHorizontal: 8,
                        paddingVertical: 4,
                        color: isDark ? '#fff' : '#111',
                    },
                    inputStyle
                ]}
                placeholderTextColor="#9ca3af"
                {...props}
            />
        </Animated.View>
    );
});

AnimatedInput.displayName = 'AnimatedInput';

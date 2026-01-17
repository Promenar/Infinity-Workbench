import React, { useState, useEffect, useCallback, memo } from 'react';
import { View, TextInput, Text, StyleSheet, TextInputProps, StyleProp, ViewStyle, TextStyle } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';

interface ParsedInputProps extends Omit<TextInputProps, 'onChangeText' | 'style'> {
    value: string;
    onValueChange: (text: string) => void;
    onParsed?: (parsed: any) => void;
    onError?: (error: string | null) => void;
    parser?: (text: string) => any;
    debounceTime?: number;
    label?: string;
    placeholder?: string;
    style?: StyleProp<ViewStyle>;
    inputStyle?: StyleProp<TextStyle>;
    error?: string;
    required?: boolean;
}

/**
 * ParsedInput
 * 
 * An optimized input component that detaches the validation/parsing logic
 * from the synchronous render loop.
 * 
 * Features:
 * - Internal state for immediate UI feedback (no lag)
 * - Debounced parsing to prevent main thread blocking
 * - Native StyleSheet (No ClassNames) for navigation safety
 */
export const ParsedInput = memo(({
    value: controlledValue,
    onValueChange,
    onParsed,
    onError,
    parser,
    debounceTime = 500,
    label,
    placeholder,
    style,
    inputStyle,
    error: externalError,
    required,
    ...props
}: ParsedInputProps) => {
    const { isDark, colors } = useTheme();
    const [internalValue, setInternalValue] = useState(controlledValue);
    const [internalError, setInternalError] = useState<string | null>(null);

    // Sync internal state if controlled value changes externally
    useEffect(() => {
        setInternalValue(controlledValue);
    }, [controlledValue]);

    // Debounced Validation Logic
    useEffect(() => {
        if (!parser) return;

        const timer = setTimeout(() => {
            // 1. Basic Required Check
            if (required && !internalValue.trim()) {
                const err = 'This field is required';
                setInternalError(err);
                onError?.(err);
                return;
            }

            // 2. Custom Parser Check
            if (internalValue.trim()) {
                try {
                    const result = parser(internalValue);
                    setInternalError(null);
                    onError?.(null);
                    onParsed?.(result);
                } catch (e) {
                    const err = (e as Error).message || 'Invalid format';
                    setInternalError(err);
                    onError?.(err);
                }
            } else {
                setInternalError(null);
                onError?.(null);
            }
        }, debounceTime);

        return () => clearTimeout(timer);
    }, [internalValue, parser, debounceTime, required, onError, onParsed]);

    const handleChangeText = useCallback((text: string) => {
        setInternalValue(text);
        onValueChange(text);
    }, [onValueChange]);

    const displayError = externalError || internalError;

    return (
        <View style={[styles.container, style]}>
            {label && (
                <Text style={[
                    styles.label,
                    { color: isDark ? '#fff' : '#111' }
                ]}>
                    {label} {required && '*'}
                </Text>
            )}

            <TextInput
                value={internalValue}
                onChangeText={handleChangeText}
                placeholder={placeholder}
                placeholderTextColor="#9ca3af"
                style={[
                    styles.input,
                    {
                        backgroundColor: isDark ? '#18181b' : '#f9fafb',
                        borderColor: displayError ? '#ef4444' : (isDark ? '#27272a' : '#e5e7eb'),
                        color: isDark ? '#fff' : '#111',
                    },
                    inputStyle
                ]}
                {...props}
            />

            {displayError && (
                <Text style={styles.errorText}>{displayError}</Text>
            )}
        </View>
    );
});

const styles = StyleSheet.create({
    container: {
        marginBottom: 0,
    },
    label: {
        fontSize: 14,
        fontWeight: '600',
        marginBottom: 8,
    },
    input: {
        borderWidth: 1,
        borderRadius: 12,
        paddingHorizontal: 16,
        paddingVertical: 10,
        fontSize: 16, // Ensure >16px to prevent iOS zoom
    },
    errorText: {
        color: '#ef4444',
        fontSize: 12,
        marginTop: 4,
    }
});

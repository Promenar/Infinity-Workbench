import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, StyleSheet, LayoutChangeEvent } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withTiming,
    Easing,
} from 'react-native-reanimated';
import { ChevronRight } from 'lucide-react-native';
import { Typography } from './Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { Borders, Spacing } from '../../theme/glass';
import * as Haptics from '../../lib/haptics';

interface CollapsibleSectionProps {
    title: string;
    children: React.ReactNode;
    defaultOpen?: boolean;
    rightElement?: React.ReactNode;
    icon?: React.ReactNode;
    style?: any;
    contentContainerStyle?: any;
}

export const CollapsibleSection: React.FC<CollapsibleSectionProps> = ({
    title,
    children,
    defaultOpen = false,
    rightElement,
    icon,
    style,
    contentContainerStyle,
}) => {
    const { colors, isDark } = useTheme();
    const [isOpen, setIsOpen] = useState(defaultOpen);
    const [contentHeight, setContentHeight] = useState(0);
    const [measured, setMeasured] = useState(false);

    const height = useSharedValue(defaultOpen ? 1 : 0);
    const rotation = useSharedValue(defaultOpen ? 90 : 0);

    const toggleOpen = () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            const nextState = !isOpen;
            setIsOpen(nextState);
            height.value = withTiming(nextState ? 1 : 0, {
                duration: 300,
                easing: Easing.bezier(0.25, 0.1, 0.25, 1),
            });
            rotation.value = withTiming(nextState ? 90 : 0, { duration: 300 });
        }, 10);
    };

    const animatedContentStyle = useAnimatedStyle(() => {
        'worklet';
        return {
            height: contentHeight === 0
                ? undefined
                : (height.value * contentHeight),
            opacity: height.value,
            overflow: 'hidden',
        };
    });

    const iconStyle = useAnimatedStyle(() => {
        'worklet';
        return {
            transform: [{ rotate: `${rotation.value}deg` }],
        };
    });

    const onLayout = (event: LayoutChangeEvent) => {
        const h = event.nativeEvent.layout.height;
        if (h > 0 && h !== contentHeight) {
            setContentHeight(h);
            setMeasured(true);
        }
    };

    return (
        <View
            className="rounded-3xl overflow-hidden mb-3 border"
            style={[{
                borderColor: isDark ? Borders.primary.dark : Borders.primary.light,
                backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : 'rgba(249, 250, 251, 0.8)'
            }, style]}
        >
            <TouchableOpacity
                onPress={toggleOpen}
                activeOpacity={0.7}
                className="flex-row items-center justify-between p-3"
            >
                <View className="flex-row items-center flex-1 mr-4">
                    {icon && <View className="mr-3">{icon}</View>}
                    <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                        {title}
                    </Typography>
                </View>

                <View className="flex-row items-center gap-3">
                    {!isOpen && rightElement && (
                        <View style={{ opacity: isOpen ? 0 : 1 }}>
                            {rightElement}
                        </View>
                    )}
                    <Animated.View style={iconStyle}>
                        <ChevronRight size={20} color={isDark ? '#9ca3af' : '#6b7280'} />
                    </Animated.View>
                </View>
            </TouchableOpacity>

            {/* 测量层：仅在未测量完成时渲染 */}
            {!measured && (
                <View
                    style={{
                        position: 'absolute',
                        opacity: 0,
                        zIndex: -100,
                        width: '100%',
                        padding: Spacing[3],
                    }}
                    pointerEvents="none"
                    onLayout={onLayout}
                >
                    <View style={contentContainerStyle}>
                        {children}
                    </View>
                </View>
            )}

            <Animated.View
                style={[animatedContentStyle, { borderTopWidth: 1, borderTopColor: isDark ? Borders.primary.dark : Borders.primary.light }]}
            >
                <View className="p-3" style={contentContainerStyle}>
                    {children}
                </View>
            </Animated.View>
        </View>
    );
};

export default CollapsibleSection;

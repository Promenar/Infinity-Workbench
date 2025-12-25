import { View, ViewProps, Platform } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { twMerge } from 'tailwind-merge';
import { useIsFocused, useNavigationState } from '@react-navigation/native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import React, { useEffect } from 'react';

interface PageLayoutProps extends ViewProps {
    safeArea?: boolean;
}

// Module-level state to persist transition history across component instances
let globalLastTabIndex = 0;

const TAB_MAP: Record<string, number> = {
    'chat': 0,
    'rag': 1,
    'settings': 2,
};

export function PageLayout({ safeArea = true, className, children, ...props }: PageLayoutProps) {
    const containerClass = twMerge("flex-1 bg-white dark:bg-black", className);
    const isFocused = useIsFocused();
    const route = useNavigationState(state => state?.routes[state.index]);

    // Animation Shared Values
    const opacity = useSharedValue(0);
    const translateX = useSharedValue(0);

    useEffect(() => {
        if (isFocused && route) {
            const currentTabName = route.name;
            const currentIndex = TAB_MAP[currentTabName] ?? 0;

            // Calculate direction: if current index is greater than last, it's a forward move (slide from right)
            // If current index is smaller, it's a backward move (slide from left)
            const slideDistance = 20;
            const startX = currentIndex >= globalLastTabIndex ? slideDistance : -slideDistance;

            // Initial state for animation
            translateX.value = startX;
            opacity.value = 0;

            // Trigger animation
            // Use hardware texture for Android to keep shadows stable during translation
            opacity.value = withTiming(1, { duration: 150 });
            translateX.value = withTiming(0, { duration: 150 });

            // Update history
            globalLastTabIndex = currentIndex;
        }
    }, [isFocused, route?.name]);

    const animatedStyle = useAnimatedStyle(() => ({
        opacity: opacity.value,
        transform: [{ translateX: translateX.value }],
    }));

    const content = (
        <Animated.View
            style={[{ flex: 1 }, animatedStyle]}
            // Crucial for Android shadow stability during animation
            renderToHardwareTextureAndroid={true}
        >
            {children}
        </Animated.View>
    );

    if (safeArea) {
        return (
            <SafeAreaView className={containerClass} {...props} edges={['top', 'left', 'right']}>
                {content}
            </SafeAreaView>
        );
    }

    return (
        <View className={containerClass} {...props}>
            {content}
        </View>
    );
}

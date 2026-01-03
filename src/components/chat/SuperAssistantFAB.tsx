import React, { useEffect } from 'react';
import { TouchableOpacity, StyleSheet, View, Platform } from 'react-native';
import { Image } from 'expo-image';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withRepeat,
    withTiming,
    withSpring,
    withSequence,
    withDelay,
    Easing,
    interpolate,
    Extrapolate,
    useDerivedValue,
    cancelAnimation
} from 'react-native-reanimated';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from 'expo-haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useSPAStore } from '../../store/spa-store';
import { useChatStore } from '../../store/chat-store';
import * as LucideIcons from 'lucide-react-native';
import { preventDoubleTap } from '../../lib/navigation-utils';

interface SuperAssistantFABProps {
    onPress: () => void;
}

// =============================================================================
// VFX Components
// =============================================================================

/**
 * 💡 Hyper Glow Component
 * Simulates a high-energy neon glow using multiple semi-transparent layers.
 * Works perfectly on Android where native shadows fail/don't support color.
 */
const HyperGlow = ({ color, isGenerating }: { color: string, isGenerating: boolean }) => {
    // 3 Layers of glow
    const scale1 = useSharedValue(1);
    const scale2 = useSharedValue(1);
    const scale3 = useSharedValue(1);
    const opacity = useSharedValue(0.5);

    useEffect(() => {
        const duration = isGenerating ? 1000 : 3000;

        // Base pulse
        opacity.value = withRepeat(
            withTiming(isGenerating ? 0.8 : 0.4, { duration, easing: Easing.inOut(Easing.ease) }),
            -1,
            true
        );

        // Ripples
        scale1.value = withRepeat(withTiming(1.4, { duration: duration * 1.5, easing: Easing.out(Easing.ease) }), -1, false);
        scale2.value = withDelay(200, withRepeat(withTiming(1.6, { duration: duration * 1.5, easing: Easing.out(Easing.ease) }), -1, false));
        scale3.value = withDelay(400, withRepeat(withTiming(1.8, { duration: duration * 1.5, easing: Easing.out(Easing.ease) }), -1, false));

    }, [isGenerating]);

    const style1 = useAnimatedStyle(() => ({
        transform: [{ scale: scale1.value }],
        opacity: interpolate(scale1.value, [1, 1.4], [0.6, 0])
    }));

    const style2 = useAnimatedStyle(() => ({
        transform: [{ scale: scale2.value }],
        opacity: interpolate(scale2.value, [1, 1.6], [0.4, 0])
    }));

    const style3 = useAnimatedStyle(() => ({
        transform: [{ scale: scale3.value }],
        opacity: interpolate(scale3.value, [1, 1.8], [0.2, 0])
    }));

    return (
        <View style={StyleSheet.absoluteFill} pointerEvents="none">
            {/* Core Glow */}
            <Animated.View style={[styles.glowLayer, { backgroundColor: color, opacity: 0.3, transform: [{ scale: 1.2 }] }]} />

            {/* Ripples */}
            <Animated.View style={[styles.glowLayer, { backgroundColor: color }, style1]} />
            <Animated.View style={[styles.glowLayer, { backgroundColor: color }, style2]} />
            <Animated.View style={[styles.glowLayer, { backgroundColor: color }, style3]} />
        </View>
    );
};

const QuantumRings = ({ isGenerating, color }: { isGenerating: boolean, color: string }) => {
    const rotateX = useSharedValue(0);
    const rotateY = useSharedValue(0);
    const scale = useSharedValue(1);

    useEffect(() => {
        const duration = isGenerating ? 1500 : 8000;

        rotateX.value = withRepeat(withTiming(360, { duration, easing: Easing.linear }), -1);
        rotateY.value = withRepeat(withTiming(360, { duration: duration * 1.5, easing: Easing.linear }), -1);

        scale.value = withTiming(isGenerating ? 1.3 : 1, { duration: 500 });
    }, [isGenerating]);

    const styleX = useAnimatedStyle(() => ({
        transform: [{ rotateZ: `${rotateX.value}deg` }, { scaleX: 1 }, { scaleY: 0.3 }],
        borderColor: color,
        opacity: isGenerating ? 1 : 0.6,
        borderWidth: isGenerating ? 3 : 1.5
    }));

    const styleY = useAnimatedStyle(() => ({
        transform: [{ rotateZ: `${-rotateY.value}deg` }, { scaleX: 0.3 }, { scaleY: 1 }],
        borderColor: color,
        opacity: isGenerating ? 1 : 0.6,
        borderWidth: isGenerating ? 3 : 1.5
    }));

    return (
        <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]} pointerEvents="none">
            <Animated.View style={[styles.quantumRing, styleX]} />
            <Animated.View style={[styles.quantumRing, styleY]} />
        </View>
    );
};

const GlitchEffect = ({ isGenerating, color }: { isGenerating: boolean, color: string }) => {
    const shiftX = useSharedValue(0);
    const shiftY = useSharedValue(0);
    const opacity = useSharedValue(0);

    useEffect(() => {
        if (!isGenerating) {
            opacity.value = 0;
            return;
        }

        const glitch = () => {
            shiftX.value = withSequence(
                withTiming(-5, { duration: 50 }),
                withTiming(5, { duration: 50 }),
                withTiming(0, { duration: 50 })
            );
            shiftY.value = withSequence(
                withTiming(5, { duration: 50 }),
                withTiming(-5, { duration: 50 }),
                withTiming(0, { duration: 50 })
            );
            opacity.value = withSequence(
                withTiming(0.8, { duration: 50 }),
                withTiming(0.4, { duration: 50 }),
                withTiming(0, { duration: 100 })
            );
        };

        const interval = setInterval(glitch, 2000);
        glitch();

        return () => clearInterval(interval);
    }, [isGenerating]);

    const style = useAnimatedStyle(() => ({
        transform: [{ translateX: shiftX.value }, { translateY: shiftY.value }],
        backgroundColor: color,
        opacity: opacity.value,
    }));

    return (
        <Animated.View style={[StyleSheet.absoluteFill, { borderRadius: 32 }, style]} />
    );
};

// =============================================================================
// Main Component
// =============================================================================

export const SuperAssistantFAB: React.FC<SuperAssistantFABProps> = ({ onPress }) => {
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const { preferences } = useSPAStore();
    const isGenerating = useChatStore(state => !!state.activeRequests['super_assistant']);
    const { animationMode = 'pulse' } = preferences.fab;

    const mode = animationMode || 'pulse';

    // Shared Values
    const pulse = useSharedValue(1);
    const rotation = useSharedValue(0);

    useEffect(() => {
        // --- Pulse Animation ---
        if (mode === 'pulse' || mode === 'liquid') {
            const baseScale = mode === 'liquid' ? (isGenerating ? 1.25 : 1.05) : (isGenerating ? 1.15 : 1.05);
            const duration = isGenerating ? (mode === 'liquid' ? 600 : 800) : 1500;

            pulse.value = withRepeat(
                withSequence(
                    withTiming(baseScale, { duration, easing: Easing.inOut(Easing.ease) }),
                    withTiming(1, { duration, easing: Easing.inOut(Easing.ease) })
                ),
                -1,
                true
            );
        } else {
            pulse.value = withTiming(1);
        }

        // --- Rotation ---
        if (preferences.fab.enableRotation || mode === 'nebula') {
            const duration = isGenerating ? 2000 : 15000;
            rotation.value = withRepeat(
                withTiming(360, { duration, easing: Easing.linear }),
                -1
            );
        } else {
            rotation.value = 0;
        }

    }, [mode, isGenerating, preferences.fab.enableRotation]);

    const containerStyle = useAnimatedStyle(() => ({
        transform: [
            { scale: pulse.value },
            { scaleX: mode === 'liquid' && isGenerating ? interpolate(pulse.value, [1, 1.25], [1, 0.9]) : 1 }
        ],
    }));

    const iconStyle = useAnimatedStyle(() => ({
        transform: [{ rotate: `${rotation.value}deg` }],
    }));

    const handlePress = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        preventDoubleTap(() => {
            setTimeout(() => {
                onPress();
            }, 50);
        });
    };

    const renderIcon = () => {
        const { iconType, customIconUri, iconColor } = preferences.fab;
        if (iconType === 'custom' && customIconUri) return null;
        const IconComponent = (LucideIcons as any)[iconType] || LucideIcons.Sparkles;
        return <IconComponent size={28} color={iconColor} strokeWidth={2.5} />; // White icon for better contrast on glow? No, user prefers custom color.
    };

    const isCustomIcon = preferences.fab.iconType === 'custom' && preferences.fab.customIconUri;
    const backgroundColor = preferences.fab.backgroundColor;
    const iconColor = preferences.fab.iconColor;
    const glowColor = preferences.fab.enableGlow ? (preferences.fab.glowColor || iconColor) : 'transparent'; // Fallback to icon color if glow color not set

    return (
        <View
            pointerEvents="box-none"
            style={[styles.wrapper, { bottom: 85 + insets.bottom }]}
        >
            {/* 🌟 Hyper Glow Background (Replaces simple Shadow) */}
            {preferences.fab.enableGlow && (
                <HyperGlow color={glowColor} isGenerating={isGenerating} />
            )}

            {/* Special Effects Layers */}
            {mode === 'quantum' && <QuantumRings isGenerating={isGenerating} color={iconColor} />}
            {mode === 'glitch' && <GlitchEffect isGenerating={isGenerating} color={iconColor} />}

            {/* Main Button Container */}
            <Animated.View style={[
                styles.container,
                containerStyle,
                {
                    // No more shadow props here to avoid conflict with HyperGlow
                    backgroundColor: isCustomIcon ? 'transparent' : backgroundColor + '1A'
                }
            ]}>
                <TouchableOpacity
                    activeOpacity={0.8}
                    onPress={handlePress}
                    style={styles.touchable}
                >
                    <BlurView
                        intensity={isDark ? 30 : 50} // Reduced intensity for clearer glow
                        tint={isDark ? 'dark' : 'light'}
                        style={styles.blur}
                    >
                        <View style={[
                            styles.inner,
                            {
                                backgroundColor: isCustomIcon ? 'transparent' : backgroundColor + '4D', // 30% -> 4D (30%)
                                borderColor: backgroundColor + '80',
                            }
                        ]}>
                            <Animated.View style={[styles.rotatingContainer, iconStyle]}>
                                {isCustomIcon && (
                                    <Image
                                        source={{ uri: preferences.fab.customIconUri }}
                                        style={StyleSheet.absoluteFillObject}
                                        contentFit="cover"
                                        transition={200}
                                    />
                                )}
                                {renderIcon()}
                            </Animated.View>
                        </View>
                    </BlurView>
                </TouchableOpacity>
            </Animated.View>
        </View>
    );
};

const styles = StyleSheet.create({
    wrapper: {
        position: 'absolute',
        right: 24,
        width: 64,
        height: 64,
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 9999,
    },
    container: {
        width: 64,
        height: 64,
        borderRadius: 32,
    },
    glowLayer: {
        position: 'absolute',
        width: 64,
        height: 64,
        borderRadius: 32,
    },
    quantumRing: {
        position: 'absolute',
        width: 90,
        height: 90,
        borderRadius: 45,
    },
    touchable: {
        width: '100%',
        height: '100%',
        borderRadius: 32,
        overflow: 'hidden',
    },
    blur: {
        width: '100%',
        height: '100%',
        alignItems: 'center',
        justifyContent: 'center',
    },
    inner: {
        width: 56,
        height: 56,
        borderRadius: 28,
        borderWidth: 1.5,
        alignItems: 'center',
        justifyContent: 'center',
        overflow: 'hidden',
    },
    rotatingContainer: {
        width: '100%',
        height: '100%',
        alignItems: 'center',
        justifyContent: 'center',
    },
});

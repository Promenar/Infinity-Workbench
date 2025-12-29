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
    useDerivedValue
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
// Animation Components
// =============================================================================

const QuantumRings = ({ isGenerating, color }: { isGenerating: boolean, color: string }) => {
    const rotateX = useSharedValue(0);
    const rotateY = useSharedValue(0);
    const scale = useSharedValue(1);

    useEffect(() => {
        const duration = isGenerating ? 2000 : 8000;

        rotateX.value = withRepeat(withTiming(360, { duration, easing: Easing.linear }), -1);
        rotateY.value = withRepeat(withTiming(360, { duration: duration * 1.5, easing: Easing.linear }), -1);

        scale.value = withTiming(isGenerating ? 1.2 : 1, { duration: 500 });
    }, [isGenerating]);

    const styleX = useAnimatedStyle(() => ({
        transform: [{ rotateZ: `${rotateX.value}deg` }, { scaleX: 1 }, { scaleY: 0.3 }], // Simulate 3D ring
        borderColor: color,
        opacity: isGenerating ? 1 : 0.8, // Increased opacity
        shadowColor: color,
        shadowOpacity: 0.8,
        shadowRadius: 10,
        elevation: 10 // Android Shadow
    }));

    const styleY = useAnimatedStyle(() => ({
        transform: [{ rotateZ: `${-rotateY.value}deg` }, { scaleX: 0.3 }, { scaleY: 1 }], // Simulate 3D ring
        borderColor: color,
        opacity: isGenerating ? 1 : 0.8, // Increased opacity
        shadowColor: color,
        shadowOpacity: 0.8,
        shadowRadius: 10,
        elevation: 10 // Android Shadow
    }));

    return (
        <View style={StyleSheet.absoluteFill} pointerEvents="none">
            <Animated.View style={[styles.quantumRing, styleX, { shadowColor: color }]} />
            <Animated.View style={[styles.quantumRing, styleY, { shadowColor: color }]} />
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

        // Glitch sequence
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
                withTiming(0.9, { duration: 50 }), // High visibility
                withTiming(0.4, { duration: 50 }),
                withTiming(0, { duration: 100 })
            );
        };

        const interval = setInterval(glitch, 2000); // Glitch every 2s
        glitch(); // Initial glitch

        return () => clearInterval(interval);
    }, [isGenerating]);

    const style = useAnimatedStyle(() => ({
        transform: [{ translateX: shiftX.value }, { translateY: shiftY.value }],
        backgroundColor: color,
        opacity: opacity.value,
        shadowColor: color,
        shadowOpacity: 1,
        shadowRadius: 15,
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

    // Use default values if animationMode is undefined (safety fallback)
    const mode = animationMode || 'pulse';

    // Shared Values
    const pulse = useSharedValue(1);
    const rotation = useSharedValue(0);
    const glowScale = useSharedValue(1);
    const glowOpacity = useSharedValue(0.6);

    useEffect(() => {
        // --- 1. Pulse Animation (Liquid / Pulse Modes) ---
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

        // --- 2. Rotation Animation (Nebula / Default) ---
        if (preferences.fab.enableRotation || mode === 'nebula') {
            const duration = isGenerating ? 2000 : 15000;
            rotation.value = withRepeat(
                withTiming(360, { duration, easing: Easing.linear }),
                -1
            );
        } else {
            rotation.value = 0;
        }

        // --- 3. Glow Animation ---
        if (preferences.fab.enableGlow) {
            const duration = isGenerating ? 500 : 2000;
            const targetScale = isGenerating ? 1.8 : 1.5;

            glowScale.value = withRepeat(
                withTiming(targetScale, { duration, easing: Easing.out(Easing.ease) }),
                -1,
                false
            );
            glowOpacity.value = withRepeat(
                withTiming(0, { duration, easing: Easing.out(Easing.ease) }),
                -1,
                false
            );
        } else {
            glowScale.value = 1;
            glowOpacity.value = 0;
        }

    }, [mode, isGenerating, preferences.fab.enableRotation, preferences.fab.enableGlow]);

    // Styles
    const containerStyle = useAnimatedStyle(() => ({
        transform: [
            { scale: pulse.value },
            { scaleX: mode === 'liquid' && isGenerating ? interpolate(pulse.value, [1, 1.25], [1, 0.9]) : 1 } // Morph/Squash effect
        ],
    }));

    const iconStyle = useAnimatedStyle(() => ({
        transform: [{ rotate: `${rotation.value}deg` }],
    }));

    const glowStyle = useAnimatedStyle(() => ({
        transform: [{ scale: glowScale.value }],
        opacity: glowOpacity.value * preferences.fab.glowIntensity,
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
        return <IconComponent size={28} color={iconColor} strokeWidth={2.5} />;
    };

    const isCustomIcon = preferences.fab.iconType === 'custom' && preferences.fab.customIconUri;
    const backgroundColor = preferences.fab.backgroundColor;
    const glowColor = preferences.fab.enableGlow ? preferences.fab.glowColor : 'transparent';

    return (
        <View
            pointerEvents="box-none"
            style={[styles.wrapper, { bottom: 85 + insets.bottom }]}
        >
            {/* Background Glow */}
            {preferences.fab.enableGlow && (
                <Animated.View
                    style={[
                        styles.glowRing,
                        { backgroundColor: glowColor, shadowColor: glowColor },
                        glowStyle
                    ]}
                />
            )}

            {/* Special Effects Layers */}
            {mode === 'quantum' && <QuantumRings isGenerating={isGenerating} color={preferences.fab.iconColor} />}
            {mode === 'glitch' && <GlitchEffect isGenerating={isGenerating} color={preferences.fab.iconColor} />}

            {/* Main Button */}
            <Animated.View style={[
                styles.container,
                containerStyle,
                {
                    shadowColor: backgroundColor, // Colored shadow
                    shadowOpacity: 0.5,
                    backgroundColor: isCustomIcon ? 'transparent' : backgroundColor + '1A' // Tint behind blur (10%)
                }
            ]}>
                <TouchableOpacity
                    activeOpacity={0.8}
                    onPress={handlePress}
                    style={styles.touchable}
                >
                    <BlurView
                        intensity={isDark ? 40 : 60}
                        tint={isDark ? 'dark' : 'light'}
                        style={styles.blur}
                    >
                        <View style={[
                            styles.inner,
                            {
                                backgroundColor: isCustomIcon ? 'transparent' : backgroundColor + '33', // Increased to 20%
                                borderColor: backgroundColor + '80', // Increased border to 50%
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
        shadowOffset: { width: 0, height: 4 },
        shadowRadius: 8,
        elevation: 6,
    },
    glowRing: {
        position: 'absolute',
        width: 64,
        height: 64,
        borderRadius: 32,
        shadowOffset: { width: 0, height: 0 },
        shadowOpacity: 0.6,
        shadowRadius: 20, // 强光晕
        elevation: 0, // Android 不支持 glow shadow 动画，故仅依赖 scale+bg
    },
    quantumRing: {
        position: 'absolute',
        left: -18, // Center 100 - 64 / 2 ?? No these are absolute. width 100.
        top: -18,
        width: 100,
        height: 100,
        borderRadius: 50,
        borderWidth: 2,
        borderColor: 'cyan',
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

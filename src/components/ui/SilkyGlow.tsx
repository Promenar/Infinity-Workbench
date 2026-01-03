import React, { useEffect } from 'react';
import { StyleSheet, ViewStyle } from 'react-native';
import {
    Canvas,
    Circle,
    RadialGradient,
    BlurMask,
    vec,
    useSharedValueEffect,
    useValue,
} from '@shopify/react-native-skia';
import Animated, {
    useSharedValue,
    withRepeat,
    withTiming,
    useDerivedValue,
    Easing
} from 'react-native-reanimated';

interface SilkyGlowProps {
    color: string;
    size: number;
    style?: ViewStyle;
}

export const SilkyGlow: React.FC<SilkyGlowProps> = ({ color, size, style }) => {
    // Shared values for breathing animation
    const progress = useSharedValue(0);

    useEffect(() => {
        progress.value = withRepeat(
            withTiming(1, { duration: 2000, easing: Easing.inOut(Easing.ease) }),
            -1,
            true
        );
    }, []);

    // Derived values for Skia
    // Note: Skia's Canvas can accept Reanimated shared values directly in some contexts, 
    // but usually via standard props or selectors.
    // Integrating Reanimated with Skia:
    // We can use generic Animated.View to wrap Canvas if we want to animate opacity/scale of the whole container,
    // OR use derived values for Radius/Opacity if we want granular control inside Canvas.
    // The prompt asks for opacity (0.6 ~ 0.9) and scale (1.0 ~ 1.15).
    // Applying scale transformation to the Canvas logic is cleaner for "Fusion Style".

    const containerStyle = useDerivedValue(() => {
        return {
            opacity: 0.6 + progress.value * 0.3, // 0.6 to 0.9
            transform: [
                { scale: 1.0 + progress.value * 0.15 } // 1.0 to 1.15
            ]
        };
    });

    // 🔑 Fix: Canvas clipping
    // The blur extends outward. If the circle fills the canvas (r = size/2), the blur gets clipped.
    // We need to padding the radius calculation.
    const blurRadius = 20;
    const padding = blurRadius * 1.5; // Enough space for the glow to fade out
    const r = (size - padding * 2) / 2;
    const c = size / 2; // Center remains absolute center

    return (
        <Animated.View
            pointerEvents="none"
            style={[
                styles.container,
                { width: size, height: size },
                style,
                containerStyle // Reanimated style
            ]}
        >
            <Canvas style={{ flex: 1 }}>
                <Circle cx={c} cy={c} r={r}>
                    <RadialGradient
                        c={vec(c, c)}
                        r={r}
                        colors={["white", color, "transparent"]}
                        positions={[0, 0.3, 1]}
                    />
                    {/* 极致柔和的虚化 */}
                    <BlurMask blur={20} style="normal" />
                </Circle>
            </Canvas>
        </Animated.View>
    );
};

const styles = StyleSheet.create({
    container: {
        justifyContent: 'center',
        alignItems: 'center',
    },
});

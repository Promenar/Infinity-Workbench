import React, { useEffect } from 'react';
import { StyleSheet, View, ViewStyle } from 'react-native';
import { Canvas, Circle, RadialGradient, BlurMask, vec, Oval } from '@shopify/react-native-skia';
import Animated, {
    useSharedValue,
    withRepeat,
    withTiming,
    useDerivedValue,
    useAnimatedStyle,
    Easing,
    cancelAnimation,
} from 'react-native-reanimated';

interface SilkyGlowProps {
    color: string;
    size: number;
    style?: ViewStyle;
}

export const SilkyGlow: React.FC<SilkyGlowProps> = ({ color, size, style }) => {
    // CONFIG: Draw on a canvas 2x larger than the visible container
    // This ensures the Gaussian Blur (which spreads wide) never hits the edge.
    const canvasSize = size * 2.5;
    const canvasCenter = canvasSize / 2;
    const offset = (size - canvasSize) / 2; // Centers the large canvas over the small container

    const rotation = useSharedValue(0);
    const breath = useSharedValue(1);

    useEffect(() => {
        rotation.value = withRepeat(
            withTiming(360, { duration: 8000, easing: Easing.linear }),
            -1,
            false
        );
        breath.value = withRepeat(
            withTiming(1.1, { duration: 2500, easing: Easing.inOut(Easing.ease) }),
            -1,
            true
        );
        return () => {
            cancelAnimation(rotation);
            cancelAnimation(breath);
        };
    }, []);

    const rotateStyle = useAnimatedStyle(() => ({
        transform: [{ rotate: `${rotation.value}deg` }],
    }));

    const breathStyle = useAnimatedStyle(() => ({
        transform: [{ scale: breath.value }],
        opacity: 0.8,
    }));

    // Style for the expanded drawing layers
    // They are absolutely positioned to center over the parent
    const layerStyle = {
        position: 'absolute' as const,
        width: canvasSize,
        height: canvasSize,
        left: offset,
        top: offset,
    };

    return (
        // overflow: 'visible' is critical here to allow the large canvas to show
        <View style={[styles.container, { width: size, height: size, overflow: 'visible' }, style]}>

            {/* Layer 1: Core Glow */}
            <Animated.View style={[layerStyle, breathStyle]}>
                <Canvas style={{ flex: 1 }}>
                    <Circle cx={canvasCenter} cy={canvasCenter} r={size * 0.25}>
                        <RadialGradient
                            c={vec(canvasCenter, canvasCenter)}
                            r={size * 0.3}
                            colors={['white', color, 'transparent']}
                            positions={[0, 0.4, 1]}
                        />
                        {/* Soft, wide blur for the core */}
                        <BlurMask blur={30} style="normal" />
                    </Circle>
                </Canvas>
            </Animated.View>

            {/* Layer 2: Rotating Nebula */}
            <Animated.View style={[layerStyle, rotateStyle]}>
                <Canvas style={{ flex: 1 }}>
                    {/* Main Horizontal Lobe */}
                    {/* Using 0.8 width relative to SIZE (not canvasSize) ensures it looks right proportionally */}
                    <Oval
                        x={canvasCenter - size * 0.4}
                        y={canvasCenter - size * 0.12}
                        width={size * 0.8}
                        height={size * 0.24}
                        color={color}
                        opacity={0.5}
                    >
                        <BlurMask blur={30} style="normal" />
                    </Oval>

                    {/* Vertical Cross Lobe */}
                    <Oval
                        x={canvasCenter - size * 0.12}
                        y={canvasCenter - size * 0.4}
                        width={size * 0.24}
                        height={size * 0.8}
                        color={color}
                        opacity={0.3}
                    >
                        <BlurMask blur={30} style="normal" />
                    </Oval>
                </Canvas>
            </Animated.View>

        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        justifyContent: 'center',
        alignItems: 'center',
    },
});

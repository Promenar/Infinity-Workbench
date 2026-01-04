import React, { useEffect, useMemo } from 'react';
import { View } from 'react-native';
import { Canvas, Group, Circle, BlurMask, RadialGradient, vec } from '@shopify/react-native-skia';
import Animated, {
    useSharedValue,
    withRepeat,
    withTiming,
    useDerivedValue,
    Easing,
    cancelAnimation,
} from 'react-native-reanimated';

interface ParticleGlowProps {
    size: number;
    color: string;
    style?: any;
}

const LAYERS = 6;
const PARTICLES_PER_LAYER = 12;

export const ParticleEnergyGlow: React.FC<ParticleGlowProps> = ({ size, color, style }) => {
    const center = size / 2;
    const progress = useSharedValue(0);

    useEffect(() => {
        // Drive seamlessly from 0 to 1
        progress.value = withRepeat(
            withTiming(1, { duration: 20000, easing: Easing.linear }), // 20s loop for slowness
            -1,
            false
        );
        return () => cancelAnimation(progress);
    }, []);

    // Generate particles
    const particleGroups = useMemo(() => {
        return Array.from({ length: LAYERS }).map((_, layerIndex) => {
            // Band distribution
            const minRadius = size * 0.1 + (layerIndex * size * 0.05);
            const maxRadius = minRadius + (size * 0.2);

            return Array.from({ length: PARTICLES_PER_LAYER }).map((__, i) => {
                const angle = (i / PARTICLES_PER_LAYER) * Math.PI * 2 + (Math.random() * 2);
                const r = minRadius + Math.random() * (maxRadius - minRadius);

                return {
                    cx: center + Math.cos(angle) * r,
                    cy: center + Math.sin(angle) * r,
                    r: (Math.random() * 4) + 2 + (layerIndex * 0.5),
                    opacity: 0.3 + Math.random() * 0.5,
                };
            });
        });
    }, [size, center]);

    // Seamless Rotations: All multipliers must be INTEGERS to ensure start(0) == end(2PI*k)
    const TWO_PI = Math.PI * 2;

    // Base Speed: 1 rotation per 20s
    const rotate1 = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 1 }]);
    const rotate2 = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 1 }]);
    const rotate3 = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 2 }]); // 2x speed
    const rotateSlow = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 1 }]);
    const rotateFast = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 2 }]);
    const rotateBack = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 2 }]);

    return (
        <View style={[{ width: size, height: size }, style]}>
            <Canvas style={{ flex: 1 }}>
                {/* 1. Enhanced Core Glow (Brighter) */}
                <Circle cx={center} cy={center} r={size * 0.35} opacity={1.0}>
                    <RadialGradient
                        c={vec(center, center)}
                        r={size * 0.35}
                        colors={['white', color, 'transparent']}
                        positions={[0, 0.5, 1]}
                    />
                    {/* Reduced Blur slightly to make core punchier */}
                    <BlurMask blur={25} style="normal" />
                </Circle>

                {/* 2. Particle Cloud Layers */}

                {/* Layer 1: Core Haze */}
                <Group origin={vec(center, center)} transform={rotate1}>
                    {particleGroups[0].map((p, i) => (
                        <Circle key={`l1-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={0.6} />
                    ))}
                    <BlurMask blur={10} style="normal" />
                </Group>

                {/* Layer 2: Counter-Rotating Texture */}
                <Group origin={vec(center, center)} transform={rotate2}>
                    {particleGroups[1].map((p, i) => (
                        <Circle key={`l2-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={0.5} />
                    ))}
                    <BlurMask blur={8} style="normal" />
                </Group>

                {/* Layer 3: Formerly "Sparks", now soft cloud (White removed) */}
                <Group origin={vec(center, center)} transform={rotateFast}>
                    {particleGroups[2].map((p, i) => (
                        // Changed color to 'color', increased blur
                        <Circle key={`l3-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={0.4} />
                    ))}
                    <BlurMask blur={12} style="normal" />
                </Group>

                {/* Layer 4: Volumetric Body */}
                <Group origin={vec(center, center)} transform={rotateSlow}>
                    {particleGroups[3].map((p, i) => (
                        <Circle key={`l4-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={0.3} />
                    ))}
                    <BlurMask blur={15} style="normal" />
                </Group>

                {/* Layer 5: Fast Outer Wisps */}
                <Group origin={vec(center, center)} transform={rotateBack}>
                    {particleGroups[4].map((p, i) => (
                        <Circle key={`l5-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={0.2} />
                    ))}
                    <BlurMask blur={20} style="normal" />
                </Group>

            </Canvas>
        </View>
    );
};

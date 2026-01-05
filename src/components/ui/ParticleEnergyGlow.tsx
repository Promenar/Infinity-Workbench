import React, { useEffect, useMemo } from 'react';
import { View, ViewStyle, StyleProp } from 'react-native';
import { Canvas, Group, Circle, BlurMask, RadialGradient, vec } from '@shopify/react-native-skia';
import Animated, {
    useSharedValue,
    withRepeat,
    withTiming,
    useDerivedValue,
    Easing,
    cancelAnimation,
} from 'react-native-reanimated';

interface ParticleEnergyGlowProps {
    size: number;
    color: string;
    style?: StyleProp<ViewStyle>;
    isDark?: boolean; // Add isDark prop for theme-aware rendering
}

// Visual Configuration
const LAYERS = 6;
const PARTICLES_PER_LAYER = 15;

export const ParticleEnergyGlow: React.FC<ParticleEnergyGlowProps> = ({ size, color, style, isDark = true }) => {
    // ---------------------------------------------------------------------------
    // OVERBOUND CANVAS STRATEGY
    // ---------------------------------------------------------------------------
    // Logical container size remains `size` (e.g., 160).
    // Drawing Canvas size is expanded (e.g., 320) to prevent Blur clipping.
    const canvasSize = size * 2.0;
    const canvasCenter = canvasSize / 2;
    const offset = (size - canvasSize) / 2; // Centers the large canvas over the container

    const progress = useSharedValue(0);

    // Dynamic Opacity Settings based on Theme
    // Light Mode: Reduce Core Glow significantly to avoid "muddy shadow", keep particles visible
    // Dark Mode: Full intensity
    const coreGlowOpacity = isDark ? 1.0 : 0.2;
    const particleBaseOpacity = isDark ? 0.6 : 0.8; // Boost particle opacity slightly in light mode

    useEffect(() => {
        // Drive seamlessly from 0 to 1
        progress.value = withRepeat(
            withTiming(1, { duration: 20000, easing: Easing.linear }),
            -1,
            false
        );
        return () => cancelAnimation(progress);
    }, []);

    // Generate particles based on the NEW canvas size logic?
    // Actually, we want the particles to look the same size relative to the *Logical* size.
    // So we use `size` for radius calculations, but position them relative to `canvasCenter`.
    const particleGroups = useMemo(() => {
        return Array.from({ length: LAYERS }).map((_, layerIndex) => {
            // Volumetric Band distribution relative to LOGICAL size
            // Reduced by ~1/3 as requested (0.15 -> 0.10, 0.05 -> 0.033)
            const minRadius = size * 0.10 + (layerIndex * size * 0.033);
            const maxRadius = minRadius + (size * 0.14); // 0.2 -> 0.14

            return Array.from({ length: PARTICLES_PER_LAYER }).map((__, i) => {
                const angle = (i / PARTICLES_PER_LAYER) * Math.PI * 2 + (Math.random() * 2);
                const r = minRadius + Math.random() * (maxRadius - minRadius);

                return {
                    cx: canvasCenter + Math.cos(angle) * r, // Use canvasCenter!
                    cy: canvasCenter + Math.sin(angle) * r,
                    r: (Math.random() * 4) + 2 + (layerIndex * 0.5),
                    opacity: (0.3 + Math.random() * 0.5) * particleBaseOpacity, // Apply base opacity adjustment
                };
            });
        });
    }, [size, canvasCenter, particleBaseOpacity]);

    // Seamless Rotations
    const TWO_PI = Math.PI * 2;
    const rotate1 = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 1 }]);
    const rotate2 = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 1 }]);
    const rotate3 = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 2 }]);
    const rotateSlow = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 1 }]);
    const rotateFast = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 2 }]);
    const rotateBack = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 2 }]);

    return (
        // overflow: 'visible' allows the large canvas to show outside the logical bounds
        <View style={[{ width: size, height: size, overflow: 'visible' }, style]}>
            <View
                style={{
                    position: 'absolute',
                    width: canvasSize,
                    height: canvasSize,
                    left: offset,
                    top: offset
                }}
            >
                <Canvas style={{ flex: 1 }}>
                    {/* 1. Core Glow - Adjusted for Light Mode & Resized (0.35 -> 0.24) */}
                    <Circle cx={canvasCenter} cy={canvasCenter} r={size * 0.24} opacity={coreGlowOpacity}>
                        <RadialGradient
                            c={vec(canvasCenter, canvasCenter)}
                            r={size * 0.24}
                            colors={['white', color, 'transparent']}
                            positions={[0, 0.5, 1]}
                        />
                        <BlurMask blur={30} style="normal" />
                    </Circle>

                    {/* 2. Particle Cloud Layers */}

                    <Group origin={vec(canvasCenter, canvasCenter)} transform={rotate1}>
                        {particleGroups[0].map((p, i) => (
                            <Circle key={`l1-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={p.opacity} />
                        ))}
                        <BlurMask blur={10} style="normal" />
                    </Group>

                    <Group origin={vec(canvasCenter, canvasCenter)} transform={rotate2}>
                        {particleGroups[1].map((p, i) => (
                            <Circle key={`l2-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={p.opacity * 0.8} />
                        ))}
                        <BlurMask blur={8} style="normal" />
                    </Group>

                    <Group origin={vec(canvasCenter, canvasCenter)} transform={rotateFast}>
                        {particleGroups[2].map((p, i) => (
                            <Circle key={`l3-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={p.opacity * 0.6} />
                        ))}
                        <BlurMask blur={12} style="normal" />
                    </Group>

                    <Group origin={vec(canvasCenter, canvasCenter)} transform={rotateSlow}>
                        {particleGroups[3].map((p, i) => (
                            <Circle key={`l4-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={p.opacity * 0.5} />
                        ))}
                        <BlurMask blur={15} style="normal" />
                    </Group>

                    <Group origin={vec(canvasCenter, canvasCenter)} transform={rotateBack}>
                        {particleGroups[4].map((p, i) => (
                            <Circle key={`l5-${i}`} cx={p.cx} cy={p.cy} r={p.r} color={color} opacity={p.opacity * 0.3} />
                        ))}
                        <BlurMask blur={20} style="normal" />
                    </Group>

                    <Group origin={vec(canvasCenter, canvasCenter)} transform={rotate1}>
                        {particleGroups[5].map((p, i) => (
                            <Circle key={`l6-${i}`} cx={p.cx} cy={p.cy} r={p.r * 1.5} color={color} opacity={p.opacity * 0.2} />
                        ))}
                        <BlurMask blur={30} style="normal" />
                    </Group>

                </Canvas>
            </View>
        </View>
    );
};

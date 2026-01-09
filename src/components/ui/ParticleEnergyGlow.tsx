import React, { useEffect, useMemo } from 'react';
import { View, ViewStyle, StyleProp } from 'react-native';
import { Canvas, Group, Circle, BlurMask, RadialGradient, vec, ColorMatrix } from '@shopify/react-native-skia';
import {
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
    isDark?: boolean;
}

const LAYERS = 6;
const PARTICLES_PER_LAYER = 15;
const HDR_MULTIPLIER = 8.0;
const HDR_BOOST_MATRIX = [
    HDR_MULTIPLIER, 0, 0, 0, 0,
    0, HDR_MULTIPLIER, 0, 0, 0,
    0, 0, HDR_MULTIPLIER, 0, 0,
    0, 0, 0, 1, 0,
];

export const ParticleEnergyGlow: React.FC<ParticleEnergyGlowProps> = ({ size, color, style, isDark = true }) => {
    const canvasSize = size * 2.0;
    const canvasCenter = canvasSize / 2;
    const offset = (size - canvasSize) / 2;

    const progress = useSharedValue(0);

    const coreGlowOpacity = isDark ? 0.8 : 0.2;
    const particleBaseOpacity = isDark ? 0.5 : 0.8;

    useEffect(() => {
        progress.value = withRepeat(
            withTiming(1, { duration: 20000, easing: Easing.linear }),
            -1,
            false
        );
        return () => cancelAnimation(progress);
    }, []);

    const particleGroups = useMemo(() => {
        return Array.from({ length: LAYERS }).map((_, layerIndex) => {
            const minRadius = size * 0.10 + (layerIndex * size * 0.033);
            const maxRadius = minRadius + (size * 0.14);

            return Array.from({ length: PARTICLES_PER_LAYER }).map((__, i) => {
                const angle = (i / PARTICLES_PER_LAYER) * Math.PI * 2 + (Math.random() * 2);
                const r = minRadius + Math.random() * (maxRadius - minRadius);

                return {
                    cx: canvasCenter + Math.cos(angle) * r,
                    cy: canvasCenter + Math.sin(angle) * r,
                    r: (Math.random() * 4) + 2 + (layerIndex * 0.5),
                    opacity: (0.3 + Math.random() * 0.5) * particleBaseOpacity,
                };
            });
        });
    }, [size, canvasCenter, particleBaseOpacity]);

    const TWO_PI = Math.PI * 2;
    const rotate1 = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 1 }]);
    const rotate2 = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 1 }]);
    const rotateFast = useDerivedValue(() => [{ rotate: progress.value * TWO_PI * 2 }]);
    const rotateSlow = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 1 }]);
    const rotateBack = useDerivedValue(() => [{ rotate: -progress.value * TWO_PI * 2 }]);

    return (
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
                    <Group>
                        <ColorMatrix matrix={HDR_BOOST_MATRIX} />
                        <Circle cx={canvasCenter} cy={canvasCenter} r={size * 0.24} opacity={coreGlowOpacity}>
                            <RadialGradient
                                c={vec(canvasCenter, canvasCenter)}
                                r={size * 0.24}
                                colors={['white', color, 'transparent']}
                                positions={[0, 0.5, 1]}
                            />
                            <BlurMask blur={40} style="normal" />
                        </Circle>
                        <Group blendMode="plus">
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
                        </Group>
                    </Group>
                </Canvas>
            </View>
        </View>
    );
};
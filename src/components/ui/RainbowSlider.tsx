import React, { useState, useEffect } from 'react';
import { View, StyleSheet, Platform } from 'react-native';
import Svg, { Defs, LinearGradient, Rect, Stop } from 'react-native-svg';
import Slider from '@react-native-community/slider';

interface RainbowSliderProps {
    value: string; // Hex color
    onValueChange: (color: string) => void;
}

export const RainbowSlider: React.FC<RainbowSliderProps> = ({ value, onValueChange }) => {
    // Simple Hex to HSL (Hue only)
    const getHueFromHex = (hex: string) => {
        hex = hex.replace('#', '');
        const r = parseInt(hex.substring(0, 2), 16) / 255;
        const g = parseInt(hex.substring(2, 4), 16) / 255;
        const b = parseInt(hex.substring(4, 6), 16) / 255;

        const max = Math.max(r, g, b);
        const min = Math.min(r, g, b);
        let h = 0;

        if (max === min) {
            h = 0;
        } else if (max === r) {
            h = (g - b) / (max - min);
        } else if (max === g) {
            h = 2 + (b - r) / (max - min);
        } else {
            h = 4 + (r - g) / (max - min);
        }

        h = h * 60;
        if (h < 0) h += 360;
        return h;
    };

    // Simple HSL to Hex (S=100%, L=50% for vivid colors)
    const hslToHex = (h: number, s: number, l: number) => {
        s /= 100;
        l /= 100;

        const c = (1 - Math.abs(2 * l - 1)) * s;
        const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
        const m = l - c / 2;
        let r = 0, g = 0, b = 0;

        if (0 <= h && h < 60) {
            r = c; g = x; b = 0;
        } else if (60 <= h && h < 120) {
            r = x; g = c; b = 0;
        } else if (120 <= h && h < 180) {
            r = 0; g = c; b = x;
        } else if (180 <= h && h < 240) {
            r = 0; g = x; b = c;
        } else if (240 <= h && h < 300) {
            r = x; g = 0; b = c;
        } else if (300 <= h && h < 360) {
            r = c; g = 0; b = x;
        }

        const toHex = (n: number) => {
            const hex = Math.round((n + m) * 255).toString(16);
            return hex.length === 1 ? '0' + hex : hex;
        };

        return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
    };

    const [hue, setHue] = useState(getHueFromHex(value));

    // Sync internal hue if external value changes drastically (optional, but good for presets)
    // Logic: If the external color's hue is significantly different, update slider.
    useEffect(() => {
        const currentHue = getHueFromHex(value);
        // Only update if difference is significant to prevent fighting during slide
        if (Math.abs(currentHue - hue) > 5) {
            setHue(currentHue);
        }
    }, [value]);

    return (
        <View style={styles.container}>
            <View style={styles.gradientContainer}>
                <Svg height="100%" width="100%">
                    <Defs>
                        <LinearGradient id="rainbow" x1="0" y1="0" x2="1" y2="0">
                            <Stop offset="0" stopColor="#ff0000" />
                            <Stop offset="0.17" stopColor="#ffff00" />
                            <Stop offset="0.33" stopColor="#00ff00" />
                            <Stop offset="0.5" stopColor="#00ffff" />
                            <Stop offset="0.67" stopColor="#0000ff" />
                            <Stop offset="0.83" stopColor="#ff00ff" />
                            <Stop offset="1" stopColor="#ff0000" />
                        </LinearGradient>
                    </Defs>
                    <Rect width="100%" height="100%" fill="url(#rainbow)" rx={10} ry={10} />
                </Svg>
            </View>
            <Slider
                style={styles.slider}
                minimumValue={0}
                maximumValue={360}
                step={1}
                value={hue}
                onValueChange={(val) => {
                    setHue(val);
                    // Convert to Hex with S=100, L=50 for pure vivid colors
                    // Or allow slight adjustment? For now, stick to standard vivid selection.
                    const newColor = hslToHex(val, 100, 50);
                    onValueChange(newColor);
                }}
                minimumTrackTintColor="transparent"
                maximumTrackTintColor="transparent"
                thumbTintColor={Platform.OS === 'android' ? 'white' : undefined} // iOS uses default shadow
            />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        height: 30, // Taller for better touch target
        justifyContent: 'center',
        marginVertical: 10,
    },
    gradientContainer: {
        ...StyleSheet.absoluteFillObject,
        height: 12, // Visual bar height
        top: 9, // Centered vertically (30 - 12) / 2 = 9
        borderRadius: 6,
        overflow: 'hidden',
    },
    slider: {
        width: '100%',
        height: 40, // Expanded touch area
        zIndex: 10,
    },
});

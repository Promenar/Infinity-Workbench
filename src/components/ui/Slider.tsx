import React from 'react';
import Slider from '@react-native-community/slider';
import { useTheme } from '../../theme/ThemeProvider';

interface Props {
    value: number;
    onValueChange: (value: number) => void;
    minimumValue: number;
    maximumValue: number;
    step?: number;
    disabled?: boolean;
    className?: string; // Suppress className warning if passed via nativewind
}

export const ThemedSlider: React.FC<Props> = ({ ...props }) => {
    const { isDark } = useTheme();
    return (
        <Slider
            style={{ width: '100%', height: 40 }}
            minimumTrackTintColor="#6366f1"
            maximumTrackTintColor={isDark ? '#3f3f46' : '#e2e8f0'}
            thumbTintColor="#6366f1"
            {...props}
        />
    );
};

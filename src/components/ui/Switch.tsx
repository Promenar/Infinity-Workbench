import React, { useEffect } from 'react';
import { TouchableOpacity, StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  interpolateColor,
  withTiming,
} from 'react-native-reanimated';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from '../../lib/haptics';

interface SwitchProps {
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
}

export const Switch: React.FC<SwitchProps> = ({ value, onValueChange, disabled = false }) => {
  const { isDark, colors } = useTheme();

  // 动画共享值：0 为关闭，1 为开启
  const progress = useSharedValue(value ? 1 : 0);

  useEffect(() => {
    progress.value = withSpring(value ? 1 : 0, {
      damping: 20,
      stiffness: 200,
    });
  }, [value]);

  const handleToggle = () => {
    if (disabled) return;

    setTimeout(() => {
      Haptics.selectionAsync();
      onValueChange(!value);
    }, 10);
  };

  const animatedTrackStyle = useAnimatedStyle(() => {
    const backgroundColor = interpolateColor(
      progress.value,
      [0, 1],
      isDark
        ? ['rgba(255, 255, 255, 0.1)', colors.opacity30] // Dark mode: subtle grey -> dynamic tint
        : ['rgba(0, 0, 0, 0.05)', colors.opacity20], // Light mode: grey -> dynamic tint
    );

    const borderColor = interpolateColor(
      progress.value,
      [0, 1],
      isDark
        ? ['rgba(255, 255, 255, 0.15)', colors.opacity30]
        : ['rgba(0, 0, 0, 0.1)', colors.opacity20],
    );

    return {
      backgroundColor,
      borderColor,
    };
  });

  const animatedThumbStyle = useAnimatedStyle(() => {
    // 总长度 50，圆点直径 26，边距 2 -> 移动距离 = 50 - 26 - 4 = 20
    const translateX = progress.value * 20;

    const backgroundColor = interpolateColor(
      progress.value,
      [0, 1],
      isDark
        ? ['#94a3b8', colors[500]] // slate -> dynamic primary
        : ['#cbd5e1', colors[500]], // slate-light -> dynamic primary
    );

    return {
      transform: [{ translateX }],
      backgroundColor,
    };
  });

  return (
    <TouchableOpacity
      activeOpacity={0.8}
      onPress={handleToggle}
      disabled={disabled}
      style={[styles.track, disabled && { opacity: 0.5 }]}
    >
      <Animated.View style={[styles.innerTrack, animatedTrackStyle]}>
        <Animated.View style={[styles.thumb, animatedThumbStyle]} />
      </Animated.View>
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  track: {
    width: 50,
    height: 30,
    justifyContent: 'center',
  },
  innerTrack: {
    width: 50,
    height: 28,
    borderRadius: 14,
    borderWidth: 1.5,
    padding: 2,
  },
  thumb: {
    width: 22,
    height: 22,
    borderRadius: 11,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
    elevation: 2,
  },
});

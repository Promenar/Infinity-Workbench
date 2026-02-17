import React, { useEffect } from 'react';
import { TouchableOpacity, StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  interpolateColor,
} from 'react-native-reanimated';
import { useTheme } from '../../theme/ThemeProvider';
import { Shadows } from '../../theme/glass';
import * as Haptics from '../../lib/haptics';

interface SwitchProps {
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
}

export const Switch = React.memo(({ value, onValueChange, disabled = false }: SwitchProps) => {
  const { isDark, colors } = useTheme();

  // 动画共享值：0 为关闭，1 为开启
  const progress = useSharedValue(value ? 1 : 0);
  const isMounted = useSharedValue(false);

  // 预计算颜色值，避免在 worklet 中访问 JS 线程变量
  const trackBgInactive = isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.05)';
  const trackBgActive = isDark ? colors.opacity30 : colors.opacity20;
  const trackBorderInactive = isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(0, 0, 0, 0.1)';
  const trackBorderActive = isDark ? colors.opacity30 : colors.opacity20;
  const thumbBgInactive = isDark ? 'rgba(255, 255, 255, 0.4)' : 'rgba(0, 0, 0, 0.25)';
  const thumbBgActive = colors[500];

  useEffect(() => {
    // 性能优化：首次挂载建议直接设置值，避免 spring 导致初次渲染出现微小位移/闪烁
    if (!isMounted.value) {
      progress.value = value ? 1 : 0;
      isMounted.value = true;
      return;
    }

    // 随后的变化使用弹簧动画
    progress.value = withSpring(value ? 1 : 0, {
      damping: 25,
      stiffness: 250,
      mass: 0.5,
    });
  }, [value]);

  const handleToggle = () => {
    if (disabled) return;

    // 延迟 10ms 执行原生调用，防御死锁
    setTimeout(() => {
      Haptics.selectionAsync();
      onValueChange(!value);
    }, 10);
  };

  const animatedTrackStyle = useAnimatedStyle(() => {
    'worklet';
    return {
      backgroundColor: interpolateColor(progress.value, [0, 1], [trackBgInactive, trackBgActive]),
      borderColor: interpolateColor(progress.value, [0, 1], [trackBorderInactive, trackBorderActive]),
    };
  });

  const animatedThumbStyle = useAnimatedStyle(() => {
    'worklet';
    return {
      transform: [{ translateX: progress.value * 20 }],
      backgroundColor: interpolateColor(progress.value, [0, 1], [thumbBgInactive, thumbBgActive]),
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
});

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
    ...Shadows.sm,
  },
});

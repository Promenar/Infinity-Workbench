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

export const Switch = React.memo(({ value, onValueChange, disabled = false }: SwitchProps) => {
  const { isDark, colors } = useTheme();

  // 动画共享值：0 为关闭，1 为开启
  const progress = useSharedValue(value ? 1 : 0);
  const isMounted = useSharedValue(false);

  useEffect(() => {
    // 性能优化：首次挂载建议直接设置值，避免 spring 导致初次渲染出现微小位移/闪烁
    if (!isMounted.value) {
      progress.value = value ? 1 : 0;
      isMounted.value = true;
      return;
    }

    // 随后的变化使用弹簧动画
    progress.value = withSpring(value ? 1 : 0, {
      damping: 25, // 略微增加阻尼，减少视觉抖动
      stiffness: 250,
      mass: 0.5, // 减小质量，让反馈更灵敏
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
    return {
      backgroundColor: interpolateColor(
        progress.value,
        [0, 1],
        isDark
          ? ['rgba(255, 255, 255, 0.1)', colors.opacity30]
          : ['rgba(0, 0, 0, 0.05)', colors.opacity20],
      ),
      borderColor: interpolateColor(
        progress.value,
        [0, 1],
        isDark
          ? ['rgba(255, 255, 255, 0.15)', colors.opacity30]
          : ['rgba(0, 0, 0, 0.1)', colors.opacity20],
      ),
    };
  });

  const animatedThumbStyle = useAnimatedStyle(() => {
    return {
      transform: [{ translateX: progress.value * 20 }],
      backgroundColor: interpolateColor(
        progress.value,
        [0, 1],
        isDark
          ? ['#94a3b8', colors[500]]
          : ['#cbd5e1', colors[500]],
      ),
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
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
    elevation: 2,
  },
});

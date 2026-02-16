import React, { useCallback } from 'react';
import { View, ViewProps, Pressable, ViewStyle } from 'react-native';
import { twMerge } from 'tailwind-merge';
import { BlurView } from 'expo-blur';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import { useTheme } from '../../theme/ThemeProvider';
import { Glass } from '../../theme/glass';
import * as Haptics from '../../lib/haptics';

interface CardProps extends ViewProps {
  variant?: 'default' | 'elevated' | 'glass';
  onPress?: () => void;
  className?: string;
}

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

const SPRING_CONFIG = {
  damping: 20,
  stiffness: 400,
  mass: 0.5,
};

export function Card({ variant = 'default', onPress, className, children, style, ...props }: CardProps) {
  const { isDark, colors } = useTheme();
  const scale = useSharedValue(1);

  const baseStyle = 'bg-surface-primary border border-border-default rounded-[20px] overflow-hidden';

  const variants = {
    default: '',
    elevated: 'shadow-sm dark:shadow-none',
    glass: '',
  };

  const containerClass = twMerge(
    baseStyle,
    variants[variant],
    variant === 'glass' && 'bg-transparent border-indigo-500/10 dark:border-white/10',
    className,
  );

  const handlePressIn = useCallback(() => {
    scale.value = withSpring(0.97, SPRING_CONFIG);
  }, [scale]);

  const handlePressOut = useCallback(() => {
    scale.value = withSpring(1, SPRING_CONFIG);
  }, [scale]);

  const handlePress = useCallback(() => {
    if (!onPress) return;
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      onPress();
    }, 10);
  }, [onPress]);

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
  }));

  const content = (
    <>
      {variant === 'glass' && (
        <BlurView
          intensity={Glass.Overlay.intensity}
          tint={isDark ? Glass.Overlay.tint.dark : Glass.Overlay.tint.light}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        />
      )}
      <View style={variant === 'glass' ? {
        backgroundColor: `rgba(${isDark ? Glass.Overlay.baseColor.dark : Glass.Overlay.baseColor.light}, ${isDark ? Glass.Overlay.opacity.dark : Glass.Overlay.opacity.light})`
      } : undefined}>
        {children}
      </View>
    </>
  );

  if (onPress) {
    return (
      <AnimatedPressable
        className={containerClass}
        onPress={handlePress}
        onPressIn={handlePressIn}
        onPressOut={handlePressOut}
        style={[style, animatedStyle]}
        {...props}
      >
        {content}
      </AnimatedPressable>
    );
  }

  return (
    <View className={containerClass} style={style} {...props}>
      {content}
    </View>
  );
}

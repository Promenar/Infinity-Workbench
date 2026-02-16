import React, { useCallback } from 'react';
import { Pressable, ActivityIndicator, View, ViewStyle } from 'react-native';
import { twMerge } from 'tailwind-merge';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import { Typography } from './Typography';
import * as Haptics from '../../lib/haptics';
import { useTheme } from '../../theme/ThemeProvider';

interface ButtonProps {
  variant?: 'primary' | 'secondary' | 'ghost' | 'outline' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  label?: string;
  icon?: React.ReactNode;
  textClassName?: string;
  className?: string;
  style?: ViewStyle;
  disabled?: boolean;
  onPress?: () => void;
  children?: React.ReactNode;
}

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

const SPRING_CONFIG = {
  damping: 20,
  stiffness: 400,
  mass: 0.5,
};

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  label,
  icon,
  className,
  textClassName,
  style,
  disabled,
  onPress,
  children,
}: ButtonProps) {
  const { colors } = useTheme();
  const scale = useSharedValue(1);

  const baseStyle = 'flex-row items-center justify-center rounded-lg font-medium';

  const variants = {
    primary: '',
    secondary: 'bg-surface-secondary border border-border-default',
    ghost: 'bg-transparent',
    outline: 'bg-transparent border border-border-default',
    danger: 'bg-red-50 dark:bg-red-900/20',
  };

  const sizes = {
    sm: 'px-3 py-1.5',
    md: 'px-4 py-2.5',
    lg: 'px-6 py-3.5',
  };

  const textColors = {
    primary: 'text-white',
    secondary: 'text-text-primary',
    ghost: 'text-text-secondary',
    outline: 'text-text-primary',
    danger: 'text-red-600 dark:text-red-400',
  };

  const textSizes = {
    sm: 'text-sm',
    md: 'text-base',
    lg: 'text-lg',
  };

  const containerClass = twMerge(
    baseStyle,
    variants[variant],
    sizes[size],
    (disabled || loading) && 'opacity-50',
    className,
  );

  const handlePressIn = useCallback(() => {
    scale.value = withSpring(0.96, SPRING_CONFIG);
  }, [scale]);

  const handlePressOut = useCallback(() => {
    scale.value = withSpring(1, SPRING_CONFIG);
  }, [scale]);

  const handlePress = useCallback(() => {
    if (disabled || loading) return;
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      onPress?.();
    }, 10);
  }, [disabled, loading, onPress]);

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
  }));

  const getBackgroundColor = (): string | undefined => {
    if (variant !== 'primary') return undefined;
    if (disabled || loading) return colors[300];
    return colors[600];
  };

  const renderContent = () => {
    if (children) {
      return children;
    }

    if (loading) {
      return (
        <ActivityIndicator size="small" color={variant === 'primary' ? 'white' : colors[500]} />
      );
    }

    return (
      <>
        {icon && <View className="mr-2">{icon}</View>}
        {label && (
          <Typography
            variant="body"
            className={twMerge(
              `${textColors[variant]} ${textSizes[size]} font-medium`,
              textClassName,
            )}
          >
            {label}
          </Typography>
        )}
      </>
    );
  };

  return (
    <AnimatedPressable
      className={containerClass}
      disabled={disabled || loading}
      onPress={handlePress}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      style={[
        { backgroundColor: getBackgroundColor() },
        style,
        animatedStyle,
      ]}
    >
      {renderContent()}
    </AnimatedPressable>
  );
}

import React from 'react';
import { TouchableOpacity, ActivityIndicator, View, TouchableOpacityProps } from 'react-native';
import { twMerge } from 'tailwind-merge';
import { Typography } from './Typography';
import * as Haptics from '../../lib/haptics';
import { Colors } from '../../theme/colors';
import { useTheme } from '../../theme/ThemeProvider';

interface ButtonProps extends TouchableOpacityProps {
  variant?: 'primary' | 'secondary' | 'ghost' | 'outline' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  label: string;
  icon?: React.ReactNode;
  textClassName?: string;
}

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  label,
  icon,
  className,
  textClassName,
  disabled,
  onPress,
  ...props
}: ButtonProps) {
  const { colors } = useTheme();

  const baseStyle =
    'flex-row items-center justify-center rounded-lg font-medium transition-all active:scale-[0.98]';

  const variants = {
    primary: '', // Handled via style for dynamic color
    secondary: 'bg-surface-secondary active:bg-surface-tertiary border border-border-default',
    ghost: 'bg-transparent active:bg-surface-secondary',
    outline: 'bg-transparent border border-border-default active:bg-surface-secondary',
    danger: 'bg-red-50 active:bg-red-100 dark:bg-red-900/20',
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

  const handlePress = (e: any) => {
    if (disabled || loading) return;

    // Native Bridge Defensive Driving: Yield to UI thread before sending bridge commands
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      onPress && onPress(e);
    }, 10);
  };

  return (
    <TouchableOpacity
      className={containerClass}
      disabled={disabled || loading}
      activeOpacity={0.8}
      onPress={handlePress}
      style={[
        variant === 'primary' && !disabled && !loading && { backgroundColor: colors[600] },
        variant === 'primary' && (disabled || loading) && { backgroundColor: colors[300] },
        props.style as any
      ]}
      {...props}
    >
      {loading ? (
        <ActivityIndicator size="small" color={variant === 'primary' ? 'white' : colors[500]} />
      ) : (
        <>
          {icon && <View className="mr-2">{icon}</View>}
          <Typography
            variant="body"
            className={twMerge(
              `${textColors[variant]} ${textSizes[size]} font-medium`,
              textClassName,
            )}
          >
            {label}
          </Typography>
        </>
      )}
    </TouchableOpacity>
  );
}

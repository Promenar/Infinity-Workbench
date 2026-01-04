import { View, ViewProps, TouchableOpacity, TouchableOpacityProps } from 'react-native';
import { twMerge } from 'tailwind-merge';

interface CardProps extends ViewProps {
  variant?: 'default' | 'elevated' | 'glass';
  onPress?: () => void;
  className?: string; // Explicitly add to props for TS
}

export function Card({ variant = 'default', onPress, className, children, ...props }: CardProps) {
  const baseStyle = 'bg-surface-primary border border-border-default rounded-xl overflow-hidden';

  const variants = {
    default: '',
    elevated: 'shadow-sm dark:shadow-none', // Avoid strong shadows in dark mode
    glass: 'bg-white/70 dark:bg-black/70 backdrop-blur-md', // NativeWind might fallback to solid if platform doesn't support blur natively easily without extra setup
  };

  const containerClass = twMerge(
    baseStyle,
    variants[variant],
    onPress && 'active:scale-[0.98] active:border-primary-500/50 transition-all',
    className,
  );

  if (onPress) {
    return (
      <TouchableOpacity
        className={containerClass}
        onPress={onPress}
        activeOpacity={1} // Handled by scale animation usually, or set to 0.7
        {...(props as TouchableOpacityProps)}
      >
        {children}
      </TouchableOpacity>
    );
  }

  return (
    <View className={containerClass} {...props}>
      {children}
    </View>
  );
}

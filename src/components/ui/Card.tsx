import { View, ViewProps, TouchableOpacity, TouchableOpacityProps } from 'react-native';
import { twMerge } from 'tailwind-merge';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';

interface CardProps extends ViewProps {
  variant?: 'default' | 'elevated' | 'glass';
  onPress?: () => void;
  className?: string; // Explicitly add to props for TS
}

export function Card({ variant = 'default', onPress, className, children, style, ...props }: CardProps) {
  const { isDark } = useTheme();

  const baseStyle = 'bg-surface-primary border border-border-default rounded-2xl overflow-hidden';

  const variants = {
    default: '',
    elevated: 'shadow-sm dark:shadow-none',
    glass: '', // Handled via style for native blur support consistency
  };

  const containerClass = twMerge(
    baseStyle,
    variants[variant],
    onPress && 'active:scale-[0.98] active:border-primary-500/50 transition-all',
    variant === 'glass' && 'bg-transparent border-indigo-500/10 dark:border-indigo-400/10',
    className,
  );

  const content = (
    <>
      {variant === 'glass' && (
        <BlurView
          intensity={isDark ? 30 : 60}
          tint={isDark ? 'dark' : 'light'}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        />
      )}
      <View style={variant === 'glass' ? { backgroundColor: isDark ? 'rgba(15, 17, 26, 0.6)' : 'rgba(255, 255, 255, 0.7)' } : null} className="flex-1">
        {children}
      </View>
    </>
  );

  if (onPress) {
    return (
      <TouchableOpacity
        className={containerClass}
        onPress={onPress}
        activeOpacity={1}
        style={style}
        {...(props as TouchableOpacityProps)}
      >
        {content}
      </TouchableOpacity>
    );
  }

  return (
    <View className={containerClass} style={style} {...props}>
      {content}
    </View>
  );
}

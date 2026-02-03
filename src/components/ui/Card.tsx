import { View, ViewProps, TouchableOpacity, TouchableOpacityProps } from 'react-native';
import { twMerge } from 'tailwind-merge';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';
import { Glass } from '../../theme/glass';

interface CardProps extends ViewProps {
  variant?: 'default' | 'elevated' | 'glass';
  onPress?: () => void;
  className?: string; // Explicitly add to props for TS
}

export function Card({ variant = 'default', onPress, className, children, style, ...props }: CardProps) {
  const { isDark } = useTheme();

  const baseStyle = 'bg-surface-primary border border-border-default rounded-[20px] overflow-hidden'; // Radius 20px (Golden Standard)

  const variants = {
    default: '',
    elevated: 'shadow-sm dark:shadow-none',
    glass: '', // Handled via style for native blur support consistency
  };

  const containerClass = twMerge(
    baseStyle,
    variants[variant],
    onPress && 'active:scale-[0.98] active:border-primary-500/50 transition-all',
    variant === 'glass' && 'bg-transparent border-indigo-500/10 dark:border-white/10', // Improved dark mode border visibility
    className,
  );

  const content = (
    <>
      {variant === 'glass' && (
        <BlurView
          intensity={isDark ? Glass.Overlay.intensity : Glass.Overlay.intensity}
          tint={isDark ? Glass.Overlay.tint.dark : Glass.Overlay.tint.light}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        />
      )}
      <View style={variant === 'glass' ? {
        backgroundColor: `rgba(${isDark ? Glass.Overlay.baseColor.dark : Glass.Overlay.baseColor.light}, ${isDark ? Glass.Overlay.opacity.dark : Glass.Overlay.opacity.light})`
      } : null}>
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

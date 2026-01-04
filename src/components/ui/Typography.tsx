import { Text, TextProps } from 'react-native';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

interface TypographyProps extends TextProps {
  variant?: 'largeTitle' | 'h1' | 'h2' | 'h3' | 'body' | 'label' | 'sectionHeader' | 'caption';
  className?: string;
  color?: 'primary' | 'secondary' | 'tertiary' | 'brand' | 'danger';
}

export function Typography({
  variant = 'body',
  className,
  color = 'primary',
  style,
  ...props
}: TypographyProps) {
  const baseStyle = 'font-sans'; // Assuming Roboto is default via NativeWind or system

  const variants = {
    largeTitle: 'text-3xl font-bold tracking-tighter',
    h1: 'text-2xl font-bold tracking-tight',
    h2: 'text-xl font-semibold tracking-tight',
    h3: 'text-lg font-semibold',
    body: 'text-base font-normal',
    label: 'text-[10px] font-medium',
    sectionHeader: 'text-sm font-bold uppercase tracking-wider text-text-tertiary',
    caption: 'text-xs font-normal text-text-secondary',
  };

  const colors = {
    primary: 'text-text-primary',
    secondary: 'text-text-secondary',
    tertiary: 'text-text-tertiary',
    brand: 'text-primary-500',
    danger: 'text-red-600 dark:text-red-400',
  };

  // sectionHeader has fixed color in design system, but we allow override
  const textClass = twMerge(
    baseStyle,
    variants[variant],
    variant !== 'sectionHeader' && colors[color],
    className,
  );

  return <Text className={textClass} style={style} {...props} />;
}

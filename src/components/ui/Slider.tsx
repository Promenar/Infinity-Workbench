import React, { useState, useEffect } from 'react';
import Slider from '@react-native-community/slider';
import { useTheme } from '../../theme/ThemeProvider';

interface Props {
  value: number;
  onValueChange: (value: number) => void;
  minimumValue: number;
  maximumValue: number;
  step?: number;
  disabled?: boolean;
  className?: string;
  useSlidingComplete?: boolean;
}

export const ThemedSlider: React.FC<Props> = ({
  value,
  onValueChange,
  useSlidingComplete = false,
  ...props
}) => {
  const { isDark, colors } = useTheme();
  const [localValue, setLocalValue] = useState(value);

  useEffect(() => {
    setLocalValue(value);
  }, [value]);

  if (useSlidingComplete) {
    return (
      <Slider
        style={{ width: '100%', height: 32 }}
        minimumTrackTintColor={colors[500]}
        maximumTrackTintColor={isDark ? '#3f3f46' : '#e2e8f0'}
        thumbTintColor={colors[500]}
        value={localValue}
        onValueChange={setLocalValue}
        onSlidingComplete={(val) => onValueChange(Math.round(val))}
        {...props}
      />
    );
  }

  return (
    <Slider
      style={{ width: '100%', height: 32 }}
      minimumTrackTintColor={colors[500]}
      maximumTrackTintColor={isDark ? '#3f3f46' : '#e2e8f0'}
      thumbTintColor={colors[500]}
      value={value}
      onValueChange={(val) => onValueChange(Math.round(val))}
      {...props}
    />
  );
};

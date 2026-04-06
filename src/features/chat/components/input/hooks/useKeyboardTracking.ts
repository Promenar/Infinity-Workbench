import { useState, useCallback, useEffect } from 'react';
import { Keyboard } from 'react-native';
import { useSharedValue, useAnimatedStyle, withTiming, interpolateColor } from 'react-native-reanimated';

interface UseKeyboardTrackingProps {
  isDark: boolean;
  agentColor: string;
  colors: any;
}

export const useKeyboardTracking = ({ isDark, agentColor, colors }: UseKeyboardTrackingProps) => {
  const [isFocused, setIsFocused] = useState(false);
  const focusProgress = useSharedValue(0);

  const focusBorderColorInactive = isDark ? '#374151' : '#E5E7EB';
  const focusBorderColorActive = agentColor || colors[500];

  const focusAnimatedStyle = useAnimatedStyle(() => ({
    borderColor: interpolateColor(focusProgress.value, [0, 1], [
      focusBorderColorInactive,
      focusBorderColorActive,
    ]),
    shadowOpacity: focusProgress.value * 0.12,
  }));

  const handleFocus = useCallback(() => {
    setIsFocused(true);
    focusProgress.value = withTiming(1, { duration: 200 });
  }, []);

  const handleBlur = useCallback(() => {
    setIsFocused(false);
    focusProgress.value = withTiming(0, { duration: 200 });
  }, []);

  useEffect(() => {
    const keyboardDidHideListener = Keyboard.addListener('keyboardDidHide', () => {
      if (isFocused) {
        setIsFocused(false);
        focusProgress.value = withTiming(0, { duration: 200 });
      }
    });
    return () => {
      keyboardDidHideListener.remove();
    };
  }, [isFocused]);

  return {
    isFocused,
    setIsFocused,
    focusProgress,
    focusAnimatedStyle,
    handleFocus,
    handleBlur,
  };
};

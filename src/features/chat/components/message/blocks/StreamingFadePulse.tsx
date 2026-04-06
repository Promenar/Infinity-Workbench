import React from 'react';
import { StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
} from 'react-native-reanimated';
import Svg, { Defs, LinearGradient as SvgLinearGradient, Rect, Stop } from 'react-native-svg';
import { useStreamingIndicator } from '../hooks/useStreamingIndicator';

const FADE_PULSE_HEIGHT = 72;

interface StreamingFadePulseProps {
  contentTrigger: number;
  isDark: boolean;
}

export const StreamingFadePulse: React.FC<StreamingFadePulseProps> = React.memo(({ contentTrigger, isDark }) => {
  const maskOpacity = useStreamingIndicator(contentTrigger);

  const animatedStyle = useAnimatedStyle(() => ({
    opacity: maskOpacity.value,
  }));

  const bgColor = isDark ? '#000000' : '#ffffff';

  return (
    <Animated.View
      pointerEvents="none"
      style={[
        {
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          height: FADE_PULSE_HEIGHT,
          zIndex: 10,
        },
        animatedStyle,
      ]}
    >
      <Svg width="100%" height="100%" style={StyleSheet.absoluteFill}>
        <Defs>
          <SvgLinearGradient id="streamFadeDiag" x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor={bgColor} stopOpacity="0" />
            <Stop offset="0.35" stopColor={bgColor} stopOpacity="0.1" />
            <Stop offset="0.65" stopColor={bgColor} stopOpacity="0.45" />
            <Stop offset="1" stopColor={bgColor} stopOpacity="0.85" />
          </SvgLinearGradient>
        </Defs>
        <Rect x="0" y="0" width="100%" height="100%" fill="url(#streamFadeDiag)" />
      </Svg>
    </Animated.View>
  );
});

import React, { useEffect } from 'react';
import { View } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  withSequence,
  withRepeat,
  cancelAnimation,
} from 'react-native-reanimated';
import type { SharedValue } from 'react-native-reanimated';

interface LoadingDotsProps {
  isDark: boolean;
  color?: string;
}

export const LoadingDots: React.FC<LoadingDotsProps> = React.memo(({ isDark, color }) => {
  const opacity1 = useSharedValue(0.3);
  const opacity2 = useSharedValue(0.3);
  const opacity3 = useSharedValue(0.3);

  useEffect(() => {
    const loop = (sv: any, delay: number) => {
      sv.value = withRepeat(
        withSequence(
          withTiming(0.3, { duration: delay }),
          withTiming(1, { duration: 500 }),
          withTiming(0.3, { duration: 500 }),
        ),
        -1,
        true,
      );
    };
    loop(opacity1, 0);
    loop(opacity2, 200);
    loop(opacity3, 400);

    return () => {
      cancelAnimation(opacity1);
      cancelAnimation(opacity2);
      cancelAnimation(opacity3);
    };
  }, []);

  const dotStyle = {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: color || (isDark ? '#e4e4e7' : '#27272a'),
    marginHorizontal: 2,
  };

  const anim1 = useAnimatedStyle(() => ({ opacity: opacity1.value }));
  const anim2 = useAnimatedStyle(() => ({ opacity: opacity2.value }));
  const anim3 = useAnimatedStyle(() => ({ opacity: opacity3.value }));

  return (
    <View style={[{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: 8 }, { height: 24 }]}>
      <Animated.View style={[dotStyle, anim1]} />
      <Animated.View style={[dotStyle, anim2]} />
      <Animated.View style={[dotStyle, anim3]} />
    </View>
  );
});

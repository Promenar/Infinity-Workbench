import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, Text, GestureResponderEvent } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  withSequence,
  runOnJS,
} from 'react-native-reanimated';
import * as Haptics from '../../../../lib/haptics';
import { MoreHorizontal } from 'lucide-react-native';
import { useMessageContext } from './MessageContext';

export const MessageMeta = React.memo<{
  modelName?: string;
  timestamp?: number;
  isDark: boolean;
  loopCount?: number;
}>(({ modelName, timestamp, isDark, loopCount }) => {
  const { onOpenMenu } = useMessageContext();
  const [showTooltip, setShowTooltip] = useState(false);
  const opacity = useSharedValue(0);

  useEffect(() => {
    if (showTooltip) {
      opacity.value = withSequence(
        withTiming(1, { duration: 200 }),
        withTiming(1, { duration: 2000 }), 
        withTiming(0, { duration: 300 }, (finished: boolean | undefined) => {
          if (finished) runOnJS(setShowTooltip)(false);
        })
      );
    }
  }, [showTooltip, opacity]);

  const animatedStyle = useAnimatedStyle(() => ({
    opacity: opacity.value,
    transform: [
      { translateX: -55 },
      { translateY: withTiming(showTooltip ? 0 : 6) }
    ],
  }));

  const getTimeStr = (ts: number) => {
    const date = new Date(ts);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();

    if (isToday) {
      return date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
      });
    } else {
      return date.toLocaleDateString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
      }) + ' ' + date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
      });
    }
  };

  const timeStr = timestamp ? getTimeStr(timestamp) : '';
  const insetShadowStyle = isDark
    ? { textShadowColor: 'rgba(0, 0, 0, 0.8)', textShadowOffset: { width: 0, height: -1 }, textShadowRadius: 0 } as any
    : { textShadowColor: 'rgba(255, 255, 255, 0.8)', textShadowOffset: { width: 0, height: 1 }, textShadowRadius: 0 } as any;

  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 2, gap: 5 }}>
      {modelName && (
        <Text style={[{
          fontSize: 10,
          color: isDark ? '#52525b' : '#d4d4d8',
          fontWeight: '500',
          textTransform: 'uppercase',
          letterSpacing: 0.8,
        }, insetShadowStyle]}>
          {modelName}
        </Text>
      )}
      {modelName && timeStr && (
        <Text style={[{ fontSize: 10, color: isDark ? '#3f3f46' : '#e4e4e7' }, insetShadowStyle]}>·</Text>
      )}
      {timeStr && (
        <Text style={[{ fontSize: 10, color: isDark ? '#3f3f46' : '#e4e4e7' }, insetShadowStyle]}>
          {timeStr}
        </Text>
      )}

      {loopCount !== undefined && loopCount > 0 && (
        <>
          <Text style={[{ fontSize: 10, color: isDark ? '#3f3f46' : '#e4e4e7' }, insetShadowStyle]}>·</Text>
          <View style={{ position: 'relative' }}>
            <TouchableOpacity
              activeOpacity={0.7}
              onPress={() => {
                setShowTooltip(true);
                setTimeout(() => {
                  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                }, 10);
              }}
              hitSlop={{ top: 15, bottom: 15, left: 10, right: 10 }}
            >
              <Text style={[{
                fontSize: 10,
                color: isDark ? '#52525b' : '#d4d4d8',
                fontWeight: '500',
                textTransform: 'uppercase',
                letterSpacing: 0.8,
                textAlign: 'center',
              }, insetShadowStyle]}>
                {loopCount}
              </Text>
            </TouchableOpacity>

            {showTooltip && (
              <Animated.View style={[
                {
                  position: 'absolute',
                  bottom: 14,
                  left: '50%',
                  backgroundColor: isDark ? '#27272a' : '#1f2937',
                  paddingHorizontal: 10,
                  paddingVertical: 4,
                  borderRadius: 6,
                  width: 110,
                  borderWidth: 1,
                  borderColor: isDark ? '#3f3f46' : '#374151',
                  shadowColor: "#000",
                  shadowOffset: { width: 0, height: 2 },
                  shadowOpacity: 0.25,
                  shadowRadius: 3.84,
                  elevation: 5,
                  zIndex: 9999
                },
                animatedStyle
              ]}>
                <Text style={{ color: '#fff', fontSize: 10, textAlign: 'center', fontWeight: '500' }}>
                  自动化执行轮数: {loopCount}
                </Text>
              </Animated.View>
            )}
          </View>
        </>
      )}

      <Text style={[{ fontSize: 10, color: isDark ? '#3f3f46' : '#e4e4e7' }, insetShadowStyle]}>·</Text>
      
      <TouchableOpacity
        onPress={(e: GestureResponderEvent) => onOpenMenu?.(e)}
        hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
        activeOpacity={0.6}
      >
        <MoreHorizontal size={14} color={isDark ? '#52525b' : '#a1a1aa'} />
      </TouchableOpacity>
    </View>
  );
});

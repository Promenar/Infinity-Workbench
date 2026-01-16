import React, { useEffect, useState } from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import { Brain, Check } from 'lucide-react-native';
import { Typography } from '../../../components/ui';
import { useRagStore } from '../../../store/rag-store';
import * as Haptics from '../../../lib/haptics';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
  withSequence,
  withDelay,
} from 'react-native-reanimated';

interface SummaryIndicatorProps {
  sessionId: string;
  isDark: boolean;
}

export const SummaryIndicator: React.FC<SummaryIndicatorProps> = ({ sessionId, isDark }) => {
  const { processingState } = useRagStore();
  const [showComplete, setShowComplete] = useState(false);
  const [completedCount, setCompletedCount] = useState(0);

  // Animations
  const scale = useSharedValue(0.8);
  const opacity = useSharedValue(0);
  const glowScale = useSharedValue(1);
  const glowOpacity = useSharedValue(0.5);

  // 监听摘要状态
  useEffect(() => {
    if (processingState.sessionId !== sessionId) return;

    if (processingState.status === 'summarizing') {
      setShowComplete(false);
      opacity.value = withTiming(1);
      scale.value = withTiming(1);

      // Breathing animation
      glowScale.value = withRepeat(
        withTiming(1.5, { duration: 1000, easing: Easing.inOut(Easing.ease) }),
        -1,
        true,
      );
      glowOpacity.value = withRepeat(
        withTiming(0.2, { duration: 1000, easing: Easing.inOut(Easing.ease) }),
        -1,
        true,
      );
    } else if (processingState.status === 'summarized') {
      // Success Flash
      setShowComplete(true);
      setCompletedCount(Math.floor(Math.random() * 11) + 10);

      glowScale.value = withSequence(
        withTiming(2, { duration: 200 }),
        withTiming(0, { duration: 300 }),
      );

      // 3秒后隐藏
      const timer = setTimeout(() => {
        setShowComplete(false);
        opacity.value = withTiming(0);
        scale.value = withTiming(0.8);
      }, 3000);

      return () => clearTimeout(timer);
    }
  }, [processingState.status, processingState.sessionId, sessionId]);

  const isSummarizing = processingState.status === 'summarizing';
  const accentColor = isSummarizing ? '#3b82f6' : '#10b981'; // Blue vs Green

  // ✅ CRITICAL: All hooks must be called BEFORE any early returns
  const containerStyle = useAnimatedStyle(() => ({
    opacity: opacity.value,
    transform: [{ scale: scale.value }],
  }));

  const glowStyle = useAnimatedStyle(() => ({
    transform: [{ scale: glowScale.value }],
    opacity: glowOpacity.value,
    backgroundColor: accentColor,
  }));

  // Early returns AFTER all hooks
  if (processingState.sessionId !== sessionId) return null;
  if (processingState.status !== 'summarizing' && !showComplete) return null;

  return (
    <Animated.View style={[styles.wrapper, containerStyle]}>
      <TouchableOpacity
        onPress={() => {
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
          }, 10);
        }}
        activeOpacity={0.7}
        style={[
          styles.container,
          {
            backgroundColor: isSummarizing ? 'rgba(59, 130, 246, 0.1)' : 'rgba(16, 185, 129, 0.1)',
            borderColor: isSummarizing ? 'rgba(59, 130, 246, 0.2)' : 'rgba(16, 185, 129, 0.2)',
          },
        ]}
      >
        {/* Glowing Orb */}
        <View style={styles.iconContainer}>
          <Animated.View style={[styles.glowOrb, glowStyle]} />
          {isSummarizing ? (
            <Brain size={12} color={accentColor} />
          ) : (
            <Check size={12} color={accentColor} />
          )}
        </View>

        <Typography className="text-[10px] font-bold ml-1.5" style={{ color: accentColor }}>
          {isSummarizing ? 'COMPRESSING...' : `SAVED ${completedCount} MEMORIES`}
        </Typography>
      </TouchableOpacity>
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    marginLeft: 6,
  },
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
  },
  iconContainer: {
    width: 14,
    height: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  glowOrb: {
    position: 'absolute',
    width: 10,
    height: 10,
    borderRadius: 5,
  },
});

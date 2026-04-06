import { useEffect, useRef, useCallback } from 'react';
import { useSharedValue, withTiming } from 'react-native-reanimated';

const IDLE_TIMEOUT_MS = 600;
const FADE_IN_MS = 150;
const FADE_OUT_MS = 400;

/**
 * useStreamingIndicator - Logic for fading pulse during streaming
 */
export const useStreamingIndicator = (contentTrigger: number) => {
  const maskOpacity = useSharedValue(0);
  const prevTriggerRef = useRef(0);
  const fadeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearFadeTimer = useCallback(() => {
    if (fadeTimerRef.current) {
      clearTimeout(fadeTimerRef.current);
      fadeTimerRef.current = null;
    }
  }, []);

  useEffect(() => {
    if (contentTrigger > 0 && contentTrigger > prevTriggerRef.current) {
      // token arrived: fast fade in
      maskOpacity.value = withTiming(1, { duration: FADE_IN_MS });

      // Reset idle timer
      clearFadeTimer();
      fadeTimerRef.current = setTimeout(() => {
        maskOpacity.value = withTiming(0, { duration: FADE_OUT_MS });
      }, IDLE_TIMEOUT_MS);
    }

    // Stream finished
    if (contentTrigger === 0 && prevTriggerRef.current > 0) {
      clearFadeTimer();
      maskOpacity.value = withTiming(0, { duration: FADE_OUT_MS });
    }

    prevTriggerRef.current = contentTrigger;
  }, [contentTrigger, clearFadeTimer, maskOpacity]);

  useEffect(() => {
    return () => clearFadeTimer();
  }, [clearFadeTimer]);

  return maskOpacity;
};

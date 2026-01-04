import React, { useEffect } from 'react';
import { TouchableOpacity, StyleSheet, View, Platform } from 'react-native';
import { Image } from 'expo-image';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  withSpring,
  withSequence,
  withDelay,
  Easing,
  interpolate,
  Extrapolate,
  useDerivedValue,
  cancelAnimation,
} from 'react-native-reanimated';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from 'expo-haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useSPAStore } from '../../store/spa-store';
import { useChatStore } from '../../store/chat-store';
import * as LucideIcons from 'lucide-react-native';
import { preventDoubleTap } from '../../lib/navigation-utils';
import { ParticleEnergyGlow } from '../ui/ParticleEnergyGlow';

interface SuperAssistantFABProps {
  onPress: () => void;
}

// =============================================================================
// VFX Components
// =============================================================================

const QuantumRings = ({ isGenerating, color }: { isGenerating: boolean; color: string }) => {
  const rotateX = useSharedValue(0);
  const rotateY = useSharedValue(0);
  const scale = useSharedValue(1);

  useEffect(() => {
    const duration = isGenerating ? 1500 : 8000;

    rotateX.value = withRepeat(withTiming(360, { duration, easing: Easing.linear }), -1);
    rotateY.value = withRepeat(
      withTiming(360, { duration: duration * 1.5, easing: Easing.linear }),
      -1,
    );

    scale.value = withTiming(isGenerating ? 1.3 : 1, { duration: 500 });
  }, [isGenerating]);

  const styleX = useAnimatedStyle(() => ({
    transform: [{ rotateZ: `${rotateX.value}deg` }, { scaleX: 1 }, { scaleY: 0.3 }],
    borderColor: color,
    opacity: isGenerating ? 1 : 0.6,
    borderWidth: isGenerating ? 3 : 1.5,
  }));

  const styleY = useAnimatedStyle(() => ({
    transform: [{ rotateZ: `${-rotateY.value}deg` }, { scaleX: 0.3 }, { scaleY: 1 }],
    borderColor: color,
    opacity: isGenerating ? 1 : 0.6,
    borderWidth: isGenerating ? 3 : 1.5,
  }));

  return (
    <View
      style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]}
      pointerEvents="none"
    >
      <Animated.View style={[styles.quantumRing, styleX]} />
      <Animated.View style={[styles.quantumRing, styleY]} />
    </View>
  );
};

const GlitchEffect = ({ isGenerating, color }: { isGenerating: boolean; color: string }) => {
  const shiftX = useSharedValue(0);
  const shiftY = useSharedValue(0);
  const opacity = useSharedValue(0);

  useEffect(() => {
    if (!isGenerating) {
      opacity.value = 0;
      return;
    }

    const glitch = () => {
      shiftX.value = withSequence(
        withTiming(-5, { duration: 50 }),
        withTiming(5, { duration: 50 }),
        withTiming(0, { duration: 50 }),
      );
      shiftY.value = withSequence(
        withTiming(5, { duration: 50 }),
        withTiming(-5, { duration: 50 }),
        withTiming(0, { duration: 50 }),
      );
      opacity.value = withSequence(
        withTiming(0.8, { duration: 50 }),
        withTiming(0.4, { duration: 50 }),
        withTiming(0, { duration: 100 }),
      );
    };

    const interval = setInterval(glitch, 2000);
    glitch();

    return () => clearInterval(interval);
  }, [isGenerating]);

  const style = useAnimatedStyle(() => ({
    transform: [{ translateX: shiftX.value }, { translateY: shiftY.value }],
    backgroundColor: color,
    opacity: opacity.value,
  }));

  return <Animated.View style={[StyleSheet.absoluteFill, { borderRadius: 32 }, style]} />;
};

// =============================================================================
// Main Component
// =============================================================================

export const SuperAssistantFAB: React.FC<SuperAssistantFABProps> = ({ onPress }) => {
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { preferences } = useSPAStore();
  const isGenerating = useChatStore((state) => !!state.activeRequests['super_assistant']);
  const { animationMode = 'pulse' } = preferences.fab;

  const mode = animationMode || 'pulse';

  // Shared Values
  const pulse = useSharedValue(1);
  const rotation = useSharedValue(0);

  useEffect(() => {
    // --- Pulse Animation ---
    if (mode === 'pulse' || mode === 'liquid') {
      const baseScale =
        mode === 'liquid' ? (isGenerating ? 1.25 : 1.05) : isGenerating ? 1.15 : 1.05;
      const duration = isGenerating ? (mode === 'liquid' ? 600 : 800) : 1500;

      pulse.value = withRepeat(
        withSequence(
          withTiming(baseScale, { duration, easing: Easing.inOut(Easing.ease) }),
          withTiming(1, { duration, easing: Easing.inOut(Easing.ease) }),
        ),
        -1,
        true,
      );
    } else {
      pulse.value = withTiming(1);
    }

    // --- Rotation ---
    if (preferences.fab.enableRotation || mode === 'nebula') {
      const duration = isGenerating ? 2000 : 15000;
      rotation.value = withRepeat(withTiming(360, { duration, easing: Easing.linear }), -1);
    } else {
      rotation.value = 0;
    }
  }, [mode, isGenerating, preferences.fab.enableRotation]);

  const containerStyle = useAnimatedStyle(() => ({
    transform: [
      { scale: pulse.value },
      {
        scaleX:
          mode === 'liquid' && isGenerating ? interpolate(pulse.value, [1, 1.25], [1, 0.9]) : 1,
      },
    ],
  }));

  const iconStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
  }));

  const handlePress = () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    preventDoubleTap(() => {
      setTimeout(() => {
        onPress();
      }, 50);
    });
  };

  const renderIcon = () => {
    const { iconType, customIconUri, iconColor } = preferences.fab;
    if (iconType === 'custom' && customIconUri) return null;
    const IconComponent = (LucideIcons as any)[iconType] || LucideIcons.Sparkles;
    return <IconComponent size={28} color={iconColor} strokeWidth={2.5} />; // White icon for better contrast on glow? No, user prefers custom color.
  };

  const isCustomIcon = preferences.fab.iconType === 'custom' && preferences.fab.customIconUri;
  const backgroundColor = preferences.fab.backgroundColor;
  const iconColor = preferences.fab.iconColor;
  const glowColor = preferences.fab.enableGlow
    ? preferences.fab.glowColor || iconColor
    : 'transparent'; // Fallback to icon color if glow color not set

  return (
    // Bottom Offset Adjustment:
    // Old Bottom: 85. Center from Bottom: 85 + 32 = 117.
    // New Size: 160. New Bottom: 117 - 80 = 37.
    <View pointerEvents="box-none" style={[styles.wrapper, { bottom: 37 + insets.bottom }]}>
      {/* 🌟 Silky Glow Background (Windows 7 Fusion Style) */}
      {/* Wrapper is now large (160x160) to avoid Android clipping. */}
      {/* Glow fills the wrapper. */}
      {preferences.fab.enableGlow && (
        <ParticleEnergyGlow
          color={glowColor}
          size={160}
          style={StyleSheet.absoluteFillObject} // Fill the 160x160 wrapper
          isDark={isDark} // Pass theme status for adaptive rendering
        />
      )}

      {/* Special Effects Layers */}
      {mode === 'quantum' && <QuantumRings isGenerating={isGenerating} color={iconColor} />}
      {mode === 'glitch' && <GlitchEffect isGenerating={isGenerating} color={iconColor} />}

      {/* Main Button Container (Centered 64x64) */}
      <Animated.View
        style={[
          styles.container,
          containerStyle,
          {
            // No more shadow props here to avoid conflict with HyperGlow
            backgroundColor: isCustomIcon ? 'transparent' : backgroundColor + '1A',
          },
        ]}
      >
        <TouchableOpacity activeOpacity={0.8} onPress={handlePress} style={styles.touchable}>
          <BlurView
            intensity={isDark ? 30 : 50}
            tint={isDark ? 'dark' : 'light'}
            style={styles.blur}
          >
            <View
              style={[
                styles.inner,
                {
                  backgroundColor: isCustomIcon ? 'transparent' : backgroundColor + '4D',
                  borderColor: backgroundColor + '80',
                },
              ]}
            >
              <Animated.View style={[styles.rotatingContainer, iconStyle]}>
                {isCustomIcon && (
                  <Image
                    source={{ uri: preferences.fab.customIconUri }}
                    style={StyleSheet.absoluteFillObject}
                    contentFit="cover"
                    transition={200}
                  />
                )}
                {renderIcon()}
              </Animated.View>
            </View>
          </BlurView>
        </TouchableOpacity>
      </Animated.View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    position: 'absolute',
    // Center alignment adjustment:
    // Old Size: 64, Old Right: 24. Center from Right: 24 + 32 = 56.
    // New Size: 160. New Right: 56 - 80 = -24.
    right: -24,
    width: 160, // Expanded to contain glow
    height: 160,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 9999,
    // pointerEvents handled by View prop "box-none" to let touches pass through glow area if needed,
    // but React Native View doesn't support pointerEvents in styles, it's a prop.
    // The top-level View has pointerEvents="box-none".
  },
  container: {
    width: 64,
    height: 64,
    borderRadius: 32,
    // Ensure button stays on top of glow in stacking order (DOM order handles this naturally)
  },
  glowLayer: {
    position: 'absolute',
    width: 64,
    height: 64,
    borderRadius: 32,
  },
  quantumRing: {
    position: 'absolute',
    width: 90,
    height: 90,
    borderRadius: 45,
  },
  touchable: {
    width: '100%',
    height: '100%',
    borderRadius: 32,
    overflow: 'hidden',
  },
  blur: {
    width: '100%',
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  inner: {
    width: 56,
    height: 56,
    borderRadius: 28,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  rotatingContainer: {
    width: '100%',
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
});

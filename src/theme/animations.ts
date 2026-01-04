import {
  Easing,
  WithTimingConfig,
  WithSpringConfig,
  FadeIn,
  FadeOut,
  SlideInUp,
  SlideInDown,
  ZoomIn,
  ZoomOut,
  LayoutAnimation,
} from 'react-native-reanimated';

/**
 * Global Animation Constants
 * Standardizes animation durations and curves across the app.
 */
export const ANIMATION_DURATION = {
  FAST: 150, // Micro-interactions, exits
  NORMAL: 250, // Standard transitions, modals, fades
  SLOW: 350, // Complex layout changes, heavy elements
  EXTRA_SLOW: 500, // Loading states, emphasis
  ROTATION_SLOW: 1500, // For loading spinners
};

export const ANIMATION_CONFIG = {
  // Timing configs
  DEFAULT: {
    duration: ANIMATION_DURATION.NORMAL,
    easing: Easing.bezier(0.25, 0.1, 0.25, 1),
  } as WithTimingConfig,
  FAST: {
    duration: ANIMATION_DURATION.FAST,
    easing: Easing.bezier(0.25, 0.1, 0.25, 1),
  } as WithTimingConfig,

  // Spring configs (iOS-like feel, tuned for Release smoothness)
  SPRING_DEFAULT: { damping: 26, stiffness: 120, mass: 1 } as WithSpringConfig,
  SPRING_BOUNCY: { damping: 18, stiffness: 120, mass: 1 } as WithSpringConfig,
  SPRING_SNAPPY: { damping: 30, stiffness: 200, mass: 0.8 } as WithSpringConfig, // Very snappy, zero bounce

  // Silk config (Fluid, non-spring timing for heavy UI changes)
  SILK: {
    duration: ANIMATION_DURATION.NORMAL,
    easing: Easing.bezier(0.4, 0, 0.2, 1),
  } as WithTimingConfig,
};

// Reanimated Layout Animation Presets
export const LayoutAnimations = {
  FadeIn: FadeIn.duration(ANIMATION_DURATION.NORMAL),
  FadeOut: FadeOut.duration(ANIMATION_DURATION.FAST),

  // Modals & Dialogs
  ModalEnter: FadeIn.duration(ANIMATION_DURATION.NORMAL)
    .springify()
    .damping(20)
    .stiffness(150)
    .mass(0.9),
  ModalExit: FadeOut.duration(ANIMATION_DURATION.FAST),

  SlideUpEnter: SlideInUp.duration(ANIMATION_DURATION.NORMAL)
    .springify()
    .damping(20)
    .stiffness(150),
  SlideDownExit: SlideInDown.duration(ANIMATION_DURATION.FAST),
};

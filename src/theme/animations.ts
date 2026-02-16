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

export const ANIMATION_DURATION = {
  FAST: 120,
  NORMAL: 200,
  SLOW: 300,
  EXTRA_SLOW: 450,
  ROTATION_SLOW: 1500,
};

export const ANIMATION_CONFIG = {
  DEFAULT: {
    duration: ANIMATION_DURATION.NORMAL,
    easing: Easing.bezier(0.25, 0.1, 0.25, 1),
  } as WithTimingConfig,
  FAST: {
    duration: ANIMATION_DURATION.FAST,
    easing: Easing.bezier(0.25, 0.1, 0.25, 1),
  } as WithTimingConfig,

  SPRING_DEFAULT: { damping: 26, stiffness: 120, mass: 1 } as WithSpringConfig,
  SPRING_BOUNCY: { damping: 18, stiffness: 120, mass: 1 } as WithSpringConfig,
  SPRING_SNAPPY: { damping: 30, stiffness: 200, mass: 0.8 } as WithSpringConfig,

  SPRING_BUTTON: { damping: 20, stiffness: 400, mass: 0.5 } as WithSpringConfig,
  SPRING_CARD: { damping: 20, stiffness: 400, mass: 0.5 } as WithSpringConfig,
  SPRING_TOAST: { damping: 20, stiffness: 180, mass: 0.8 } as WithSpringConfig,

  SILK: {
    duration: 200,
    easing: Easing.bezier(0.4, 0, 0.2, 1),
  } as WithTimingConfig,
};

export const LayoutAnimations = {
  FadeIn: FadeIn.duration(ANIMATION_DURATION.NORMAL),
  FadeOut: FadeOut.duration(ANIMATION_DURATION.FAST),

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

  ScaleIn: ZoomIn.duration(ANIMATION_DURATION.NORMAL)
    .springify()
    .damping(20)
    .stiffness(200),
  ScaleOut: ZoomOut.duration(ANIMATION_DURATION.FAST),

  ToastEnter: FadeIn.duration(ANIMATION_DURATION.NORMAL)
    .springify()
    .damping(20)
    .stiffness(180),
  ToastExit: FadeOut.duration(ANIMATION_DURATION.FAST),

  ListItemEnter: FadeIn.duration(ANIMATION_DURATION.NORMAL),
  ListItemExit: FadeOut.duration(ANIMATION_DURATION.FAST),
};

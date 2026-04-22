// react-native-reanimated mock

function useSharedValue(initialValue) {
  return { value: initialValue };
}

function useAnimatedStyle(updater) {
  return updater();
}

function withTiming(value, _config) {
  return value;
}

function withSpring(value, _config) {
  return value;
}

function withDelay(_delay, animation) {
  return animation;
}

function withSequence(...animations) {
  return animations[0];
}

function withRepeat(animation, _count, _reverse) {
  return animation;
}

function runOnJS(fn) {
  return fn;
}

function runOnUI(fn) {
  return fn;
}

function useDerivedValue(fn) {
  return { value: fn() };
}

function useAnimatedGestureHandler(_handlers) {
  return {};
}

function useAnimatedScrollHandler(_handlers) {
  return {};
}

function useAnimatedRef() {
  return { current: null };
}

function scrollTo(_ref, _x, _y, _animated) {
  // no-op
}

function withDecay(_config) {
  return 0;
}

function withBounce(_config) {
  return 0;
}

function Easing(_easing) {
  return _easing;
}

const default = {
  addWhitelistedNativeProps: () => {},
  addWhitelistedUIProps: () => {},
  addWhitelistedBooleanProps: () => {},
  createAnimatedComponent: (Component) => Component,
  View: 'View',
  Text: 'Text',
  Image: 'Image',
  TextInput: 'TextInput',
};

const LayoutAnimation = {
  configureNext: () => {},
  create: () => {},
  Types: {},
  Properties: {},
};

const Transition = {
  create: () => {},
  Types: {},
};

module.exports = {
  default,
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withSpring,
  withDelay,
  withSequence,
  withRepeat,
  runOnJS,
  runOnUI,
  useDerivedValue,
  useAnimatedGestureHandler,
  useAnimatedScrollHandler,
  useAnimatedRef,
  scrollTo,
  withDecay,
  withBounce,
  Easing,
  LayoutAnimation,
  Transition,
};

// react-native-gesture-handler mock
const GestureHandlerRootView = 'View';
const Swipeable = 'View';
const DrawerLayout = 'View';
const State = {
  UNDETERMINED: 0,
  FAILED: 1,
  BEGAN: 2,
  CANCELLED: 3,
  ACTIVE: 4,
  END: 5,
};
const PanGestureHandler = 'View';
const TapGestureHandler = 'View';
const LongPressGestureHandler = 'View';
const PinchGestureHandler = 'View';
const RotationGestureHandler = 'View';
const FlingGestureHandler = 'View';
const NativeViewGestureHandler = 'View';
const RawButton = 'View';
const BaseButton = 'View';
const RectButton = 'View';
const BorderlessButton = 'View';
const TouchableHighlight = 'View';
const TouchableNativeFeedback = 'View';
const TouchableOpacity = 'View';
const TouchableWithoutFeedback = 'View';

const Directions = {
  RIGHT: 1,
  LEFT: 2,
  UP: 4,
  DOWN: 8,
};

const gestureHandlerRootHOC = (component) => component;

module.exports = {
  GestureHandlerRootView,
  Swipeable,
  DrawerLayout,
  State,
  PanGestureHandler,
  TapGestureHandler,
  LongPressGestureHandler,
  PinchGestureHandler,
  RotationGestureHandler,
  FlingGestureHandler,
  NativeViewGestureHandler,
  RawButton,
  BaseButton,
  RectButton,
  BorderlessButton,
  TouchableHighlight,
  TouchableNativeFeedback,
  TouchableOpacity,
  TouchableWithoutFeedback,
  Directions,
  gestureHandlerRootHOC,
};

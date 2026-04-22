// react-native-safe-area-context mock
function SafeAreaProvider({ children }) {
  return children;
}

const SafeAreaView = 'View';
const SafeAreaScrollView = 'ScrollView';
const SafeAreaFrameContext = {};
const SafeAreaInsetsContext = {};
const LayoutContext = {};

function useSafeAreaInsets() {
  return { top: 0, bottom: 0, left: 0, right: 0 };
}

function useSafeAreaFrame() {
  return { x: 0, y: 0, width: 375, height: 812 };
}

function useSafeAreaLayoutGuide() {
  return {
    top: { value: 0 },
    left: { value: 0 },
    right: { value: 0 },
    bottom: { value: 0 },
    height: { value: 812 },
    width: { value: 375 },
  };
}

const initialWindowMetrics = {
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
  frame: { x: 0, y: 0, width: 375, height: 812 },
};

module.exports = {
  SafeAreaProvider,
  SafeAreaView,
  SafeAreaScrollView,
  SafeAreaFrameContext,
  SafeAreaInsetsContext,
  LayoutContext,
  useSafeAreaInsets,
  useSafeAreaFrame,
  useSafeAreaLayoutGuide,
  initialWindowMetrics,
};

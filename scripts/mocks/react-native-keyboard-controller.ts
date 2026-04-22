// react-native-keyboard-controller mock
function useKeyboardHandler() {
  return [];
}

function KeyboardProvider({ children }) {
  return children;
}

function useAnimatedKeyboard() {
  return { height: { value: 0 }, progress: { value: 0 } };
}

function useKeyboardHeight() {
  return 0;
}

function useKeyboardStatus() {
  return { isKeyboardShown: false, keyboardHeight: 0 };
}

const KeyboardController = {
  View: 'View',
  TextInput: 'TextInput',
  ScrollView: 'ScrollView',
};

const KeyboardAvoidingView = 'View';

const useDismissKeyboard = () => {};

const useReanimatedKeyboardAnimation = () => ({
  bottom: { value: 0 },
});

module.exports = {
  useKeyboardHandler,
  KeyboardProvider,
  useAnimatedKeyboard,
  useKeyboardHeight,
  useKeyboardStatus,
  KeyboardController,
  KeyboardAvoidingView,
  useDismissKeyboard,
  useReanimatedKeyboardAnimation,
};

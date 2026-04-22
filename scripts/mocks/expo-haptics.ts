// expo-haptics mock
const impactAsync = async () => {};
const notificationAsync = async () => {};
const selectionAsync = async () => {};

const ImpactFeedbackStyle = {
  light: 'light',
  medium: 'medium',
  heavy: 'heavy',
  rigid: 'rigid',
  soft: 'soft',
};

const NotificationFeedbackType = {
  success: 'success',
  warning: 'warning',
  error: 'error',
  info: 'info',
};

const selection = {
  trigger: () => {},
};

const ImpactOptions = {};

module.exports = {
  impactAsync,
  notificationAsync,
  selectionAsync,
  ImpactFeedbackStyle,
  NotificationFeedbackType,
  selection,
  ImpactOptions,
};

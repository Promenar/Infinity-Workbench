// react-native-view-shot mock
async function captureScreen(_options) {
  return '';
}

async function captureRef(_ref, _options) {
  return '';
}

async function captureOnAnimationFrame(_ref, _onImage) {
  // no-op
}

const ViewShot = 'View';
const CaptureMode = {
  continuous: 'continuous',
  static: 'static',
};

const defaultOptions = {
  format: 'png',
  quality: 1,
  result: 'tmpfile',
};

module.exports = {
  captureScreen,
  captureRef,
  captureOnAnimationFrame,
  ViewShot,
  CaptureMode,
  defaultOptions,
};

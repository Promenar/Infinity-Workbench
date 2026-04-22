// expo-image-picker mock
async function launchImageLibraryAsync(_options) {
  return { canceled: true, assets: [] };
}

async function launchCameraAsync(_options) {
  return { canceled: true, assets: [] };
}

async function getMediaLibraryPermissionsAsync(_request) {
  return { status: 'granted' };
}

async function requestMediaLibraryPermissionsAsync(_request) {
  return { status: 'granted' };
}

async function requestCameraPermissionsAsync() {
  return { status: 'granted' };
}

async function getCameraPermissionsAsync() {
  return { status: 'granted' };
}

const MediaTypeOptions = {
  Images: 'Images',
  Videos: 'Videos',
  All: 'All',
};

const defaultOptions = {
  mediaTypes: MediaTypeOptions.Images,
  allowsEditing: false,
  aspect: [1, 1],
  quality: 1,
};

module.exports = {
  launchImageLibraryAsync,
  launchCameraAsync,
  getMediaLibraryPermissionsAsync,
  requestMediaLibraryPermissionsAsync,
  requestCameraPermissionsAsync,
  getCameraPermissionsAsync,
  MediaTypeOptions,
  defaultOptions,
};

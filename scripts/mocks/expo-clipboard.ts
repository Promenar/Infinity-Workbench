// expo-clipboard mock
async function getStringAsync() {
  return '';
}

async function setStringAsync(_s) {
  // no-op
}

async function hasStringAsync() {
  return false;
}

async function getImageAsync() {
  return null;
}

const Clipboard = {
  getString: getStringAsync,
  setString: setStringAsync,
};

module.exports = {
  getStringAsync,
  setStringAsync,
  hasStringAsync,
  getImageAsync,
  Clipboard,
};

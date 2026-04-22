// expo-keep-awake mock
function useKeepAwake(_tag) {
  // no-op
}

function activate() {
  // no-op
}

function deactivate() {
  // no-op
}

const activateKeepAwake = activate;
const deactivateKeepAwake = deactivate;

module.exports = {
  useKeepAwake,
  activate,
  deactivate,
  activateKeepAwake,
  deactivateKeepAwake,
};

// llama.rn mock
async function loadModel(_options) {
  return {};
}

async function unloadModel() {
  // no-op
}

async function generate(_prompt, _options) {
  return '';
}

async function* generateStream(_prompt, _options) {
  yield { chunk: '' };
}

function isModelLoaded() {
  return false;
}

function isLoaded() {
  return false;
}

const defaultOptions = {
  temperature: 0.7,
  maxTokens: 512,
  topP: 0.9,
};

module.exports = {
  loadModel,
  unloadModel,
  generate,
  generateStream,
  isModelLoaded,
  isLoaded,
  defaultOptions,
};

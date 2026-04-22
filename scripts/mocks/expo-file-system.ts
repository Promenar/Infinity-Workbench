/**
 * expo-file-system 内存模拟
 */

const filesystem = new Map<string, string>();

const documentDirectory = '/mock-document-dir/';
const cacheDirectory = '/mock-cache-dir/';

function ensureDir(path: string): void {
  const dir = path.substring(0, path.lastIndexOf('/'));
  if (dir && !filesystem.has(dir)) {
    filesystem.set(dir, '');
  }
}

async function readAsStringAsync(
  fileUri: string,
  _options?: any
): Promise<string> {
  const content = filesystem.get(fileUri);
  if (content === undefined) {
    throw new Error(`File not found: ${fileUri}`);
  }
  return content;
}

async function writeAsStringAsync(
  fileUri: string,
  contents: string,
  _options?: any
): Promise<void> {
  ensureDir(fileUri);
  filesystem.set(fileUri, contents);
}

async function deleteAsync(fileUri: string, _options?: any): Promise<void> {
  filesystem.delete(fileUri);
}

async function makeDirectoryAsync(
  dirUri: string,
  _options?: any
): Promise<void> {
  filesystem.set(dirUri, '');
}

async function getInfoAsync(fileUri: string): Promise<any> {
  const exists = filesystem.has(fileUri);
  const isDirectory = filesystem.get(fileUri) === '';
  return {
    exists,
    isDirectory,
    uri: fileUri,
    size: exists && !isDirectory ? filesystem.get(fileUri)!.length : 0,
    mtime: new Date(),
  };
}

async function copyAsync(
  _from: string,
  _to: string,
  _options?: any
): Promise<void> {
  // no-op
}

async function moveAsync(
  _from: string,
  _to: string,
  _options?: any
): Promise<void> {
  // no-op
}

async function downloadAsync(
  _uri: string,
  _fileUri: string,
  _options?: any
): Promise<any> {
  return { uri: _fileUri, status: 200 };
}

async function createDownloadResumable(
  url: string,
  fileUri: string,
  options?: any,
  _callback?: any
): Promise<any> {
  return {
    url,
    fileUri,
    options,
    resume: () => downloadAsync(url, fileUri, options),
  };
}

function __getFilesystem(): Map<string, string> {
  return filesystem;
}

function __clear(): void {
  filesystem.clear();
}

function __setFile(path: string, content: string): void {
  filesystem.set(path, content);
}

function __hasFile(path: string): boolean {
  return filesystem.has(path);
}

module.exports = {
  documentDirectory,
  cacheDirectory,
  readAsStringAsync,
  writeAsStringAsync,
  deleteAsync,
  makeDirectoryAsync,
  getInfoAsync,
  copyAsync,
  moveAsync,
  downloadAsync,
  createDownloadResumable,
  __getFilesystem,
  __clear,
  __setFile,
  __hasFile,
};

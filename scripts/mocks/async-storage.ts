/**
 * AsyncStorage 内存模拟
 */

const storage = new Map<string, string>();

async function getItem(key: string): Promise<string | null> {
  return storage.get(key) ?? null;
}

async function setItem(key: string, value: string): Promise<void> {
  storage.set(key, value);
}

async function removeItem(key: string): Promise<void> {
  storage.delete(key);
}

async function getAllKeys(): Promise<string[]> {
  return [...storage.keys()];
}

async function clear(): Promise<void> {
  storage.clear();
}

async function multiGet(
  keys: string[]
): Promise<[string, string | null][]> {
  return keys.map((k) => [k, storage.get(k) ?? null]);
}

async function multiSet(keyValuePairs: [string, string][]): Promise<void> {
  for (const [k, v] of keyValuePairs) {
    storage.set(k, v);
  }
}

async function multiRemove(keys: string[]): Promise<void> {
  for (const k of keys) {
    storage.delete(k);
  }
}

async function length(): Promise<number> {
  return storage.size;
}

async function hasItem(key: string): Promise<boolean> {
  return storage.has(key);
}

// Jest 测试辅助
function __getStorage(): Map<string, string> {
  return storage;
}

function __clear(): void {
  storage.clear();
}

function __setItems(items: Record<string, string>): void {
  Object.entries(items).forEach(([k, v]) => storage.set(k, v));
}

module.exports = {
  getItem,
  setItem,
  removeItem,
  getAllKeys,
  clear,
  multiGet,
  multiSet,
  multiRemove,
  length,
  hasItem,
  // Jest helpers
  __getStorage,
  __clear,
  __setItems,
};

import { addAliases } from 'module-alias';
import path from 'path';
import fs from 'fs';

// Mock Globals
(global as any).__DEV__ = true;

// Resolve mocks directory
const mocksDir = path.resolve(__dirname, 'mocks');

// Ensure mocks directory exists
if (!fs.existsSync(mocksDir)) {
  console.warn(`[test-setup] Warning: mocks directory not found at ${mocksDir}`);
}

// Register aliases - 映射路径到 Mock 文件
addAliases({
  // 核心第三方库 Mock
  'expo-file-system/legacy': path.resolve(mocksDir, 'expo-file-system.ts'),
  '@react-native-async-storage/async-storage': path.resolve(
    mocksDir,
    'async-storage.ts'
  ),
  '@op-engineering/op-sqlite': path.resolve(mocksDir, 'op-sqlite.ts'),

  // Expo 模块 Mock
  'expo-haptics': path.resolve(mocksDir, 'expo-haptics.ts'),
  'expo-clipboard': path.resolve(mocksDir, 'expo-clipboard.ts'),
  'expo-image': path.resolve(mocksDir, 'expo-image.ts'),
  'expo-router': path.resolve(mocksDir, 'expo-router.ts'),
  'expo-keep-awake': path.resolve(mocksDir, 'expo-keep-awake.ts'),
  'expo-sharing': path.resolve(mocksDir, 'expo-sharing.ts'),
  'expo-image-picker': path.resolve(mocksDir, 'expo-image-picker.ts'),

  // React Native 模块 Mock
  'react-native-reanimated': path.resolve(
    mocksDir,
    'react-native-reanimated.ts'
  ),
  'react-native-gesture-handler': path.resolve(
    mocksDir,
    'react-native-gesture-handler.ts'
  ),
  'react-native-keyboard-controller': path.resolve(
    mocksDir,
    'react-native-keyboard-controller.ts'
  ),
  'react-native-view-shot': path.resolve(mocksDir, 'react-native-view-shot.ts'),
  'react-native-svg': path.resolve(mocksDir, 'react-native-svg.ts'),
  'react-native-screens': path.resolve(mocksDir, 'react-native-screens.ts'),
  'react-native-safe-area-context': path.resolve(
    mocksDir,
    'react-native-safe-area-context.ts'
  ),
  'react-native-webview': path.resolve(mocksDir, 'react-native-webview.ts'),
  'react-native-sse': path.resolve(mocksDir, 'react-native-sse.ts'),

  // Shopify 模块 Mock
  '@shopify/flash-list': path.resolve(mocksDir, '@shopify-flash-list.ts'),

  // 本地 LLM Mock
  'llama.rn': path.resolve(mocksDir, 'llama-rn.ts'),

  // 已有的 Mock
  '@probelabs/maid': path.resolve(__dirname, 'mocks/probelabs-maid.ts'),
  'ai-text-sanitizer': path.resolve(
    __dirname,
    'mocks/ai-text-sanitizer.ts'
  ),
});

import { addAliases } from 'module-alias';
import path from 'path';

// Mock Globals
(global as any).__DEV__ = true;

// Register aliases
addAliases({
    'expo-file-system/legacy': path.resolve(__dirname, 'mocks/expo-file-system.ts'),
    '@react-native-async-storage/async-storage': path.resolve(__dirname, 'mocks/async-storage.ts'),
    '@op-engineering/op-sqlite': path.resolve(__dirname, 'mocks/op-sqlite.ts'),
});

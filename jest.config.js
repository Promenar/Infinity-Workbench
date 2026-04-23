module.exports = {
  preset: 'react-native',
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],

  // 测试环境配置
  testEnvironment: 'node',

  // 匹配 .test.ts, .spec.ts 和 .bench.ts 文件
  testMatch: [
    '**/__tests__/**/*.?([mc])[jt]s?(x)',
    '**/?(*.)+(spec|test|bench).?([mc])[jt]s?(x)',
  ],

  // Setup 文件 - 注册 Mock 别名
  setupFiles: ['<rootDir>/scripts/test-setup.ts'],

  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?)|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|react-navigation|@react-navigation/.*|@unimodules/.*|unimodules|sentry-expo|native-base|react-native-svg|react-native-sse)',
  ],

  testPathIgnorePatterns: [
    '/node_modules/',
    '/android/',
    '/ios/',
    '/web-client/',
    '/worktree/',
    '\\.benchmark\\.ts$',
  ],

  // 覆盖测试超时
  testTimeout: 10000,

  // 全局 teardown
  globalTeardown: '<rootDir>/scripts/jest-teardown.js',

  // 模块名称映射
  moduleNameMapper: {
    // Pure ESM packages that cannot be CJS-required by Jest
    '@probelabs/maid': '<rootDir>/scripts/mocks/probelabs-maid.ts',
    'ai-text-sanitizer': '<rootDir>/scripts/mocks/ai-text-sanitizer.ts',
  },
};

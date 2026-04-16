module.exports = {
    preset: 'react-native',
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
    transformIgnorePatterns: [
        'node_modules/(?!((jest-)?react-native|@react-native(-community)?)|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|react-navigation|@react-navigation/.*|@unimodules/.*|unimodules|sentry-expo|native-base|react-native-svg|react-native-sse)',
    ],
    testPathIgnorePatterns: ['/node_modules/', '/android/', '/ios/'],
    moduleNameMapper: {
        // Pure ESM packages that cannot be CJS-required by Jest
        '@probelabs/maid': '<rootDir>/scripts/mocks/probelabs-maid.ts',
        'ai-text-sanitizer': '<rootDir>/scripts/mocks/ai-text-sanitizer.ts',
    },
};

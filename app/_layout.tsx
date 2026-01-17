import '../global.css';
import {
  configureReanimatedLogger,
  ReanimatedLogLevel,
} from 'react-native-reanimated';

// Disable strict mode for Reanimated 3 to prevent "Reading from value during component render" warnings
configureReanimatedLogger({
  level: ReanimatedLogLevel.warn,
  strict: false,
});

import '../src/services/NotifeeBackgroundRunner'; // Register background task
import React, { useEffect, useState } from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { View, ActivityIndicator, LogBox } from 'react-native';

LogBox.ignoreLogs([
  'SafeAreaView has been deprecated',
]);
import { ThemeProvider } from '../src/theme/ThemeProvider';
import { ToastProvider } from '../src/components/ui/Toast';
import { useI18n } from '../src/lib/i18n';
import { initDb } from '../src/lib/db';
import { createTables } from '../src/lib/db/schema';
import { migrateDatabase } from '../src/lib/db/migration';
import { useLocalModelStore } from '../src/lib/local-inference/LocalModelServer'; // Hook auto-load

import { KeyboardProvider } from 'react-native-keyboard-controller';

import { GestureHandlerRootView } from 'react-native-gesture-handler';

import {
  ThemeProvider as NavThemeProvider,
  DarkTheme,
  DefaultTheme,
} from '@react-navigation/native';
import { useColorScheme } from 'nativewind';

import { Colors } from '../src/theme/colors';

// Map our custom token-based colors to React Navigation Theme
const CustomDarkTheme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    primary: Colors.primary,
    background: Colors.dark.background,
    card: Colors.dark.surfaceSecondary,
    text: Colors.dark.textPrimary,
    border: Colors.dark.borderDefault,
    notification: Colors.error,
  },
};

const CustomLightTheme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
    primary: Colors.primary,
    background: Colors.light.background,
    card: Colors.light.surfaceSecondary,
    text: Colors.light.textPrimary,
    border: Colors.light.borderDefault,
    notification: Colors.error,
  },
};

import { SafeAreaProvider } from 'react-native-safe-area-context';

export default function RootLayout() {
  const [dbReady, setDbReady] = useState(false);
  const { colorScheme } = useColorScheme();
  const currentTheme = colorScheme === 'dark' ? CustomDarkTheme : CustomLightTheme;

  useEffect(() => {
    const setup = async () => {
      try {
        await initDb();
        await createTables();
        await migrateDatabase(); // Run migrations
        console.log('[App] DB Initialized');

        // Fire and forget auto backup in next tick
        setTimeout(() => {
          const { BackupManager } = require('../src/lib/backup/BackupManager');
          BackupManager.checkAndTriggerAutoBackup().catch((err: any) => {
            console.warn('[App] Auto backup initial trigger failed:', err.message);
          });
        }, 1000);

      } catch (e) {
        console.error('[App] DB Init Failed', e);
      } finally {
        setDbReady(true);
      }
    };
    setup();
  }, []);

  if (!dbReady) {
    return (
      <View
        style={{
          flex: 1,
          justifyContent: 'center',
          alignItems: 'center',
          backgroundColor: Colors.dark.background,
        }}
      >
        <ActivityIndicator size="large" color={Colors.primary} />
      </View>
    );
  }

  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: currentTheme.colors.background }}>
      <SafeAreaProvider>
        <ThemeProvider>
          <KeyboardProvider>
            <ToastProvider>
              <NavThemeProvider value={currentTheme}>
                <StatusBar style="auto" />
                <Stack
                  screenOptions={{
                    headerShown: false,
                    animation: 'slide_from_right',
                    animationDuration: 200, // 提速至 200ms，增强跟手感
                    contentStyle: { backgroundColor: 'transparent' },
                  }}
                >
                  <Stack.Screen name="(tabs)" />
                  <Stack.Screen name="index" />
                  <Stack.Screen name="settings/skills" />
                  <Stack.Screen name="demo/skills" />
                </Stack>
              </NavThemeProvider>
            </ToastProvider>
          </KeyboardProvider>
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

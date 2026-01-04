import 'react-native-get-random-values';
import '../global.css';
import React, { useEffect, useState } from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { View, ActivityIndicator } from 'react-native';
import { ThemeProvider } from '../src/theme/ThemeProvider';
import { ToastProvider } from '../src/components/ui/Toast';
import { useI18n } from '../src/lib/i18n';
import { initDb } from '../src/lib/db';
import { createTables } from '../src/lib/db/schema';
import { migrateDatabase } from '../src/lib/db/migration';

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
        const { BackupManager } = require('../src/lib/backup/BackupManager');
        BackupManager.checkAndTriggerAutoBackup(); // Fire and forget
        console.log('[App] DB Initialized');
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
      <ThemeProvider>
        <KeyboardProvider>
          <ToastProvider>
            <NavThemeProvider value={currentTheme}>
              <StatusBar style="auto" />
              <Stack
                screenOptions={{
                  headerShown: false,
                  animation: 'slide_from_right',
                  gestureEnabled: true,
                  animationDuration: 300,
                  contentStyle: { backgroundColor: currentTheme.colors.background },
                }}
              >
                <Stack.Screen name="(tabs)" />
                <Stack.Screen name="index" />
              </Stack>
            </NavThemeProvider>
          </ToastProvider>
        </KeyboardProvider>
      </ThemeProvider>
    </GestureHandlerRootView>
  );
}

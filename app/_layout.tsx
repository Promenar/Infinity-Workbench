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
import { initCrashHandler } from '../src/lib/logging/CrashHandler';
import { Logger } from '../src/lib/logging/Logger';

// Initialize global crash handler
initCrashHandler();

const logger = Logger.getInstance();

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
        logger.info('App', 'DB Initialized');

        // 🔑 Phase 4b: 从 SQLite 加载会话
        try {
          const { useChatStore } = require('../src/store/chat-store');
          await useChatStore.getState().loadSessions();
        } catch (err: any) {
          logger.warn('App', 'Session loading failed', { error: err.message });
        }

        // Fire and forget auto backup in next tick
        setTimeout(() => {
          const { BackupManager } = require('../src/lib/backup/BackupManager');
          BackupManager.checkAndTriggerAutoBackup().catch((err: any) => {
            logger.warn('App', 'Auto backup initial trigger failed', { error: err.message });
          });
        }, 1000);

        // 🔑 恢复中断的向量化任务 (Checkpoint Recovery)
        setTimeout(async () => {
          try {
            const { useRagStore } = require('../src/store/rag-store');
            const queue = useRagStore.getState()._getQueue?.();
            if (queue) {
              await queue.resumeInterruptedTasks();
            }
          } catch (err: any) {
            logger.warn('App', 'Queue recovery failed', { error: err.message });
          }
        }, 2000);

      } catch (e) {
        logger.error('App', 'DB Init Failed', { error: e });
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
                    animation: 'default', // ✅ 回归原生默认：iOS 为覆盖式(Cover)，Android 为系统默认(往往是 Zoom 或 Cover)
                    // animationDuration: 250, // 移除手动时长，交由系统物理引擎接管，实现最自然的“跟手”
                    gestureEnabled: true,
                    gestureDirection: 'horizontal',
                    fullScreenGestureEnabled: true,
                    contentStyle: { backgroundColor: 'transparent' },
                  }}
                >
                  <Stack.Screen name="(tabs)" />
                  {/* 针对部分页面强制使用 Slide (如设置页)，如果系统默认不是由右向左，可以在此细化 */}
                  {/* 但通常 Default 在 iOS 上就是 Slide from right (Cover) */}
                  <Stack.Screen name="index" />
                  <Stack.Screen name="settings/skills" />
                  <Stack.Screen name="demo/skills" />
                  <Stack.Screen name="welcome" options={{ headerShown: false, gestureEnabled: false }} />
                </Stack>
              </NavThemeProvider>
            </ToastProvider>
          </KeyboardProvider>
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

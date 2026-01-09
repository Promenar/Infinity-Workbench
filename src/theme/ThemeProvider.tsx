import React, { createContext, useContext, useState, useEffect, useMemo } from 'react';
import { useColorScheme } from 'nativewind';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useSettingsStore } from '../store/settings-store';
import { ColorPalette, generatePalette } from '../lib/color-utils';

type ThemeMode = 'light' | 'dark' | 'system';

interface ThemeContextType {
  theme: ThemeMode;
  setTheme: (mode: ThemeMode) => void;
  isDark: boolean;
  colors: ColorPalette;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { colorScheme, setColorScheme } = useColorScheme();
  const [mode, setModeState] = useState<ThemeMode>('system');
  const { accentColor } = useSettingsStore();

  const dynamicColors = useMemo(() => generatePalette(accentColor), [accentColor]);

  useEffect(() => {
    AsyncStorage.getItem('theme_mode').then((stored) => {
      if (stored) {
        const savedMode = stored as ThemeMode;
        console.log('[ThemeProvider] Loaded saved mode:', savedMode);
        setModeState(savedMode);
        setColorScheme(savedMode);
      } else {
        // Default to system
        console.log('[ThemeProvider] No saved theme, defaulting to system');
        setColorScheme('system');
      }
    });
  }, []);

  const setTheme = async (newMode: ThemeMode) => {
    console.log('[ThemeProvider] Setting theme to:', newMode);
    setModeState(newMode);
    setColorScheme(newMode);
    await AsyncStorage.setItem('theme_mode', newMode);
  };

  const isDark = colorScheme === 'dark';

  return (
    <ThemeContext.Provider value={{ theme: mode, setTheme, isDark, colors: dynamicColors }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
}

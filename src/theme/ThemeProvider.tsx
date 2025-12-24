import React, { createContext, useContext, useState, useEffect } from 'react';
import { useColorScheme } from 'nativewind';
import { Appearance } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

type ThemeMode = 'light' | 'dark' | 'system';

interface ThemeContextType {
    theme: ThemeMode;
    setTheme: (mode: ThemeMode) => void;
    isDark: boolean;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
    const { colorScheme, setColorScheme } = useColorScheme();
    const [mode, setModeState] = useState<ThemeMode>('system');

    useEffect(() => {
        // Load persisted theme
        AsyncStorage.getItem('theme_mode').then((saved: string | null) => {
            if (saved && (saved === 'light' || saved === 'dark' || saved === 'system')) {
                setMode(saved as ThemeMode);
            }
        });
    }, []);

    const setMode = (newMode: ThemeMode) => {
        setModeState(newMode);
        AsyncStorage.setItem('theme_mode', newMode);

        if (newMode === 'system') {
            const systemTheme = Appearance.getColorScheme();
            setColorScheme(systemTheme || 'light');
        } else {
            setColorScheme(newMode);
        }
    };

    const isDark = colorScheme === 'dark';

    return (
        <ThemeContext.Provider value={{ theme: mode, setTheme: setMode, isDark }}>
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

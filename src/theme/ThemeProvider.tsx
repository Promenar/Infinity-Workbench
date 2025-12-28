import React, { createContext, useContext, useState, useEffect } from 'react';
import { useColorScheme } from 'nativewind';
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
        AsyncStorage.getItem('theme_mode').then(stored => {
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
    console.log('[ThemeProvider] Current - mode:', mode, 'colorScheme:', colorScheme, 'isDark:', isDark);

    return (
        <ThemeContext.Provider value={{ theme: mode, setTheme, isDark }}>
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

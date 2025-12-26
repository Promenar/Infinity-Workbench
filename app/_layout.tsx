import "../global.css";
import React from 'react';
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { View, ActivityIndicator } from "react-native";
import { ThemeProvider } from "../src/theme/ThemeProvider";
import { ToastProvider } from "../src/components/ui/Toast";
import { useI18n } from "../src/lib/i18n";

import { KeyboardProvider } from "react-native-keyboard-controller";

export default function RootLayout() {
    return (
        <ThemeProvider>
            <KeyboardProvider>
                <ToastProvider>
                    <StatusBar style="auto" />
                    <Stack screenOptions={{ headerShown: false }}>
                        <Stack.Screen name="(tabs)" />
                        <Stack.Screen name="index" />
                    </Stack>
                </ToastProvider>
            </KeyboardProvider>
        </ThemeProvider>
    );
}

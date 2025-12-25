import "../global.css";
import React from 'react';
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { View, ActivityIndicator } from "react-native";
import { ThemeProvider } from "../src/theme/ThemeProvider";
import { ToastProvider } from "../src/components/ui/Toast";
import { useI18n } from "../src/lib/i18n";

export default function RootLayout() {
    return (
        <ThemeProvider>
            <ToastProvider>
                <View className="flex-1 bg-white dark:bg-black">
                    <StatusBar style="auto" />
                    <Stack screenOptions={{ headerShown: false }}>
                        <Stack.Screen name="(tabs)" />
                        <Stack.Screen name="index" />
                    </Stack>
                </View>
            </ToastProvider>
        </ThemeProvider>
    );
}

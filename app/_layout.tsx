import "../global.css";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { View } from "react-native";
import { ThemeProvider } from "../src/theme/ThemeProvider";
import { ToastProvider } from "../src/components/ui/Toast";

export default function RootLayout() {
    return (
        <ThemeProvider>
            <ToastProvider>
                <View className="flex-1 bg-surface-secondary">
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

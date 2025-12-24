import { Tabs } from "expo-router";
import { View, Platform } from "react-native";
import { MessageSquare, Library, Settings } from "lucide-react-native";
import { BlurView } from "expo-blur";
import { useTheme } from "../../src/theme/ThemeProvider";

export default function TabLayout() {
    const { isDark } = useTheme();

    return (
        <Tabs
            screenOptions={{
                headerShown: false,
                animation: 'fade',
                sceneContainerStyle: {
                    backgroundColor: isDark ? '#000000' : '#F9FAFB', // Fixes flicker by matching page bg
                },
                tabBarStyle: {
                    backgroundColor: Platform.OS === 'ios'
                        ? 'transparent'
                        : (isDark ? 'rgba(15, 23, 42, 0.9)' : 'rgba(255, 255, 255, 0.9)'),
                    borderTopColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)',
                    elevation: 0,
                    position: 'absolute',
                    bottom: 0,
                    height: 60,
                    paddingBottom: 8,
                },
                tabBarBackground: () => (
                    Platform.OS === 'ios' ? (
                        <BlurView intensity={80} tint={isDark ? "dark" : "light"} style={{ flex: 1 }} />
                    ) : null
                ),
                tabBarActiveTintColor: '#6366f1', // primary-500
                tabBarInactiveTintColor: isDark ? '#64748b' : '#94a3b8', // text-tertiary
                tabBarHideOnKeyboard: true,
            }}
        >
            <Tabs.Screen
                name="chat"
                options={{
                    title: "Chat",
                    tabBarIcon: ({ color }) => <MessageSquare size={24} color={color} />,
                }}
            />
            <Tabs.Screen
                name="rag"
                options={{
                    title: "Library",
                    tabBarIcon: ({ color }) => <Library size={24} color={color} />,
                }}
            />
            <Tabs.Screen
                name="settings"
                options={{
                    title: "Settings",
                    tabBarIcon: ({ color }) => <Settings size={24} color={color} />,
                }}
            />
        </Tabs>
    );
}

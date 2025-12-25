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
                lazy: false,
                animation: 'none', // Handled locally by PageLayout for Lumina feel
                sceneContainerStyle: {
                    backgroundColor: isDark ? '#000000' : '#FFFFFF',
                },
                tabBarStyle: {
                    backgroundColor: isDark ? '#000000' : '#FFFFFF',
                    borderTopColor: isDark ? '#1e1e1e' : '#f1f1f1',
                    elevation: 8,
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

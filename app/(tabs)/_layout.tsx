import { Tabs } from "expo-router";
import { View, Platform } from "react-native";
import { MessageSquare, Library, Settings } from "lucide-react-native";
import { BlurView } from "expo-blur";
import { useTheme } from "../../src/theme/ThemeProvider";
import { useI18n } from "../../src/lib/i18n";

export default function TabLayout() {
    const { isDark } = useTheme();
    const { t, language } = useI18n();

    return (
        <Tabs
            key={language} // 强制在语言切换时重装导航器，这是最稳健的解决黑屏方案
            screenOptions={{
                headerShown: false,
                lazy: true,
                animation: 'shift',
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
                tabBarActiveTintColor: '#6366f1',
                tabBarInactiveTintColor: isDark ? '#64748b' : '#94a3b8',
                tabBarHideOnKeyboard: true,
            }}
        >
            <Tabs.Screen
                name="chat"
                options={{
                    tabBarLabel: t.tabs.chat,
                    tabBarIcon: ({ color }) => <MessageSquare size={24} color={color} />,
                }}
            />
            <Tabs.Screen
                name="rag"
                options={{
                    tabBarLabel: t.tabs.library,
                    tabBarIcon: ({ color }) => <Library size={24} color={color} />,
                }}
            />
            <Tabs.Screen
                name="settings"
                options={{
                    tabBarLabel: t.tabs.settings,
                    tabBarIcon: ({ color }) => <Settings size={24} color={color} />,
                }}
            />
        </Tabs>
    );
}

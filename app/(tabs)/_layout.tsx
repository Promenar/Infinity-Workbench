import { Tabs } from "expo-router";
import { MessageSquare, Library, Settings } from "lucide-react-native";
import { useTheme } from "../../src/theme/ThemeProvider";
import { useI18n } from "../../src/lib/i18n";

export default function TabLayout() {
    const { isDark } = useTheme();
    const { t, language } = useI18n();

    return (
        <Tabs
            screenOptions={{
                headerShown: false,
                lazy: false, // 预加载所有页面，避免首次切换延迟
                animation: 'shift', // 保留原有shift动画
                tabBarStyle: {
                    backgroundColor: isDark ? '#000000' : '#FFFFFF',
                    borderTopColor: isDark ? '#1e1e1e' : '#f1f1f1',
                    elevation: 0,
                    shadowOpacity: 0,
                    borderTopWidth: 0,
                    position: 'absolute',
                    bottom: 0,
                    height: 60,
                    paddingBottom: 8,
                },
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

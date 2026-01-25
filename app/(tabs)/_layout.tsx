import { Tabs } from 'expo-router';
import { MessageSquare, Library, Settings } from 'lucide-react-native';
import * as LucideIcons from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useI18n } from '../../src/lib/i18n';

export default function TabLayout() {
  const { isDark, colors } = useTheme();
  const { t, language } = useI18n();

  return (
    <Tabs
      key={language}

      screenOptions={{
        headerShown: false,
        lazy: false, // 预加载所有页面，避免首次切换延迟
        animation: 'fade', // ✅ 最终修正：淡入淡出是 Tab 切换的最优解，无位移干扰
        tabBarStyle: {
          backgroundColor: isDark ? '#000000' : '#FFFFFF',
          borderTopColor: isDark ? '#1e1e1e' : '#f1f1f1',
          elevation: 0,
          shadowOpacity: 0,
          borderTopWidth: 0,
          position: 'absolute',
          bottom: 0,
          height: 65,
          paddingBottom: 12,
          paddingTop: 4,
        },
        tabBarActiveTintColor: colors[500],
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

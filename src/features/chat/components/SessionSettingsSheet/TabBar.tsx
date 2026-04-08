import React from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import { Cpu, Brain, BarChart3, Wrench } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';

interface TabBarProps {
  activeTab: string;
  onTabChange: (tab: string) => void;
}

const TABS = [
  { id: 'model', label: '模型', icon: Cpu },
  { id: 'thinking', label: '思考', icon: Brain },
  { id: 'stats', label: '统计', icon: BarChart3 },
  { id: 'tools', label: '工具', icon: Wrench },
];

export const TabBar: React.FC<TabBarProps> = ({ activeTab, onTabChange }) => {
  const { isDark, colors } = useTheme();

  return (
    <View style={[styles.tabBar, { borderBottomColor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.04)' }]}>
      {TABS.map((tab) => {
        const isActive = activeTab === tab.id;
        const Icon = tab.icon;
        const activeColor = colors[500];
        const inactiveColor = isDark ? '#71717a' : '#9ca3af';

        return (
          <TouchableOpacity
            key={tab.id}
            onPress={() => onTabChange(tab.id)}
            activeOpacity={0.7}
            style={styles.tab}
          >
            <Icon size={16} color={isActive ? activeColor : inactiveColor} />
            <Typography
              style={{
                fontSize: 13,
                fontWeight: isActive ? '600' : '400',
                color: isActive ? activeColor : isDark ? '#a1a1aa' : '#6b7280',
                marginLeft: 8,
              }}
            >
              {tab.label}
            </Typography>
            {isActive && (
              <View style={[styles.activeIndicator, { backgroundColor: activeColor }]} />
            )}
          </TouchableOpacity>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  tabBar: {
    flexDirection: 'row',
    borderBottomWidth: 1,
  },
  tab: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: Spacing[3],
    position: 'relative',
  },
  activeIndicator: {
    position: 'absolute',
    bottom: 0,
    left: '25%',
    right: '25%',
    height: 2,
    borderRadius: 1,
  },
});

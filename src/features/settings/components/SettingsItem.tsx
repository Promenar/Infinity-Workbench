import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { ChevronRight } from 'lucide-react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { Colors } from '../../../theme/colors';
import * as Haptics from '../../../lib/haptics';

interface SettingsItemProps {
  icon: React.ElementType;
  title: string;
  subtitle?: string;
  rightElement?: React.ReactNode;
  onPress?: () => void;
  showChevron?: boolean;
  isLast?: boolean;
}

export function SettingsItem({
  icon: Icon,
  title,
  subtitle,
  rightElement,
  onPress,
  showChevron = false,
  isLast = false,
}: SettingsItemProps) {
  const { isDark, colors } = useTheme();
  const themeColors = isDark ? Colors.dark : Colors.light;

  const handlePress = () => {
    if (onPress) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onPress();
      }, 10);
    }
  };

  return (
    <TouchableOpacity
      onPress={onPress ? handlePress : undefined}
      disabled={!onPress}
      activeOpacity={0.7}
      style={[
        styles.container,
        !isLast && {
          borderBottomWidth: 1,
          borderBottomColor: isDark ? 'rgba(99, 102, 241, 0.1)' : 'rgba(0, 0, 0, 0.05)',
        },
      ]}
    >
      <View style={styles.iconContainer}>
        <Icon size={18} color={colors[500]} />
      </View>

      <View style={styles.content}>
        <Text style={[styles.title, { color: themeColors.textPrimary }]}>{title}</Text>
        {subtitle && (
          <Text style={[styles.subtitle, { color: themeColors.textTertiary }]} numberOfLines={1}>
            {subtitle}
          </Text>
        )}
      </View>

      {rightElement}

      {showChevron && (
        <View style={styles.chevron}>
          <ChevronRight size={18} color={themeColors.textTertiary} />
        </View>
      )}
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12, // Reduced -> 12
  },
  iconContainer: {
    width: 22, // Reduced 24 -> 22
    alignItems: 'center',
  },
  content: {
    flex: 1,
    marginLeft: 12,
    marginRight: 8,
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
  },
  subtitle: {
    fontSize: 13,
    marginTop: 4,
  },
  chevron: {
    marginLeft: 4,
  },
});

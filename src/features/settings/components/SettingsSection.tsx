import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { Colors } from '../../../theme/colors';

interface SettingsSectionProps {
  title?: string;
  children: React.ReactNode;
  containerStyle?: any;
}

export function SettingsSection({ title, children, containerStyle }: SettingsSectionProps) {
  const { isDark } = useTheme();
  const themeColors = isDark ? Colors.dark : Colors.light;

  return (
    <View style={[styles.section, containerStyle]}>
      {title && (
        <Text style={[styles.sectionTitle, { color: themeColors.textTertiary }]}>
          {title.toUpperCase()}
        </Text>
      )}
      <View
        style={[
          styles.card,
          {
            backgroundColor: isDark ? 'rgba(24, 24, 27, 0.8)' : themeColors.surfaceSecondary,
            borderColor: isDark ? 'rgba(63, 63, 70, 0.5)' : themeColors.borderDefault,
          },
        ]}
      >
        {children}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    marginBottom: 16, // Reduced 24 -> 16
  },
  sectionTitle: {
    fontSize: 10,
    fontWeight: '700',
    marginBottom: 12,
    paddingHorizontal: 16,
    letterSpacing: 1.5,
  },
  card: {
    borderRadius: 24,
    overflow: 'hidden',
    borderWidth: 1,
  },
});

import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { Colors } from '../../../theme/colors';
import { Card } from '../../../components/ui/Card';

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
      <Card variant="glass">
        {children}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    marginBottom: 20, // Standardize section spacing
  },
  sectionTitle: {
    fontSize: 11, // Standard: 11px
    fontWeight: '700',
    marginBottom: 10, // Reduced from 12 -> 10 for tighter grouping
    paddingHorizontal: 16,
    letterSpacing: 1.2, // Slightly reduced but still distinct
    opacity: 0.8,
  },
  // card style removed - effectively delegated to Card component
});

import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { Colors } from '../../../theme/colors';
import { Card } from '../../../components/ui/Card';
import { Typography } from '../../../components/ui/Typography';

interface SettingsSectionProps {
  title?: string;
  children: React.ReactNode;
  containerStyle?: any;
}

export function SettingsSection({ title, children, containerStyle }: SettingsSectionProps) {
  const { isDark, colors } = useTheme();

  return (
    <View style={[styles.section, containerStyle]}>
      {title && (
        <View style={styles.headerContainer}>
          <View style={[styles.pill, { backgroundColor: colors[500] }]} />
          <Typography style={[styles.sectionTitle, { color: isDark ? 'white' : '#111827' }]}>
            {title}
          </Typography>
        </View>
      )}
      <Card variant="glass">
        {children}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    marginBottom: 8, // Ultra Compact: 12 -> 8
  },
  sectionTitle: {
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 1.5,
  },
  headerContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
    paddingHorizontal: 4,
  },
  pill: {
    width: 4,
    height: 12,
    borderRadius: 999,
    marginRight: 8,
  },
});

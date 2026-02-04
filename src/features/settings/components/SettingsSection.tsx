import { View } from 'react-native';
import { SettingsCard } from '../../../components/ui/SettingsCard';
import { SettingsSectionHeader } from '../../../components/ui/SettingsSectionHeader';

interface SettingsSectionProps {
  title?: string;
  children: React.ReactNode;
  containerStyle?: any;
}

export function SettingsSection({ title, children, containerStyle }: SettingsSectionProps) {
  return (
    <View style={containerStyle}>
      {title && <SettingsSectionHeader title={title} />}
      <SettingsCard>
        {children}
      </SettingsCard>
    </View>
  );
}

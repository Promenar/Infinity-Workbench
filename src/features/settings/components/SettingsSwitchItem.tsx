import React from 'react';
import { SettingsItem } from './SettingsItem';
import { Switch } from '../../../components/ui/Switch';
import { ActivityIndicator, View } from 'react-native';

interface SettingsSwitchItemProps {
    icon: React.ElementType;
    title: string;
    subtitle?: string;
    value: boolean;
    onValueChange: (value: boolean) => void;
    loading?: boolean;
    disabled?: boolean;
    isLast?: boolean;
}

export function SettingsSwitchItem({
    icon,
    title,
    subtitle,
    value,
    onValueChange,
    loading = false,
    disabled = false,
    isLast = false,
}: SettingsSwitchItemProps) {
    return (
        <SettingsItem
            icon={icon}
            title={title}
            subtitle={subtitle}
            isLast={isLast}
            // We don't use onPress for the row itself to avoid conflict, 
            // or we can make the row toggle the switch (better UX).
            onPress={!disabled && !loading ? () => onValueChange(!value) : undefined}
            rightElement={
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                    {loading ? (
                        <ActivityIndicator size="small" style={{ marginRight: 8 }} />
                    ) : (
                        <Switch
                            value={value}
                            onValueChange={onValueChange}
                            disabled={disabled || loading}
                        />
                    )}
                </View>
            }
        />
    );
}

import React from 'react';
import { View, ViewProps } from 'react-native';
import { Typography } from './Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { clsx } from 'clsx';

interface SettingsSectionHeaderProps extends ViewProps {
    title: string;
    className?: string;
}

export const SettingsSectionHeader: React.FC<SettingsSectionHeaderProps> = ({
    title,
    className,
    style,
    ...props
}) => {
    const { colors } = useTheme();

    return (
        <View
            className={clsx("flex-row items-center mb-2 mt-2", className)}
            style={style}
            {...props}
        >
            <View style={{ backgroundColor: colors[500] }} className="w-1 h-4 rounded-full mr-2" />
            <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
                {title}
            </Typography>
        </View>
    );
};

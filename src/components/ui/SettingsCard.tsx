import React from 'react';
import { View, ViewProps } from 'react-native';
import { clsx } from 'clsx';
import { useTheme } from '../../theme/ThemeProvider';

interface SettingsCardProps extends ViewProps {
    children: React.ReactNode;
    className?: string;
    noPadding?: boolean;
}

export const SettingsCard: React.FC<SettingsCardProps> = ({
    children,
    className,
    style,
    noPadding = false,
    ...props
}) => {
    return (
        <View
            className={clsx(
                "bg-gray-50/80 dark:bg-zinc-900/60 rounded-3xl border border-indigo-50 dark:border-indigo-500/10 mb-3",
                !noPadding && "p-3",
                className
            )}
            style={style}
            {...props}
        >
            {children}
        </View>
    );
};

import React from 'react';
import { TextInput, TextInputProps } from 'react-native';
import { clsx } from 'clsx';
import { useTheme } from '../../theme/ThemeProvider';

interface SettingsInputProps extends TextInputProps {
    className?: string;
    label?: string; // Optional label if we ever want to integrate it
}

export const SettingsInput: React.FC<SettingsInputProps> = ({
    className,
    style,
    placeholderTextColor,
    ...props
}) => {
    return (
        <TextInput
            className={clsx(
                "text-gray-600 dark:text-gray-300 bg-white dark:bg-black p-3 rounded-xl border border-indigo-50 dark:border-indigo-500/10 mb-3",
                className
            )}
            placeholderTextColor={placeholderTextColor || "#94a3b8"}
            style={style}
            {...props}
        />
    );
};

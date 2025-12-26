import React from 'react';
import { View, TouchableOpacity, Platform } from 'react-native';
import { Typography } from './Typography';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { clsx } from 'clsx';
import { Menu } from 'lucide-react-native';

interface HeaderProps {
    title: string;
    subtitle?: string;
    rightAction?: React.ReactNode;
    leftAction?: React.ReactNode;
    showMenu?: boolean;
    onMenuPress?: () => void;
    onTitlePress?: () => void;
    className?: string; // Additional classes
}

export function Header({ title, subtitle, rightAction, leftAction, showMenu, onMenuPress, onTitlePress, className }: HeaderProps) {
    const insets = useSafeAreaInsets();

    return (
        <View
            style={{ paddingTop: insets.top }}
            className={clsx(
                "bg-surface-primary/95 dark:bg-slate-900/95 backdrop-blur-md z-50 border-b border-transparent dark:border-slate-800 shadow-sm",
                className
            )}
        >
            <View className="h-[52px] px-4 flex-row items-center justify-between">
                <View className="flex-row items-center flex-1">
                    <TouchableOpacity
                        onPress={onMenuPress}
                        disabled={!showMenu && !leftAction}
                        className={clsx(
                            "mr-3 w-10 h-10 items-center justify-center rounded-full hover:bg-black/5 active:bg-black/10 dark:active:bg-white/10",
                            (!showMenu && !leftAction) && "opacity-0" // Hide but keep layout space
                        )}
                    >
                        {leftAction ? leftAction : <Menu size={24} color="#64748b" />}
                    </TouchableOpacity>

                    <TouchableOpacity
                        onPress={onTitlePress}
                        disabled={!onTitlePress}
                        activeOpacity={0.7}
                        className="flex-1"
                    >
                        <View>
                            <Typography variant="h2" className="text-slate-900 dark:text-white font-black tracking-tight leading-none" numberOfLines={1}>
                                {title}
                            </Typography>
                            {subtitle && (
                                <Typography className="text-gray-400 font-bold uppercase text-[10px] tracking-widest mt-1 leading-none">
                                    {subtitle}
                                </Typography>
                            )}
                        </View>
                    </TouchableOpacity>
                </View>

                {rightAction && (
                    <View className="flex-row items-center">
                        {rightAction}
                    </View>
                )}
            </View>
        </View>
    );
}

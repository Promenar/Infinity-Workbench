import React from 'react';
import { View, Image, StyleSheet } from 'react-native';
import { Typography } from '../ui';
import * as LucideIcons from 'lucide-react-native';
import { clsx } from 'clsx';

interface AgentAvatarProps {
    id: string;
    name: string;
    avatar?: string;
    color?: string;
    size?: number;
    className?: string;
}

export const AgentAvatar = ({ id, name, avatar, color, size = 40, className }: AgentAvatarProps) => {
    const isImage = avatar?.startsWith('file://') || avatar?.startsWith('http') || avatar?.startsWith('content://') || avatar?.startsWith('data:');

    // 生成基于 ID 的背景色（如果有传入颜色则优先使用）
    const getBackgroundColor = () => {
        if (color) return color;
        const colors = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899'];
        let hash = 0;
        for (let i = 0; i < id.length; i++) {
            hash = id.charCodeAt(i) + ((hash << 5) - hash);
        }
        return colors[Math.abs(hash) % colors.length];
    };

    const renderContent = () => {
        if (isImage) {
            return (
                <Image
                    source={{ uri: avatar }}
                    style={{ width: size, height: size, borderRadius: size / 2 }}
                    className="bg-gray-100 dark:bg-zinc-800"
                />
            );
        }

        // 尝试作为图标渲染
        const IconComponent = (LucideIcons as any)[avatar || ''];
        if (IconComponent) {
            return <IconComponent size={size * 0.6} color="white" />;
        }

        // 默认兜底：首字母
        const initial = name.trim().charAt(0).toUpperCase() || '?';
        return (
            <Typography
                style={{ fontSize: size * 0.45, color: 'white' }}
                className="font-bold"
            >
                {initial}
            </Typography>
        );
    };

    return (
        <View
            style={[
                {
                    width: size,
                    height: size,
                    borderRadius: size / 2.2,
                    backgroundColor: isImage ? 'transparent' : getBackgroundColor(),
                    alignItems: 'center',
                    justifyContent: 'center',
                    overflow: 'hidden'
                }
            ]}
            className={clsx(className)}
        >
            {renderContent()}
        </View>
    );
};

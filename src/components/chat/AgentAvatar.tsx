import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Image } from 'expo-image';
import { Typography } from '../ui';
import * as LucideIcons from 'lucide-react-native';
import { clsx } from 'clsx';
import { useSPAStore } from '../../store/spa-store';

interface AgentAvatarProps {
    id: string;
    name: string;
    avatar?: string;
    color?: string;
    size?: number;
    className?: string;
}

export const AgentAvatar = ({ id, name, avatar, color, size = 40, className }: AgentAvatarProps) => {
    // 1. 获取超级助手配置
    const { preferences } = useSPAStore();
    const isSPA = id === 'super_assistant';
    const [imageError, setImageError] = React.useState(false);

    // 2. 确定最终的头像源和颜色
    let finalAvatar = avatar;
    let finalColor = color;
    let isCustomImage = false;
    let isIcon = false;
    let IconComp: any = null;

    if (isSPA) {
        // 如果是超级助手，优先使用 store 中的配置
        const { iconType, iconColor, customIconUri } = preferences.fab;
        finalColor = preferences.fab.backgroundColor; // 使用 FAB 背景色作为头像背景

        if (iconType === 'custom' && customIconUri && !imageError) {
            finalAvatar = customIconUri;
            isCustomImage = true;
        } else {
            // 预设图标
            isIcon = true;
            IconComp = (LucideIcons as any)[iconType] || LucideIcons.Sparkles;
            finalColor = iconColor; // 对于图标，用图标色作为背景可能太深？或者保持背景色，图标用白色？
            // 修正策略：保持背景色为 FAB 背景色，图标为白色
            finalColor = preferences.fab.backgroundColor;
        }
    } else {
        // 普通 Agent
        isCustomImage = !!(avatar?.startsWith('file://') || avatar?.startsWith('http') || avatar?.startsWith('content://') || avatar?.startsWith('data:'));
        if (imageError) isCustomImage = false;

        // 尝试判断是否为图标名称
        if (!isCustomImage && avatar) {
            const MaybeIcon = (LucideIcons as any)[avatar];
            if (MaybeIcon) {
                isIcon = true;
                IconComp = MaybeIcon;
            }
        }

        // 获取默认背景色
        if (!finalColor) {
            const colors = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899'];
            let hash = 0;
            for (let i = 0; i < id.length; i++) {
                hash = id.charCodeAt(i) + ((hash << 5) - hash);
            }
            finalColor = colors[Math.abs(hash) % colors.length];
        }
    }

    const renderContent = () => {
        if (isCustomImage && finalAvatar) {
            return (
                <Image
                    source={{ uri: finalAvatar }}
                    style={{ width: size, height: size, borderRadius: size / 2 }}
                    className="bg-gray-100 dark:bg-zinc-800"
                    contentFit="cover"
                    cachePolicy="disk"
                    transition={200}
                    onError={() => setImageError(true)}
                />
            );
        }

        if (isIcon && IconComp) {
            // 如果是超级助手，保持图标为白色（背景已有颜色）。如果是普通 Agent，也保持白色。
            return <IconComp size={size * 0.6} color="white" />;
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
                    backgroundColor: isCustomImage && !imageError ? 'transparent' : finalColor,
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

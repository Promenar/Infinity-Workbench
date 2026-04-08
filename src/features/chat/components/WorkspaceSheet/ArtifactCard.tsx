/**
 * ArtifactCard组件
 * 显示Artifact预览卡片，支持点击打开详情和长按菜单
 */

import React, { useCallback, useState } from 'react';
import { View, TouchableOpacity, StyleSheet, Pressable } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';
import * as Haptics from 'expo-haptics';
import {
    PieChart,
    GitBranch,
    SquarePi,
    Code,
    Image,
    FileText,
    ChevronRight,
    Trash2,
    Share2,
    Star,
    StarOff,
} from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import { Artifact, ArtifactType } from '../../../../types/artifact';
import { ARTIFACT_TYPE_INFO, getArtifactTypeColor } from '../../../../constants/artifact-config';

interface ArtifactCardProps {
    artifact: Artifact;
    onPress: (artifact: Artifact) => void;
    onDelete?: (id: string) => void;
    onExport?: (artifact: Artifact) => void;
    onToggleFavorite?: (id: string) => void;
}

const TYPE_ICONS: Record<ArtifactType, React.ElementType> = {
    echarts: PieChart,
    mermaid: GitBranch,
    math: SquarePi,
    html: Code,
    svg: Image,
};

function formatRelativeTime(timestamp: number): string {
    const now = Date.now();
    const diff = now - timestamp;
    const minute = 60 * 1000;
    const hour = 60 * minute;
    const day = 24 * hour;
    const week = 7 * day;

    if (diff < minute) return '刚刚';
    if (diff < hour) return Math.floor(diff / minute) + '分钟前';
    if (diff < day) return Math.floor(diff / hour) + '小时前';
    if (diff < week) return Math.floor(diff / day) + '天前';
    const date = new Date(timestamp);
    return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
}

function truncateTitle(title: string, maxLength: number = 24): string {
    if (title.length <= maxLength) return title;
    return title.slice(0, maxLength) + '...';
}

export const ArtifactCard: React.FC<ArtifactCardProps> = ({
    artifact,
    onPress,
    onDelete,
    onExport,
    onToggleFavorite,
}) => {
    const { isDark } = useTheme();
    const [showMenu, setShowMenu] = useState(false);
    const scale = useSharedValue(1);

    const typeInfo = ARTIFACT_TYPE_INFO[artifact.type];
    const TypeIcon = TYPE_ICONS[artifact.type] || FileText;
    const typeColor = getArtifactTypeColor(artifact.type);
    const typeLabel = typeInfo?.label || artifact.type;

    const handlePressIn = useCallback(() => {
        scale.value = withSpring(0.98, { damping: 15, stiffness: 300 });
    }, [scale]);

    const handlePressOut = useCallback(() => {
        scale.value = withSpring(1, { damping: 15, stiffness: 300 });
    }, [scale]);

    const handlePress = useCallback(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onPress(artifact);
    }, [artifact, onPress]);

    const handleLongPress = useCallback(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        setShowMenu(true);
    }, []);

    const handleDelete = useCallback(() => {
        setShowMenu(false);
        onDelete?.(artifact.id);
    }, [artifact.id, onDelete]);

    const handleExport = useCallback(() => {
        setShowMenu(false);
        onExport?.(artifact);
    }, [artifact, onExport]);

    const handleFavorite = useCallback(() => {
        setShowMenu(false);
        onToggleFavorite?.(artifact.id);
    }, [artifact.id, onToggleFavorite]);

    const handleCloseMenu = useCallback(() => setShowMenu(false), []);

    const animatedStyle = useAnimatedStyle(() => ({
        transform: [{ scale: scale.value }],
    }));

    return (
        <>
            <Pressable
                onPressIn={handlePressIn}
                onPressOut={handlePressOut}
                onPress={handlePress}
                onLongPress={handleLongPress}
                delayLongPress={500}
            >
                <Animated.View
                    style={[
                        styles.container,
                        animatedStyle,
                        {
                            backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fafafa',
                            borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                        },
                    ]}
                >
                    <View style={[styles.typeIcon, { backgroundColor: typeColor + '15' }]}>
                        <TypeIcon size={20} color={typeColor} />
                    </View>
                    <View style={styles.content}>
                        <Typography
                            style={{ fontSize: 14, fontWeight: '500', color: isDark ? '#fff' : '#111' }}
                            numberOfLines={1}
                        >
                            {truncateTitle(artifact.title)}
                        </Typography>
                        <View style={styles.meta}>
                            <View style={[styles.typeTag, { backgroundColor: typeColor + '12' }]}>
                                <Typography style={{ fontSize: 10, color: typeColor, fontWeight: '500' }}>
                                    {typeLabel}
                                </Typography>
                            </View>
                            <Typography style={{ fontSize: 11, color: isDark ? '#52525b' : '#9ca3af' }}>
                                {formatRelativeTime(artifact.createdAt)}
                            </Typography>
                        </View>
                    </View>
                    <ChevronRight size={16} color={isDark ? '#3f3f46' : '#d1d5db'} />
                </Animated.View>
            </Pressable>

            {showMenu && (
                <Pressable style={styles.menuOverlay} onPress={handleCloseMenu}>
                    <View style={[styles.menu, {
                        backgroundColor: isDark ? '#27272a' : '#ffffff',
                        borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                    }]}>
                        <TouchableOpacity style={styles.menuItem} onPress={handleFavorite} activeOpacity={0.7}>
                            {artifact.tags?.includes('favorite') ? (
                                <StarOff size={16} color="#f59e0b" />
                            ) : (
                                <Star size={16} color={isDark ? '#a1a1aa' : '#6b7280'} />
                            )}
                            <Typography style={{ fontSize: 13, color: isDark ? '#fff' : '#111', marginLeft: 10 }}>
                                {artifact.tags?.includes('favorite') ? '取消收藏' : '收藏'}
                            </Typography>
                        </TouchableOpacity>
                        <TouchableOpacity style={styles.menuItem} onPress={handleExport} activeOpacity={0.7}>
                            <Share2 size={16} color={isDark ? '#a1a1aa' : '#6b7280'} />
                            <Typography style={{ fontSize: 13, color: isDark ? '#fff' : '#111', marginLeft: 10 }}>
                                导出
                            </Typography>
                        </TouchableOpacity>
                        <TouchableOpacity style={styles.menuItem} onPress={handleDelete} activeOpacity={0.7}>
                            <Trash2 size={16} color="#ef4444" />
                            <Typography style={{ fontSize: 13, color: '#ef4444', marginLeft: 10 }}>
                                删除
                            </Typography>
                        </TouchableOpacity>
                    </View>
                </Pressable>
            )}
        </>
    );
};

const styles = StyleSheet.create({
    container: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: Spacing[4],
        borderRadius: 16,
        marginBottom: Spacing[3],
        borderWidth: 1,
    },
    typeIcon: {
        width: 44,
        height: 44,
        borderRadius: 12,
        alignItems: 'center',
        justifyContent: 'center',
    },
    content: {
        flex: 1,
        marginLeft: Spacing[3],
    },
    meta: {
        flexDirection: 'row',
        alignItems: 'center',
        marginTop: Spacing[1],
    },
    typeTag: {
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 6,
        marginRight: Spacing[2],
    },
    menuOverlay: {
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: 'rgba(0,0,0,0.3)',
        justifyContent: 'center',
        alignItems: 'center',
    },
    menu: {
        borderRadius: 16,
        padding: Spacing[2],
        borderWidth: 1,
        minWidth: 150,
    },
    menuItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: Spacing[4],
        paddingVertical: Spacing[3],
        borderRadius: 12,
    },
});

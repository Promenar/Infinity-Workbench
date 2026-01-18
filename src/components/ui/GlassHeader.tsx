import React from 'react';
import { View, TouchableOpacity, ViewStyle } from 'react-native';
import { BlurView } from 'expo-blur';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../theme/ThemeProvider';
import { Typography } from './Typography';
import * as Haptics from '../../lib/haptics';
import { preventDoubleTap } from '../../lib/navigation-utils';

export interface GlassHeaderAction {
    icon: React.ReactNode;
    onPress: () => void;
    label?: string;
}

export interface GlassHeaderProps {
    /** 主标题 */
    title: string;
    /** 副标题（可选），如模型名称、状态等 */
    subtitle?: string;
    /** 左侧操作按钮（通常是返回） */
    leftAction?: GlassHeaderAction;
    /** 右侧操作按钮（通常是设置、更多） */
    rightAction?: GlassHeaderAction;
    /** 模糊强度 (0-100)，默认 100 */
    intensity?: number;
    /** 遮罩层透明度 (0-1)，用于增强模糊效果，默认 0.5 */
    overlayOpacity?: number;
    /** 是否显示底部分隔线，默认 true */
    showBorder?: boolean;
    /** 自定义高度（不含安全区），默认 64 */
    height?: number;
    /** 自定义样式 */
    style?: ViewStyle;
    /** 自定义右侧组件（渲染在 rightAction 左侧） */
    headerRight?: React.ReactNode;
}

/**
 * GlassHeader - 毛玻璃风格的页面顶栏
 * 
 * **最佳使用场景**：
 * - 有滚动内容的页面（聊天、列表、文章）
 * - 有背景装饰的页面（渐变、图片、动画）
 * 
 * **不推荐场景**：
 * - 纯色静态背景页面（效果不明显）
 * 
 * @example
 * ```tsx
 * <GlassHeader
 *   title="聊天详情"
 *   subtitle="DeepSeek-V3"
 *   leftAction={{
 *     icon: <ChevronLeft size={24} />,
 *     onPress: () => router.back(),
 *   }}
 *   rightAction={{
 *     icon: <Settings size={20} />,
 *     onPress: () => router.push('/settings'),
 *   }}
 * />
 * ```
 */
export function GlassHeader({
    title,
    subtitle,
    leftAction,
    rightAction,
    intensity = 100,
    overlayOpacity = 0.3,
    showBorder = true,
    height = 64,
    style,
    headerRight,
}: GlassHeaderProps) {
    const insets = useSafeAreaInsets();
    const { isDark } = useTheme();

    const handleAction = (action: GlassHeaderAction) => {
        preventDoubleTap(() => {
            // 遵循 Native Bridge 防御规则：
            // 任何 Haptics 或 Navigation 操作必须延迟执行，避免与 TouchUp 事件冲突锁死 Bridge
            setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                action.onPress();
            }, 15);
        });
    };

    return (
        <BlurView
            intensity={intensity}
            tint={isDark ? 'dark' : 'light'}
            style={[
                {
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    height: height + insets.top,
                    zIndex: 50,
                },
                style,
            ]}
        >
            {/* Semi-transparent overlay to enhance blur effect */}
            <View
                style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    bottom: 0,
                    backgroundColor: isDark
                        ? `rgba(0, 0, 0, ${overlayOpacity})`
                        : `rgba(255, 255, 255, ${overlayOpacity})`,
                }}
            />

            <View
                style={{
                    paddingTop: insets.top,
                    height: height + insets.top,
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    paddingHorizontal: 16,
                }}
            >
                {/* 左侧按钮 */}
                {leftAction ? (
                    <TouchableOpacity
                        onPress={() => handleAction(leftAction)}
                        accessible={true}
                        accessibilityLabel={leftAction.label || '返回'}
                        style={{
                            width: 40,
                            height: 40,
                            borderRadius: 20,
                            backgroundColor: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.10)',
                            alignItems: 'center',
                            justifyContent: 'center',
                        }}
                    >
                        {leftAction.icon}
                    </TouchableOpacity>
                ) : (
                    <View style={{ width: 40 }} />
                )}

                {/* 标题区域 */}
                <View style={{ flex: 1, alignItems: 'center', marginHorizontal: 8 }}>
                    <Typography
                        variant="h3"
                        className="text-[17px] font-black text-gray-900 dark:text-white"
                        numberOfLines={1}
                    >
                        {title}
                    </Typography>
                    {subtitle && (
                        <Typography
                            variant="caption"
                            className="text-[10px] text-gray-500 dark:text-gray-400 uppercase tracking-widest font-bold text-center"
                        >
                            {subtitle}
                        </Typography>
                    )}
                </View>

                {/* 右侧区域 (Custom + Action) */}
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    {headerRight}
                    {rightAction ? (
                        <TouchableOpacity
                            onPress={() => handleAction(rightAction)}
                            accessible={true}
                            accessibilityLabel={rightAction.label || '操作'}
                            style={{
                                width: 40,
                                height: 40,
                                borderRadius: 20,
                                backgroundColor: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.10)',
                                alignItems: 'center',
                                justifyContent: 'center',
                            }}
                        >
                            {rightAction.icon}
                        </TouchableOpacity>
                    ) : (
                        <View style={{ width: 40 }} />
                    )}
                </View>
            </View>

            {/* 底部分隔线 */}
            {showBorder && (
                <View
                    style={{
                        position: 'absolute',
                        bottom: 0,
                        left: 0,
                        right: 0,
                        height: 0.5,
                        backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.15)',
                    }}
                />
            )}
        </BlurView>
    );
}

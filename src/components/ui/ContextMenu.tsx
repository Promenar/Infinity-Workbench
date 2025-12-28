import React, { useState, useRef } from 'react';
import { Modal, TouchableWithoutFeedback, View, TouchableOpacity, Dimensions } from 'react-native';
import Animated, { FadeIn, FadeOut, SlideInUp, SlideOutDown, useAnimatedStyle, withTiming, useSharedValue } from 'react-native-reanimated';
import { Typography } from './Typography';
import { clsx } from 'clsx';
import * as Haptics from 'expo-haptics';

export interface ContextMenuItem {
    label: string;
    icon?: React.ReactNode;
    onPress: () => void;
    destructive?: boolean;
}

interface ContextMenuProps {
    children: React.ReactNode;
    items: ContextMenuItem[];
}

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

/**
 * Lumina 抽屉式上下文菜单
 * 优化了长按触发阈值 (250ms) 并采用抽屉式滑入动画
 */
export function ContextMenu({ children, items }: ContextMenuProps) {
    const [visible, setVisible] = useState(false);
    const [menuPos, setMenuPos] = useState({ x: 0, y: 0, width: 0, height: 0 });
    const triggerRef = useRef<View>(null);

    const handleOpen = () => {
        if (!triggerRef.current) return;

        triggerRef.current.measure((x, y, width, height, pageX, pageY) => {
            // Android 测量兜底
            if (pageX === undefined || pageY === undefined) return;

            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);

            const menuWidth = 220;
            const menuHeight = items.length * 56 + 16;

            let posX = pageX + width / 2 - menuWidth / 2;
            let posY = pageY + height;

            // 边界检查
            if (posX < 20) posX = 20;
            if (posX + menuWidth > SCREEN_WIDTH - 20) posX = SCREEN_WIDTH - menuWidth - 20;

            // 如果底部放不下，则在上方弹出，逻辑依然保持抽屉感
            if (pageY + height + menuHeight > SCREEN_HEIGHT - 60) {
                posY = pageY - menuHeight;
            }

            setMenuPos({ x: posX, y: posY, width: menuWidth, height: menuHeight });
            setVisible(true);
        });
    };

    const handleClose = () => setVisible(false);

    return (
        <>
            <View ref={triggerRef} collapsable={false}>
                <TouchableOpacity
                    onPress={handleOpen}
                    onLongPress={handleOpen}
                    delayLongPress={250} // 显著缩短长按判定时间
                    activeOpacity={0.7}
                >
                    {children}
                </TouchableOpacity>
            </View>

            <Modal visible={visible} transparent animationType="none" onRequestClose={handleClose}>
                <TouchableWithoutFeedback onPress={handleClose}>
                    <View className="flex-1">
                        {/* 背景遮罩淡入 */}
                        <Animated.View
                            entering={FadeIn.duration(200)}
                            exiting={FadeOut.duration(200)}
                            className="absolute inset-0 bg-black/5 dark:bg-black/20"
                        />

                        {/* 菜单内容：抽屉式滑入 */}
                        <Animated.View
                            entering={FadeIn.duration(250).springify().damping(20).stiffness(120)}
                            exiting={FadeOut.duration(150)}
                            style={{
                                position: 'absolute',
                                top: menuPos.y,
                                left: menuPos.x,
                                width: menuPos.width,
                                backgroundColor: 'transparent',
                                shadowColor: '#000',
                                shadowOffset: { width: 0, height: 10 },
                                shadowOpacity: 0.15,
                                shadowRadius: 20,
                                elevation: 12,
                            }}
                        >
                            <Animated.View
                                entering={FadeIn.duration(300)}
                                className="bg-white dark:bg-zinc-900 rounded-2xl overflow-hidden border border-gray-100 dark:border-zinc-800"
                            >
                                {items.map((item, index) => (
                                    <TouchableOpacity
                                        key={index}
                                        onPress={() => {
                                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                            item.onPress();
                                            handleClose();
                                        }}
                                        activeOpacity={0.6}
                                        className={clsx(
                                            "flex-row items-center justify-between py-3.5 px-5",
                                            index < items.length - 1 && "border-b border-gray-50 dark:border-zinc-800/10"
                                        )}
                                    >
                                        <Typography
                                            className={clsx(
                                                "text-[15px] font-bold",
                                                item.destructive ? "text-red-500" : "text-gray-900 dark:text-white"
                                            )}
                                        >
                                            {item.label}
                                        </Typography>
                                        {item.icon && (
                                            <View className="opacity-30">
                                                {React.cloneElement(item.icon as React.ReactElement<any>, {
                                                    size: 18,
                                                    color: item.destructive ? '#ef4444' : '#64748b'
                                                })}
                                            </View>
                                        )}
                                    </TouchableOpacity>
                                ))}
                            </Animated.View>
                        </Animated.View>
                    </View>
                </TouchableWithoutFeedback>
            </Modal>
        </>
    );
}

import React, { useState, useRef } from 'react';
import { Modal, TouchableWithoutFeedback, View, TouchableOpacity, Dimensions, GestureResponderEvent } from 'react-native';
import Animated, {
  FadeIn,
  FadeOut,
  SlideInUp,
  SlideOutDown,
  useAnimatedStyle,
  withTiming,
  useSharedValue,
} from 'react-native-reanimated';
import { Typography } from './Typography';
import { clsx } from 'clsx';
import * as Haptics from '../../lib/haptics';
import { LayoutAnimations } from '../../theme/animations';

export interface ContextMenuItem {
  label: string;
  icon?: React.ReactNode;
  onPress: () => void;
  destructive?: boolean;
}

interface ContextMenuProps {
  children: React.ReactNode;
  items: ContextMenuItem[];
  triggerOnPress?: boolean; // 新增：是否支持短按触发
}

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

/**
 * Lumina 抽屉式上下文菜单
 * 优化了长按触发阈值 (250ms) 并采用抽屉式滑入动画
 */
export function ContextMenu({ children, items, triggerOnPress = false }: ContextMenuProps) {
  const [visible, setVisible] = useState(false);
  const [menuPos, setMenuPos] = useState({ x: 0, y: 0, width: 0, height: 0 });
  const triggerRef = useRef<View>(null);

  const handleOpen = (event?: GestureResponderEvent) => {
    // 优先使用触摸点坐标 (如果有 event)
    if (event && event.nativeEvent && event.nativeEvent.pageX !== undefined) {
      const { pageX, pageY } = event.nativeEvent;
      calculateAndShow(pageX, pageY, 0, 0); // width/height 0 for point anchoring
      return;
    }

    // 降级使用组件测量
    if (!triggerRef.current) return;
    triggerRef.current.measure((x, y, width, height, pageX, pageY) => {
      if (pageX === undefined || pageY === undefined) return;
      calculateAndShow(pageX, pageY, width, height);
    });
  };

  const calculateAndShow = (pageX: number, pageY: number, targetWidth: number, targetHeight: number) => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);

    const menuWidth = 220;
    const menuHeight = items.length * 56 + 16;

    // 默认居中于目标 (如果是触摸点，targetWidth=0)
    let posX = pageX + targetWidth / 2 - menuWidth / 2;
    // 默认在目标下方 (如果是触摸点，就是触摸点下方)
    let posY = pageY + targetHeight;

    // 如果是触摸点触发，增加极小垂直边界，避免手指遮挡但不产生由于间距过大导致的离手感
    if (targetHeight === 0) {
      posY += 8;
    }

    // 边界检查
    if (posX < 20) posX = 20;
    if (posX + menuWidth > SCREEN_WIDTH - 20) posX = SCREEN_WIDTH - menuWidth - 20;

    // 如果底部放不下，则在上方弹出
    if (posY + menuHeight > SCREEN_HEIGHT - 80) { // 留出底部 TabBar 的安全距离
      if (targetHeight === 0) {
        // 触摸点触发：显示在手指上方
        posY = pageY - menuHeight - 20;
      } else {
        // 组件触发：显示在组件上方
        posY = pageY - menuHeight;
      }
    }

    // 🛡️ 顶部边界检查：防止在系统状态栏上方溢出
    if (posY < 60) {
      posY = 60;
    }

    setMenuPos({ x: posX, y: posY, width: menuWidth, height: menuHeight });
    setVisible(true);
  };

  const handleClose = () => setVisible(false);

  return (
    <>
      <View ref={triggerRef} collapsable={false}>
        <TouchableOpacity
          onPress={(e) => {
            if (triggerOnPress) {
              // 确保在这里捕获的是当前点击的坐标
              handleOpen(e);
            }
          }}
          onLongPress={(e) => handleOpen(e)}
          delayLongPress={250}
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
              entering={LayoutAnimations.FadeIn}
              exiting={LayoutAnimations.FadeOut}
              className="absolute inset-0 bg-black/5 dark:bg-black/20"
            />

            {/* 菜单内容：抽屉式滑入 */}
            <Animated.View
              entering={LayoutAnimations.ModalEnter}
              exiting={LayoutAnimations.ModalExit}
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
                      'flex-row items-center justify-between py-3 px-5 h-[52px]', // 统一高度确保稳定感
                      index < items.length - 1 && 'border-b border-gray-50 dark:border-zinc-800/10',
                    )}
                  >
                    <View className="flex-1 mr-4">
                      <Typography
                        className={clsx(
                          'text-[15px] font-bold',
                          item.destructive ? 'text-red-500' : 'text-gray-900 dark:text-white',
                        )}
                        numberOfLines={1}
                      >
                        {item.label}
                      </Typography>
                    </View>
                    <View className="w-5 items-center justify-center">
                      {item.icon ? (
                        <View className="opacity-40">
                          {React.cloneElement(item.icon as React.ReactElement<any>, {
                            size: 18,
                            color: item.destructive ? '#ef4444' : '#64748b',
                          })}
                        </View>
                      ) : (
                        <View className="w-5" /> // 即使没图标也占据空间确保右侧对齐
                      )}
                    </View>
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

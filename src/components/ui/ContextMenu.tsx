import React, { useState, useRef, useCallback, useEffect } from 'react';
import { Modal, TouchableWithoutFeedback, View, TouchableOpacity, Dimensions, GestureResponderEvent, Pressable, useWindowDimensions } from 'react-native';
import Animated, {
  FadeIn,
  FadeOut,
  useSharedValue,
  useAnimatedStyle,
  withSpring,
  withTiming,
  runOnJS,
} from 'react-native-reanimated';
import { Typography } from './Typography';
import { clsx } from 'clsx';
import * as Haptics from '../../lib/haptics';
import { useTheme } from '../../theme/ThemeProvider';
import { Shadows, Spacing } from '../../theme/glass';

export interface ContextMenuItem {
  label: string;
  icon?: React.ReactNode;
  onPress: () => void;
  destructive?: boolean;
}

interface ContextMenuProps {
  children: React.ReactNode;
  items: ContextMenuItem[];
  triggerOnPress?: boolean;
}

const MENU_WIDTH = 200;
const MENU_ITEM_HEIGHT = 48;
const MENU_PADDING = 8;
const TOUCH_OFFSET_Y = 16;
const SCREEN_MARGIN = 16;
const SAFE_AREA_TOP = 60;
const SAFE_AREA_BOTTOM = 100;

const MENU_ENTER_CONFIG = {
  damping: 22,
  stiffness: 280,
  mass: 0.8,
};

const MENU_EXIT_DURATION = 120;

export function ContextMenu({ children, items, triggerOnPress = false }: ContextMenuProps) {
  const { isDark } = useTheme();
  const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = useWindowDimensions();
  
  const [visible, setVisible] = useState(false);
  const [menuPos, setMenuPos] = useState({ x: 0, y: 0, width: MENU_WIDTH });
  const triggerRef = useRef<View>(null);
  const isMounted = useRef(true);

  const scale = useSharedValue(0.85);
  const opacity = useSharedValue(0);

  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const menuHeight = items.length * MENU_ITEM_HEIGHT + MENU_PADDING * 2;

  const calculatePosition = useCallback((pageX: number, pageY: number, targetWidth: number, targetHeight: number) => {
    let posX = pageX + targetWidth / 2 - MENU_WIDTH / 2;
    let posY = pageY + targetHeight;

    if (targetHeight === 0) {
      posY += TOUCH_OFFSET_Y;
    }

    if (posX < SCREEN_MARGIN) posX = SCREEN_MARGIN;
    if (posX + MENU_WIDTH > SCREEN_WIDTH - SCREEN_MARGIN) {
      posX = SCREEN_WIDTH - MENU_WIDTH - SCREEN_MARGIN;
    }

    const bottomBoundary = SCREEN_HEIGHT - SAFE_AREA_BOTTOM;
    if (posY + menuHeight > bottomBoundary) {
      if (targetHeight === 0) {
        posY = pageY - menuHeight - TOUCH_OFFSET_Y;
      } else {
        posY = pageY - menuHeight;
      }
    }

    if (posY < SAFE_AREA_TOP) {
      posY = SAFE_AREA_TOP;
    }

    return { x: posX, y: posY, width: MENU_WIDTH };
  }, [SCREEN_WIDTH, SCREEN_HEIGHT, menuHeight]);

  const handleOpen = useCallback((event?: GestureResponderEvent) => {
    if (event?.nativeEvent && event.nativeEvent.pageX !== undefined) {
      const { pageX, pageY } = event.nativeEvent;
      const position = calculatePosition(pageX, pageY, 0, 0);
      if (isMounted.current) {
        setMenuPos(position);
        setVisible(true);
      }
      return;
    }

    if (!triggerRef.current) return;
    
    triggerRef.current.measure((x, y, width, height, pageX, pageY) => {
      if (pageX === undefined || pageY === undefined || !isMounted.current) return;
      const position = calculatePosition(pageX, pageY, width, height);
      if (isMounted.current) {
        setMenuPos(position);
        setVisible(true);
      }
    });
  }, [calculatePosition]);

  const handleClose = useCallback(() => {
    opacity.value = withTiming(0, { duration: MENU_EXIT_DURATION });
    scale.value = withTiming(0.9, { duration: MENU_EXIT_DURATION }, (finished) => {
      if (finished) {
        runOnJS(setVisible)(false);
      }
    });
  }, []);

  const handleItemPress = useCallback((item: ContextMenuItem) => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    item.onPress();
    handleClose();
  }, [handleClose]);

  useEffect(() => {
    if (visible) {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      opacity.value = withTiming(1, { duration: 100 });
      scale.value = withSpring(1, MENU_ENTER_CONFIG);
    } else {
      scale.value = 0.85;
      opacity.value = 0;
    }
  }, [visible]);

  const animatedMenuStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
    opacity: opacity.value,
  }));

  const animatedBackdropStyle = useAnimatedStyle(() => ({
    opacity: opacity.value * 0.5,
  }));

  return (
    <>
      <View ref={triggerRef} collapsable={false}>
        <Pressable
          onPress={(e: GestureResponderEvent) => {
            if (triggerOnPress) {
              handleOpen(e);
            }
          }}
          onLongPress={(e: GestureResponderEvent) => handleOpen(e)}
          delayLongPress={200}
          style={({ pressed }: { pressed: boolean }) => [
            pressed && triggerOnPress ? { opacity: 0.7 } : null,
          ]}
        >
          {children}
        </Pressable>
      </View>

      <Modal visible={visible} transparent animationType="none" onRequestClose={handleClose} statusBarTranslucent>
        <TouchableWithoutFeedback onPress={handleClose}>
          <View className="flex-1">
            <Animated.View
              style={[
                {
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  right: 0,
                  bottom: 0,
                  backgroundColor: isDark ? 'rgba(0,0,0,0.4)' : 'rgba(0,0,0,0.15)',
                },
                animatedBackdropStyle,
              ]}
            />

            <Animated.View
              style={[
                {
                  position: 'absolute',
                  top: menuPos.y,
                  left: menuPos.x,
                  width: menuPos.width,
                  backgroundColor: isDark ? '#18181b' : '#ffffff',
                  borderRadius: 16,
                  overflow: 'hidden',
                  ...Shadows.md,
                  shadowOpacity: isDark ? 0.4 : 0.12,
                  borderWidth: 0.5,
                  borderColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)',
                },
                animatedMenuStyle,
              ]}
            >
              {items.map((item, index) => (
                <TouchableOpacity
                  key={`${item.label}-${index}`}
                  onPress={() => handleItemPress(item)}
                  activeOpacity={0.6}
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    height: MENU_ITEM_HEIGHT,
                    paddingHorizontal: Spacing[4],
                    borderBottomWidth: index < items.length - 1 ? 0.5 : 0,
                    borderBottomColor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.04)',
                  }}
                >
                  <View style={{ flex: 1, marginRight: Spacing[3] }}>
                    <Typography
                      style={{
                        fontSize: 15,
                        fontWeight: '600',
                        color: item.destructive ? '#ef4444' : (isDark ? '#fafafa' : '#18181b'),
                      }}
                      numberOfLines={1}
                    >
                      {item.label}
                    </Typography>
                  </View>
                  <View style={{ width: 20, alignItems: 'center', justifyContent: 'center' }}>
                    {item.icon ? (
                      React.cloneElement(item.icon as React.ReactElement<any>, {
                        size: 18,
                        color: item.destructive ? '#ef4444' : (isDark ? '#71717a' : '#94a3b8'),
                      })
                    ) : (
                      <View style={{ width: 20 }} />
                    )}
                  </View>
                </TouchableOpacity>
              ))}
            </Animated.View>
          </View>
        </TouchableWithoutFeedback>
      </Modal>
    </>
  );
}

import React, { useState } from 'react';
import { Modal, TouchableWithoutFeedback, View, TouchableOpacity } from 'react-native';
import Animated, { FadeIn, FadeOut, ZoomIn, ZoomOut } from 'react-native-reanimated';
import { Typography } from './Typography';
import { clsx } from 'clsx';
import { BlurView } from 'expo-blur'; // Assuming we might want blur, but for now simple overlay

interface ContextMenuItem {
    label: string;
    icon?: React.ReactNode;
    onPress: () => void;
    destructive?: boolean;
}

interface ContextMenuProps {
    children: React.ReactNode;
    items: ContextMenuItem[];
}

export function ContextMenu({ children, items }: ContextMenuProps) {
    const [visible, setVisible] = useState(false);
    const [position, setPosition] = useState({ x: 0, y: 0 });

    const handleLongPress = (event: any) => {
        // Simple positioning logic - center for now or strictly near touch if possible
        // Native context menus are complex to position perfectly in JS without native modules measure
        // We will center it like a bottom sheet or center modal for robustness on Android
        setVisible(true);
    };

    const handleClose = () => {
        setVisible(false);
    };

    return (
        <>
            <TouchableOpacity
                onLongPress={handleLongPress}
                delayLongPress={250}
                activeOpacity={0.8}
            >
                {children}
            </TouchableOpacity>

            <Modal transparent visible={visible} onRequestClose={handleClose} animationType="none">
                <TouchableWithoutFeedback onPress={handleClose}>
                    <View className="flex-1 bg-black/40 justify-center items-center px-8">
                        <Animated.View
                            entering={ZoomIn.duration(200)}
                            exiting={FadeOut.duration(150)}
                            className="w-full max-w-sm bg-surface-primary rounded-xl overflow-hidden shadow-2xl"
                        >
                            {items.map((item, index) => (
                                <TouchableOpacity
                                    key={index}
                                    onPress={() => {
                                        item.onPress();
                                        handleClose();
                                    }}
                                    className={clsx(
                                        "flex-row items-center p-4 active:bg-surface-secondary",
                                        index < items.length - 1 && "border-b border-border-subtle"
                                    )}
                                >
                                    {item.icon && <View className="mr-3">{item.icon}</View>}
                                    <Typography
                                        variant="body"
                                        className={item.destructive ? "text-red-500 font-medium" : "text-text-primary"}
                                    >
                                        {item.label}
                                    </Typography>
                                </TouchableOpacity>
                            ))}
                        </Animated.View>
                    </View>
                </TouchableWithoutFeedback>
            </Modal>
        </>
    );
}

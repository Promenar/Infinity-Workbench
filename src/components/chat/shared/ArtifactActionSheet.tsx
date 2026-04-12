/**
 * ArtifactActionSheet - Artifact 长按上下文菜单
 *
 * 在长按 artifact 卡片时弹出，提供导出、复制数据、删除等操作。
 */

import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Modal } from 'react-native';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown } from 'react-native-reanimated';
import { Download, Copy, Trash2, ExternalLink, X } from 'lucide-react-native';

export interface ActionSheetAction {
    key: string;
    label: string;
    icon?: React.ReactNode;
    destructive?: boolean;
    onPress: () => void;
}

interface ArtifactActionSheetProps {
    visible: boolean;
    title?: string;
    actions: ActionSheetAction[];
    onClose: () => void;
    isDark?: boolean;
}

export const ArtifactActionSheet: React.FC<ArtifactActionSheetProps> = ({
    visible,
    title,
    actions,
    onClose,
    isDark = false,
}) => {
    return (
        <Modal
            visible={visible}
            transparent
            animationType="none"
            onRequestClose={onClose}
        >
            <Animated.View
                entering={FadeIn.duration(150)}
                exiting={FadeOut.duration(100)}
                style={styles.overlay}
            >
                <TouchableOpacity
                    style={styles.overlayTouch}
                    activeOpacity={1}
                    onPress={onClose}
                />
                <Animated.View
                    entering={SlideInDown.duration(200)}
                    exiting={SlideOutDown.duration(150)}
                    style={[styles.sheet, {
                        backgroundColor: isDark ? '#1c1c1e' : '#fff',
                    }]}
                >
                    {/* Header */}
                    {title && (
                        <View style={[styles.header, { borderBottomColor: isDark ? '#2c2c2e' : '#f3f4f6' }]}>
                            <Text style={[styles.title, { color: isDark ? '#f4f4f5' : '#111827' }]} numberOfLines={1}>
                                {title}
                            </Text>
                            <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
                                <X size={18} color={isDark ? '#71717a' : '#9ca3af'} />
                            </TouchableOpacity>
                        </View>
                    )}

                    {/* Actions */}
                    <View style={styles.actionList}>
                        {actions.map((action, index) => (
                            <TouchableOpacity
                                key={action.key}
                                style={[
                                    styles.actionItem,
                                    index < actions.length - 1 && { borderBottomColor: isDark ? '#2c2c2e' : '#f3f4f6', borderBottomWidth: 1 },
                                ]}
                                onPress={() => { action.onPress(); onClose(); }}
                                activeOpacity={0.6}
                            >
                                <View style={styles.actionIconContainer}>
                                    {action.icon}
                                </View>
                                <Text style={[
                                    styles.actionLabel,
                                    action.destructive
                                        ? { color: '#ef4444' }
                                        : { color: isDark ? '#f4f4f5' : '#111827' },
                                ]}>
                                    {action.label}
                                </Text>
                            </TouchableOpacity>
                        ))}
                    </View>

                    {/* Cancel */}
                    <TouchableOpacity
                        style={[styles.cancelBtn, { backgroundColor: isDark ? '#2c2c2e' : '#f3f4f6' }]}
                        onPress={onClose}
                        activeOpacity={0.6}
                    >
                        <Text style={[styles.cancelText, { color: isDark ? '#a1a1aa' : '#6b7280' }]}>
                            取消
                        </Text>
                    </TouchableOpacity>
                </Animated.View>
            </Animated.View>
        </Modal>
    );
};

const styles = StyleSheet.create({
    overlay: {
        flex: 1,
        justifyContent: 'flex-end',
    },
    overlayTouch: {
        flex: 1,
    },
    sheet: {
        borderTopLeftRadius: 20,
        borderTopRightRadius: 20,
        paddingBottom: 34, // safe area
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 16,
        paddingVertical: 14,
        borderBottomWidth: 1,
    },
    title: {
        fontSize: 16,
        fontWeight: '700',
        flex: 1,
        marginRight: 12,
    },
    closeBtn: {
        padding: 4,
    },
    actionList: {
        paddingHorizontal: 16,
    },
    actionItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 14,
    },
    actionIconContainer: {
        width: 28,
        marginRight: 12,
        alignItems: 'center',
    },
    actionLabel: {
        fontSize: 16,
        fontWeight: '500',
    },
    cancelBtn: {
        marginHorizontal: 16,
        marginTop: 8,
        paddingVertical: 14,
        borderRadius: 12,
        alignItems: 'center',
    },
    cancelText: {
        fontSize: 16,
        fontWeight: '600',
    },
});

import React from 'react';
import { View, Text, TouchableOpacity, Modal, SafeAreaView, StatusBar, Platform } from 'react-native';
import { X } from 'lucide-react-native';
import { sharedArtifactStyles as styles } from './styles';
import { PhoneRotateIcon } from './PhoneRotateIcon';

interface FullscreenModalProps {
    visible: boolean;
    title: string;
    isDark: boolean;
    isLandscape: boolean;
    accentColor: string;
    onClose: () => void;
    onToggleOrientation: () => void;
    children: React.ReactNode;
    headerRight?: React.ReactNode;
}

/**
 * FullscreenModal - 全屏图表查看 Modal 壳组件
 * 包含 header（标题、关闭按钮、可选额外按钮）和旋转 FAB
 */
export const FullscreenModal: React.FC<FullscreenModalProps> = ({
    visible,
    title,
    isDark,
    isLandscape,
    accentColor,
    onClose,
    onToggleOrientation,
    children,
    headerRight,
}) => {
    return (
        <Modal
            visible={visible}
            animationType="fade"
            presentationStyle="fullScreen"
            onRequestClose={onClose}
        >
            <SafeAreaView style={{ flex: 1, backgroundColor: isDark ? '#000' : '#fff', paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight || 0) : 0 }}>
                <View style={[styles.modalHeader, { borderBottomColor: isDark ? '#1c1c1e' : '#f3f4f6' }]}>
                    <Text style={[styles.modalTitle, { color: isDark ? '#fff' : '#000' }]} numberOfLines={1}>
                        {title}
                    </Text>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                        {headerRight}
                        <TouchableOpacity
                            style={[styles.closeIconButton, { backgroundColor: isDark ? '#1c1c1e' : '#f3f4f6' }]}
                            onPress={onClose}
                            accessibilityRole="button"
                            accessibilityLabel="关闭"
                        >
                            <X size={20} color={isDark ? '#fff' : '#666'} />
                        </TouchableOpacity>
                    </View>
                </View>

                {children}

                {/* Landscape Toggle FAB */}
                <TouchableOpacity
                    style={[styles.fab, {
                        backgroundColor: accentColor,
                        shadowColor: accentColor,
                    }]}
                    onPress={onToggleOrientation}
                    accessibilityRole="button"
                    accessibilityLabel="切换横竖屏"
                    accessibilityHint="切换横屏或竖屏查看"
                >
                    <PhoneRotateIcon size={28} color="#fff" />
                </TouchableOpacity>
            </SafeAreaView>
        </Modal>
    );
};

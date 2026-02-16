import React, { useEffect } from 'react';
import { View, Modal, TouchableOpacity, StyleSheet, Dimensions, BackHandler } from 'react-native';
import Animated, { FadeIn, FadeOut, ZoomIn, ZoomOut } from 'react-native-reanimated';
import { Typography } from './Typography';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';
import { Glass } from '../../theme/glass';
import * as Haptics from '../../lib/haptics';

interface GlassAlertProps {
    visible: boolean;
    title: string;
    message: string;
    confirmText?: string;
    cancelText?: string;
    showCancel?: boolean;
    onConfirm?: () => void;
    onCancel?: () => void;
    isDestructive?: boolean;
}

const { width } = Dimensions.get('window');

/**
 * A highly styled alert component with glassmorphism effects.
 * Designed to replace native Alert.alert for a consistent app experience.
 */
export const GlassAlert: React.FC<GlassAlertProps> = ({
    visible,
    title,
    message,
    confirmText = 'OK',
    cancelText = 'Cancel',
    showCancel = false,
    onConfirm,
    onCancel,
    isDestructive = false,
}) => {
    const { isDark, colors } = useTheme();

    // Handle hardware back button
    useEffect(() => {
        const onBackPress = () => {
            if (visible) {
                if (onCancel) onCancel();
                return true;
            }
            return false;
        };
        const subscription = BackHandler.addEventListener('hardwareBackPress', onBackPress);
        return () => subscription.remove();
    }, [visible, onCancel]);

    if (!visible) return null;

    const handleConfirm = () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            if (onConfirm) onConfirm();
        }, 10);
    };

    const handleCancel = () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            if (onCancel) onCancel();
        }, 10);
    };

    // Glass settings - Matching GlassBottomSheet exactly
    const glassIntensity = Glass.Header.intensity;
    // Use Header opacity (0.15/0.25) to match the toolbox transparency
    const glassOpacity = isDark ? Glass.Header.opacity.dark : Glass.Header.opacity.light;
    const glassTint = isDark ? Glass.Header.tint.dark : Glass.Header.tint.light;
    // Base color helps tint the blur slightly
    const baseColor = isDark ? `rgba(0, 0, 0, ${glassOpacity})` : `rgba(255, 255, 255, ${glassOpacity})`;

    return (
        <Modal transparent visible={visible} animationType="none" onRequestClose={handleCancel}>
            <View style={styles.container}>
                {/* Backdrop */}
                <Animated.View
                    entering={FadeIn.duration(200)}
                    exiting={FadeOut.duration(150)}
                    style={StyleSheet.absoluteFill}
                >
                    <View style={{ flex: 1, backgroundColor: isDark ? 'rgba(0,0,0,0.6)' : 'rgba(0,0,0,0.3)' }} />
                </Animated.View>

                {/* Alert Box */}
                <Animated.View
                    entering={ZoomIn.duration(250)}
                    exiting={ZoomOut.duration(150)}
                    style={styles.modalContent}
                >
                    <View
                        style={{
                            borderRadius: 24,
                            overflow: 'hidden',
                            borderWidth: 1,
                            borderColor: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
                            backgroundColor: 'transparent',
                            // Shadow
                            shadowColor: '#000',
                            shadowOffset: { width: 0, height: 10 },
                            shadowOpacity: 0.2,
                            shadowRadius: 20,
                            elevation: 10,
                        }}
                    >
                        <BlurView
                            intensity={glassIntensity}
                            tint={glassTint as any}
                            experimentalBlurMethod='dimezisBlurView'
                            style={StyleSheet.absoluteFill}
                        />
                        {/* Color Overlay for Tint */}
                        <View style={[StyleSheet.absoluteFill, { backgroundColor: baseColor }]} />

                        <View className="p-6 items-center">
                            <Typography className="text-xl font-bold text-gray-900 dark:text-white mb-2 text-center">
                                {title}
                            </Typography>
                            <Typography className="text-base text-gray-600 dark:text-gray-300 text-center leading-6">
                                {message}
                            </Typography>
                        </View>

                        {/* Buttons */}
                        <View className="flex-row border-t border-gray-100/20 dark:border-white/10">
                            {showCancel && (
                                <TouchableOpacity
                                    onPress={handleCancel}
                                    className="flex-1 py-4 items-center justify-center border-r border-gray-100/20 dark:border-white/10 active:bg-black/5 dark:active:bg-white/5"
                                    activeOpacity={0.7}
                                >
                                    <Typography className="text-base font-semibold text-gray-500 dark:text-gray-400">
                                        {cancelText}
                                    </Typography>
                                </TouchableOpacity>
                            )}

                            <TouchableOpacity
                                onPress={handleConfirm}
                                className="flex-1 py-4 items-center justify-center active:bg-black/5 dark:active:bg-white/5"
                                activeOpacity={0.7}
                            >
                                <Typography
                                    className={`text-base font-bold ${isDestructive ? 'text-red-500' : 'text-indigo-500 dark:text-indigo-400'}`}
                                >
                                    {confirmText}
                                </Typography>
                            </TouchableOpacity>
                        </View>
                    </View>
                </Animated.View>
            </View>
        </Modal>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        padding: 30,
    },
    modalContent: {
        width: width - 80,
        maxWidth: 320,
    },
});

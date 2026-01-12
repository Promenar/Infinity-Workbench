import React from 'react';
import { View, Modal, TouchableOpacity, StyleSheet, Dimensions } from 'react-native';
import Animated, { FadeIn, FadeOut, FadeInUp } from 'react-native-reanimated';
import { Typography } from './Typography';
import { BlurView } from 'expo-blur';
import { useTheme } from '../../theme/ThemeProvider';
import * as Haptics from '../../lib/haptics';
import { LayoutAnimations } from '../../theme/animations';

interface ConfirmDialogProps {
  visible: boolean;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  isDestructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

const { width } = Dimensions.get('window');

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  visible,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  isDestructive = false,
  onConfirm,
  onCancel,
}) => {
  const { isDark } = useTheme();
  if (!visible) return null;

  const handleConfirm = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      onConfirm();
    }, 10);
  };

  const handleCancel = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      onCancel();
    }, 10);
  };

  return (
    <Modal transparent visible={visible} animationType="none">
      <View style={styles.container}>
        <Animated.View
          entering={FadeIn.duration(150)}
          exiting={FadeOut.duration(100)}
          style={StyleSheet.absoluteFill}
        >
          <TouchableOpacity activeOpacity={1} onPress={handleCancel} style={styles.backdrop} />
        </Animated.View>

        <Animated.View
          entering={FadeInUp.duration(250)}
          exiting={FadeOut.duration(150)}
          style={styles.modalContent}
        >
          <View
            style={{
              backgroundColor: isDark ? 'rgba(15, 17, 26, 0.7)' : 'rgba(255, 255, 255, 0.8)',
              borderColor: isDark ? 'rgba(99, 102, 241, 0.2)' : 'rgba(0, 0, 0, 0.05)',
            }}
            className="rounded-2xl overflow-hidden shadow-2xl border"
          >
            <BlurView
              intensity={isDark ? 30 : 60}
              tint={isDark ? 'dark' : 'light'}
              style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
            />
            <View className="p-8">
              <Typography className="text-xl font-bold text-gray-900 dark:text-white mb-3 text-center">
                {title}
              </Typography>
              <Typography className="text-base text-gray-500 dark:text-gray-400 text-center leading-6">
                {message}
              </Typography>
            </View>

            <View className="flex-row border-t border-gray-50 dark:border-zinc-800/50">
              <TouchableOpacity
                onPress={handleCancel}
                className="flex-1 py-5 items-center justify-center border-r border-gray-50 dark:border-zinc-800/50"
                activeOpacity={0.7}
              >
                <Typography className="text-base font-semibold text-gray-400 dark:text-gray-500">
                  {cancelText}
                </Typography>
              </TouchableOpacity>

              <TouchableOpacity
                onPress={handleConfirm}
                className="flex-1 py-5 items-center justify-center"
                activeOpacity={0.7}
              >
                <Typography
                  className={`text-base font-bold ${isDestructive ? 'text-red-500' : 'text-indigo-500'}`}
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
    padding: 40,
  },
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  modalContent: {
    width: width - 80,
    maxWidth: 320,
  },
});

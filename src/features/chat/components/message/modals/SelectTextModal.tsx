import React, { useState, useEffect } from 'react';
import {
  View,
  TouchableOpacity,
  Modal,
  ScrollView,
  Platform,
  StyleSheet,
} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from '../../../../../lib/haptics';
import { Typography } from '../../../../../components/ui';
import { X, Copy } from 'lucide-react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  runOnJS,
} from 'react-native-reanimated';

interface SelectTextModalProps {
  isVisible: boolean;
  content: string;
  onClose: () => void;
  isDark: boolean;
  t: any;
  colors: any;
}

export const SelectTextModal: React.FC<SelectTextModalProps> = ({
  isVisible,
  content,
  onClose,
  isDark,
  t,
  colors,
}) => {
  const bgOpacity = useSharedValue(0);
  const contentTranslateY = useSharedValue(600);
  const [modalVisible, setModalVisible] = useState(isVisible);

  useEffect(() => {
    if (isVisible) {
      setModalVisible(true);
      bgOpacity.value = withTiming(1, { duration: 300 });
      contentTranslateY.value = withTiming(0, { duration: 400 });
    } else {
      handleDismiss();
    }
  }, [isVisible]);

  const handleDismiss = () => {
    bgOpacity.value = withTiming(0, { duration: 300 });
    contentTranslateY.value = withTiming(600, { duration: 400 }, (finished: boolean | undefined) => {
      if (finished) {
        runOnJS(setModalVisible)(false);
      }
    });
  };

  const handleCopy = async () => {
    await Clipboard.setStringAsync(content);
    setTimeout(() => {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    }, 10);
    onClose();
  };

  const bgStyle = useAnimatedStyle(() => ({
    opacity: bgOpacity.value,
    backgroundColor: 'rgba(0,0,0,0.5)',
    ...StyleSheet.absoluteFillObject,
  }));

  const contentStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: contentTranslateY.value }],
  }));

  if (!modalVisible && !isVisible) return null;

  return (
    <Modal
      visible={modalVisible}
      animationType="none"
      transparent={true}
      onRequestClose={onClose}
      statusBarTranslucent={true}
    >
      <View style={{ flex: 1, justifyContent: 'flex-end' }}>
        <Animated.View style={bgStyle}>
          <TouchableOpacity activeOpacity={1} onPress={onClose} style={{ flex: 1 }} />
        </Animated.View>

        <Animated.View
          style={[
            {
              width: '100%',
              height: '80%',
              backgroundColor: isDark ? '#18181b' : '#ffffff',
              borderTopLeftRadius: 24,
              borderTopRightRadius: 24,
              padding: 24,
              paddingBottom: Platform.OS === 'ios' ? 40 : 24,
            },
            contentStyle,
          ]}
        >
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 16,
            }}
          >
            <TouchableOpacity onPress={onClose} style={{ padding: 8 }}>
              <X size={24} color={isDark ? '#e4e4e7' : '#27272a'} />
            </TouchableOpacity>
            <Typography style={{ fontSize: 16, fontWeight: 'bold' }}>
              {t?.agent?.viewAndSelectText || 'Select Text'}
            </Typography>
            <TouchableOpacity
              onPress={handleCopy}
              style={[{ backgroundColor: colors[500] }, { padding: 8, borderRadius: 100 }]}
            >
              <Copy size={20} color="#fff" />
            </TouchableOpacity>
          </View>

          <Typography variant="caption" style={{ marginBottom: 16, color: '#6b7280' }}>
            {t?.agent?.textSelectionHint}
          </Typography>

          <ScrollView style={{ flex: 1 }} showsVerticalScrollIndicator={false}>
            <Typography
              selectable={true}
              style={{
                color: isDark ? '#fafafa' : '#18181b',
                fontSize: 16,
                lineHeight: 26,
              }}
            >
              {content}
            </Typography>
          </ScrollView>
        </Animated.View>
      </View>
    </Modal>
  );
};

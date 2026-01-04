import React from 'react';
import { View, Modal, Image, TouchableOpacity, Dimensions } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { X } from 'lucide-react-native';
import Animated, { FadeIn, FadeOut, ZoomIn, ZoomOut } from 'react-native-reanimated';

interface ImagePreviewModalProps {
  visible: boolean;
  imageUri: string | null;
  onClose: () => void;
}

const { width, height } = Dimensions.get('window');

export const ImagePreviewModal: React.FC<ImagePreviewModalProps> = ({
  visible,
  imageUri,
  onClose,
}) => {
  if (!imageUri) return null;

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View className="flex-1 bg-black items-center justify-center">
        <TouchableOpacity
          onPress={onClose}
          className="absolute top-12 right-6 z-50 p-2 bg-black/50 rounded-full"
        >
          <X size={24} color="white" />
        </TouchableOpacity>

        <Animated.Image
          entering={ZoomIn}
          exiting={ZoomOut}
          source={{ uri: imageUri }}
          style={{ width: width, height: height * 0.8 }}
          resizeMode="contain"
        />
      </View>
    </Modal>
  );
};

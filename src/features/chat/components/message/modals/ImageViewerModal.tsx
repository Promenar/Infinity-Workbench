import React from 'react';
import { View, Modal, TouchableOpacity, Image, Dimensions } from 'react-native';
import { X } from 'lucide-react-native';

interface ImageViewerModalProps {
  visible: boolean;
  uri: string;
  onClose: () => void;
}

export const ImageViewerModal: React.FC<ImageViewerModalProps> = ({
  visible,
  uri,
  onClose,
}) => {
  return (
    <Modal visible={visible} transparent={true} animationType="fade" onRequestClose={onClose}>
      <View
        style={{
          flex: 1,
          backgroundColor: 'black',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <TouchableOpacity
          style={{ position: 'absolute', top: 50, right: 20, zIndex: 10, padding: 10 }}
          onPress={onClose}
        >
          <X size={28} color="white" />
        </TouchableOpacity>
        <Image
          source={{ uri }}
          style={{ width: Dimensions.get('window').width, height: Dimensions.get('window').height }}
          resizeMode="contain"
        />
      </View>
    </Modal>
  );
};

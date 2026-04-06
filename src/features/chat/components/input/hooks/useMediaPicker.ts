import { useState } from 'react';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as Haptics from '../../../../../lib/haptics';
import { documentService } from '../../../../../lib/file/document-service';
import { ChatAttachment } from '../../../../../types/chat';

export interface MediaPickerConfirmState {
  visible: boolean;
  title: string;
  message: string;
  onConfirm: () => void;
}

export const useMediaPicker = (t: any) => {
  const [selectedImages, setSelectedImages] = useState<string[]>([]);
  const [selectedFiles, setSelectedFiles] = useState<ChatAttachment[]>([]);
  const [confirmState, setConfirmState] = useState<MediaPickerConfirmState>({
    visible: false,
    title: '',
    message: '',
    onConfirm: () => { },
  });

  const handlePickImage = async (source: 'camera' | 'library') => {
    try {
      let result;
      if (source === 'camera') {
        const { status } = await ImagePicker.requestCameraPermissionsAsync();
        if (status !== 'granted') {
          setConfirmState({
            visible: true,
            title: t.chat.cameraPermission,
            message: t.chat.cameraPermissionMessage,
            onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
          });
          return;
        }
        result = await ImagePicker.launchCameraAsync({
          mediaTypes: ImagePicker.MediaTypeOptions.Images,
          allowsEditing: false,
          quality: 0.7,
        });
      } else {
        const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
        if (status !== 'granted') {
          setConfirmState({
            visible: true,
            title: t.chat.galleryPermission,
            message: t.chat.galleryPermissionMessage,
            onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
          });
          return;
        }
        result = await ImagePicker.launchImageLibraryAsync({
          mediaTypes: ImagePicker.MediaTypeOptions.Images,
          allowsMultipleSelection: true,
          quality: 0.7,
        });
      }

      if (!result.canceled) {
        const newImages: string[] = [];
        const imgDir = (FileSystem.documentDirectory || '') + 'images/';
        try {
          await FileSystem.makeDirectoryAsync(imgDir, { intermediates: true });
        } catch (e) {
          console.warn('[useMediaPicker] Failed to create image directory');
        }

        for (const asset of result.assets) {
          try {
            const filename = `img_${Date.now()}_${Math.random().toString(36).slice(2)}.jpg`;
            const dest = imgDir + filename;
            await FileSystem.copyAsync({
              from: asset.uri,
              to: dest,
            });
            newImages.push(dest);
          } catch (copyError) {
            console.warn('[useMediaPicker] Copy failed, using original URI:', copyError);
            newImages.push(asset.uri);
          }
        }

        if (newImages.length > 0) {
          setSelectedImages((prev) => [...prev, ...newImages]);
          setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
          }, 10);
        }
      }
    } catch (e) {
      console.error('Image picker error:', e);
      setConfirmState({
        visible: true,
        title: t.chat.imageSelectionError,
        message: `${t.chat.imageSelectionErrorMessage}\n\nDebug Info: ${(e as Error).message}`,
        onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
      });
    }
  };

  const handlePickFile = async () => {
    try {
      const file = await documentService.pickDocument();
      if (file) {
        setSelectedFiles((prev) => [...prev, file]);
        setTimeout(() => {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        }, 10);
      }
    } catch (e) {
      console.error('File picker error:', e);
    }
  };

  const removeImage = (index: number) => {
    setSelectedImages((prev) => prev.filter((_, i) => i !== index));
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }, 10);
  };

  const removeFile = (index: number) => {
    setSelectedFiles((prev) => prev.filter((_, i) => i !== index));
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }, 10);
  };

  const clearAttachments = () => {
    setSelectedImages([]);
    setSelectedFiles([]);
  };

  return {
    selectedImages,
    selectedFiles,
    handlePickImage,
    handlePickFile,
    removeImage,
    removeFile,
    clearAttachments,
    confirmState,
    setConfirmState,
  };
};

import { useCallback } from 'react';
import { InteractionManager, Alert } from 'react-native';
import { captureRef } from 'react-native-view-shot';
import * as Sharing from 'expo-sharing';

export const useMessageActions = (bubbleRef: React.RefObject<any>) => {
  const handleShare = useCallback(async () => {
    if (!bubbleRef.current) return;
    try {
      // Wait for rendering to stabilize
      const { InteractionManager } = require('react-native');
      await new Promise<void>((resolve) => {
        InteractionManager.runAfterInteractions(() => {
          setTimeout(resolve, 100); 
        });
      });

      if (!bubbleRef.current) return;

      const uri = await captureRef(bubbleRef.current, {
        format: 'png',
        quality: 0.9,
      });
      await Sharing.shareAsync(uri);
    } catch (error) {
      console.error('[useMessageActions] Snapshot failed', error);
      Alert.alert('分享失败', '无法捕获消息截图，请稍后重试');
    }
  }, [bubbleRef]);

  return { 
    handleShare 
  };
};

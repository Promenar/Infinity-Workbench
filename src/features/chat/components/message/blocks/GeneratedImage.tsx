import React, { useState } from 'react';
import {
  View,
  TouchableOpacity,
  ActivityIndicator,
  Image,
  Dimensions,
} from 'react-native';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import * as Haptics from '../../../../../lib/haptics';
import { Download, Check, X, Expand, AlertCircle } from 'lucide-react-native';
import { Typography } from '../../../../../components/ui';

interface GeneratedImageProps {
  src: string;
  alt?: string;
  isDark: boolean;
  t: any;
  onImagePress?: (uri: string) => void;
}

export const GeneratedImage: React.FC<GeneratedImageProps> = React.memo(
  ({ src, alt, isDark, t, onImagePress }) => {
    const [isLoading, setIsLoading] = useState(true);
    const [hasError, setHasError] = useState(false);
    const [dimensions, setDimensions] = useState<{ width: number; height: number } | null>(null);

    const handleDownload = async () => {
      try {
        if (await Sharing.isAvailableAsync()) {
          let shareSrc = src;
          try {
            const { getOriginalFromThumbnail } = require('../../../../lib/image-utils');
            const original = getOriginalFromThumbnail(src);
            if (original && original !== src && !original.startsWith('data:')) {
              const info = await FileSystem.getInfoAsync(original);
              if (info.exists) {
                shareSrc = original;
              }
            }
          } catch (e) {
            console.warn('Failed to resolve original for share', e);
          }

          await Sharing.shareAsync(shareSrc);
          setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
          }, 10);
        } else {
          alert('Sharing is not available on this platform');
        }
      } catch (e) {
        console.error('Save failed', e);
        setTimeout(() => {
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        }, 10);
      }
    };

    return (
      <View style={{ marginVertical: 12 }}>
        <View
          style={{
            width: '100%',
            minHeight: 200,
            backgroundColor: isDark ? '#27272a' : '#f4f4f5',
            borderRadius: 12,
            overflow: 'hidden',
            position: 'relative',
            justifyContent: 'center',
            alignItems: 'center',
            borderWidth: 1,
            borderColor: isDark ? '#3f3f46' : '#e4e4e7',
          }}
        >
          <TouchableOpacity
            activeOpacity={0.9}
            style={{ width: '100%', alignItems: 'center' }}
            onPress={() => {
              if (onImagePress) {
                try {
                  const { getOriginalFromThumbnail } = require('../../../../lib/image-utils');
                  const original = getOriginalFromThumbnail(src);
                  onImagePress(original || src);
                } catch {
                  onImagePress(src);
                }
              }
            }}
          >
            {isLoading && (
              <View style={{ position: 'absolute', zIndex: 10 }}>
                <ActivityIndicator size="large" color={isDark ? '#a1a1aa' : '#6b7280'} />
              </View>
            )}

            {hasError ? (
              <View style={{ alignItems: 'center', padding: 20 }}>
                <AlertCircle size={32} color="#ef4444" />
                <Typography
                  variant="caption"
                  style={{ color: '#ef4444', marginTop: 8, textAlign: 'center' }}
                >
                  {t.agent.imageLoadError}
                </Typography>
                <Typography
                  variant="caption"
                  style={{ color: isDark ? '#71717a' : '#a1a1aa', marginTop: 4, fontSize: 11 }}
                >
                  {src.startsWith('data:') ? t.agent.imageTooLarge : t.agent.imagePathError}
                </Typography>
              </View>
            ) : (
              <Image
                source={{ uri: src }}
                style={{
                  width: '100%',
                  height: dimensions
                    ? (dimensions.height / dimensions.width) * Dimensions.get('window').width * 0.9
                    : 300,
                  maxHeight: 600,
                }}
                resizeMode="contain"
                accessibilityLabel={alt}
                onLoad={(e) => {
                  const { width, height } = e.nativeEvent.source;
                  setDimensions({ width, height });
                  setIsLoading(false);
                }}
                onError={(e) => {
                  console.warn('Image load error:', e.nativeEvent.error);
                  setHasError(true);
                  setIsLoading(false);
                }}
              />
            )}

            {!hasError && !isLoading && (
              <TouchableOpacity
                onPress={handleDownload}
                style={{
                  position: 'absolute',
                  bottom: 12,
                  right: 12,
                  backgroundColor: 'rgba(0,0,0,0.7)',
                  padding: 10,
                  borderRadius: 20,
                  borderWidth: 1,
                  borderColor: 'rgba(255,255,255,0.3)',
                }}
              >
                <Download size={18} color="#fff" />
              </TouchableOpacity>
            )}
          </TouchableOpacity>
        </View>
      </View>
    );
  },
);

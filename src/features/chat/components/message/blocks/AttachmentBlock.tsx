import React from 'react';
import { View } from 'react-native';
import { useMessageContext } from '../MessageContext';
import { SafeUserImage } from '../../SafeUserImage';
import { FileText } from 'lucide-react-native';
import { Typography } from '../../../../../components/ui';
import { Borders } from '../../../../../theme/glass';

export const AttachmentBlock: React.FC = React.memo(() => {
  const { message, isDark, onViewImage } = useMessageContext();

  const hasImages = message.images && message.images.length > 0;
  const hasFiles = message.files && message.files.length > 0;

  if (!hasImages && !hasFiles) return null;

  return (
    <View style={{ marginTop: (message.content || message.reasoning) ? 8 : 0 }}>
      {/* Images Grid */}
      {hasImages && (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 4 }}>
          {message.images?.map((img, index) => (
            <SafeUserImage
              key={`img-${index}`}
              uri={img.thumbnail}
              onPress={() => onViewImage?.(img.thumbnail)}
              isDark={isDark}
            />
          ))}
        </View>
      )}

      {/* Files List */}
      {hasFiles && (
        <View style={{ marginTop: 8, flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {message.files?.map((file, idx) => (
            <View
              key={`file-${idx}`}
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)',
                borderWidth: 1,
                borderColor: isDark ? Borders.glass.dark : Borders.glass.light,
                padding: 8,
                borderRadius: 12,
                maxWidth: '100%',
              }}
            >
              <View
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: 8,
                  backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginRight: 8,
                }}
              >
                <FileText size={18} color={isDark ? '#e4e4e7' : '#4b5563'} />
              </View>
              <View style={{ flex: 1, maxWidth: 200 }}>
                <Typography
                  numberOfLines={1}
                  style={{
                    fontSize: 13,
                    fontWeight: '600',
                    color: isDark ? '#e4e4e7' : '#374151',
                  }}
                >
                  {file.name}
                </Typography>
                <Typography
                  numberOfLines={1}
                  style={{
                    fontSize: 11,
                    color: isDark ? '#a1a1aa' : '#6b7280',
                  }}
                >
                  {file.size ? (file.size / 1024).toFixed(1) + ' KB' : 'Unknown Size'}
                </Typography>
              </View>
            </View>
          ))}
        </View>
      )}
    </View>
  );
});

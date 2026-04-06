import React from 'react';
import { View, TouchableOpacity } from 'react-native';
import { useMessageContext } from '../MessageContext';
import { StreamCard } from '../../StreamCard';
import { Colors } from '../../../../../theme/colors';

export const ErrorBlock: React.FC = React.memo(() => {
  const { message, isDark, t, onRegenerate } = useMessageContext();

  if (!message.isError && !message.isLongWait) return null;

  return (
    <View style={{ marginTop: 12 }}>
      {/* ⚠️ Soft Timeout Warning */}
      {message.isLongWait && !message.isError && (
        <TouchableOpacity activeOpacity={0.8}>
           <StreamCard
              content={`**${t?.chat?.softTimeout?.title || 'Taking longer than usual...'}**\n${t?.chat?.softTimeout?.message || 'Most models respond within 30s. Deep thinking models may take longer.'}\n\n[**${t?.chat?.softTimeout?.actionAbortRetry || 'Abort & Retry'}**](action://retry)`}
              index={0}
              showIndex={true}
              indexLabel="?"
              accentColor="#f59e0b" // Amber-500
              markdownStyles={{
                body: { color: isDark ? '#fbbf24' : '#d97706' },
              }}
              onLinkPress={(url) => {
                if (url.includes('action://retry')) {
                  onRegenerate?.();
                  return false;
                }
                return true;
              }}
           />
        </TouchableOpacity>
      )}

      {/* 🚨 Hard Error Card */}
      {message.isError && (
        <TouchableOpacity activeOpacity={0.8} onPress={onRegenerate}>
           <StreamCard
              content={`**${t?.common?.error || 'Error'}**: ${message.errorMessage || 'Unknown error occurred.'}\n\n[**${t?.common?.retry || 'Tap to Retry'}**](action://retry)`}
              index={0}
              showIndex={true}
              indexLabel="!"
              accentColor={Colors.error}
              markdownStyles={{
                body: { color: isDark ? '#fca5a5' : '#ef4444' },
              }}
              onLinkPress={(url) => {
                if (url.includes('action://retry')) {
                  onRegenerate?.();
                  return false;
                }
                return true;
              }}
           />
        </TouchableOpacity>
      )}
    </View>
  );
});

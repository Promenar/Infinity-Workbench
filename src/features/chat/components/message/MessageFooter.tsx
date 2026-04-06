import React from 'react';
import { View } from 'react-native';
import { useMessageContext } from './MessageContext';
import { MessageMeta } from './MessageMeta';

export const MessageFooter: React.FC = React.memo(() => {
  const { message, isDark, isUser, resolvedModelName } = useMessageContext();

  if (isUser) {
    return (
      <View style={{ marginTop: 2, alignItems: 'flex-end', width: '100%' }}>
        <MessageMeta
          timestamp={message.createdAt}
          isDark={isDark}
        />
      </View>
    );
  }

  return (
    <View style={{ marginTop: 4 }}>
      <MessageMeta
          modelName={resolvedModelName}
          timestamp={message.createdAt}
          isDark={isDark}
          loopCount={message.loopCount}
      />
    </View>
  );
});

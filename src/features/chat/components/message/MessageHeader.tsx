import React from 'react';
import { View, Text } from 'react-native';
import { useMessageContext } from './MessageContext';
import { AgentAvatar } from '../../../../components/chat/AgentAvatar';
import { RagOmniIndicator } from '../RagOmniIndicator';
import { Colors } from '../../../../theme/colors';

export const MessageHeader: React.FC = React.memo(() => {
  const {
    message,
    isUser,
    isDark,
    agentId,
    agentName,
    agentAvatar,
    agentColor,
    isGenerating,
    isRagExpanded,
    setIsRagExpanded,
    // Add these to context or pass via props
    userName,
    userAvatar
  } = useMessageContext() as any;

  if (isUser) {
    return (
      <View style={{
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'flex-end',
        marginBottom: 8,
        width: '100%'
      }}>
        <Text style={{
          fontSize: 12,
          fontWeight: '600',
          color: isDark ? '#a1a1aa' : '#71717a',
          marginRight: 8
        }}>
          {userName || 'User'}
        </Text>
        <AgentAvatar
          id="user"
          name={userName || 'User'}
          avatar={userAvatar}
          size={24}
        />
      </View>
    );
  }

  // Assistant Header
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
      <View style={{
        backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#ffffff',
        borderRadius: 9999,
        padding: 2,
        borderWidth: 1,
        borderColor: isDark ? 'rgba(255,255,255,0.08)' : '#f3f4f6',
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.05,
        shadowRadius: 2,
        elevation: 1,
      }}>
        <AgentAvatar
          id={agentId || 'ai'}
          name={agentName || 'AI'}
          avatar={agentAvatar || 'Sparkles'}
          color={agentColor || '#6366f1'}
          size={28}
        />
      </View>

      <View style={{ flex: 1, marginLeft: 12 }}>
        {/* Simplified logic for header indicators - moved to dedicated indicator components if complex */}
        {(!!message.ragReferencesLoading ||
          (Array.isArray(message.ragReferences) && message.ragReferences.length > 0) ||
          (!!message.ragProgress)
        ) && (
          <View style={{ width: '100%', marginBottom: 4 }}>
            <RagOmniIndicator
              messageId={message.id}
              isGenerating={isGenerating}
              referencesCount={message.ragReferences?.length || 0}
              isExpanded={isRagExpanded}
              onToggle={() => setIsRagExpanded?.(!isRagExpanded)}
            />
          </View>
        )}
      </View>
    </View>
  );
});

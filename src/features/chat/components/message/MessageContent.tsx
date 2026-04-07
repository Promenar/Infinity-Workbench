import React from 'react';
import { View } from 'react-native';
import { useMessageContext } from './MessageContext';
import {
  MarkdownBlock,
  AttachmentBlock,
  ToolCallBlock,
  ErrorBlock,
  LoadingDots,
  StreamingFadePulse
} from './blocks';
import { TaskMonitor } from '../TaskMonitor';

export const MessageContent: React.FC = React.memo(() => {
  const { 
    message, 
    isUser, 
    isGenerating, 
    agentColor, 
    isDark,
    sessionId,
    isLastAssistantMessage,
    globalPendingIntervention,
    sessionData
  } = useMessageContext() as any;

  const intermediateContentSteps = React.useMemo(() => {
    if (isUser) return [];
    return (message.executionSteps || []).filter((s: any) => s.type === 'thinking' && (s.content || '').trim().length > 0 && s.id !== 'native-reasoning');
  }, [message.executionSteps, isUser]);

  // Heuristics for waiting/loading state
  const isWaitingForContent = !isUser && isGenerating && !message.content;
  const isStartingToGenerate = !isUser && !message.content && !message.reasoning && isGenerating;

  if (isUser) {
    return (
      <View style={{
        width: '100%',
        backgroundColor: isDark ? 'rgba(39, 39, 42, 0.4)' : 'rgba(244, 244, 245, 0.6)',
        borderRadius: 16,
        borderTopRightRadius: 4,
        padding: 16,
      }}>
         <MarkdownBlock />
         <AttachmentBlock />
      </View>
    );
  }

  return (
    <View style={{ flex: 1 }}>
       {!isUser && message.planningTask && (
          <TaskMonitor
            sessionId={sessionId}
            task={message.planningTask}
            isLatest={isLastAssistantMessage}
            pendingIntervention={isLastAssistantMessage ? (globalPendingIntervention || sessionData?.pendingIntervention) : undefined}
            containerStyle={{ marginLeft: -15, marginRight: -12, marginTop: 2, marginBottom: 4 }}
          />
       )}

       <ToolCallBlock />

       {/* Intermediate Content Cards (Results of thought/inter-step output) */}
       {intermediateContentSteps.map((step: any) => (
         <View key={step.id} style={{ marginBottom: 8 }}>
            <MarkdownBlock overrideContent={step.content} />
         </View>
       ))}

       <View style={{ minHeight: 20, position: 'relative', overflow: 'hidden' }}>
          {isWaitingForContent ? (
             <View style={{ alignItems: 'flex-start', paddingVertical: 8 }}>
                <LoadingDots isDark={isDark} color={agentColor} />
             </View>
          ) : isStartingToGenerate ? (
             <View style={{ paddingVertical: 8 }}>
                <LoadingDots isDark={isDark} />
             </View>
          ) : (
             <>
                <MarkdownBlock />
                <AttachmentBlock />
             </>
          )}

          <StreamingFadePulse
            contentTrigger={!isUser && isGenerating && message.content ? message.content.length : 0}
            isDark={isDark}
          />

          <ErrorBlock />
       </View>
    </View>
  );
});

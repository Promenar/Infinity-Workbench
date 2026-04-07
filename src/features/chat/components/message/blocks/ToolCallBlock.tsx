import React from 'react';
import { View } from 'react-native';
import { useMessageContext } from '../MessageContext';
import { ToolExecutionTimeline } from '../../../../../components/skills/ToolExecutionTimeline';
import { ToolArtifacts } from '../../ToolArtifacts';

export const ToolCallBlock: React.FC = React.memo(() => {
  const { message, isGenerating, sessionId } = useMessageContext();

  const processedSteps = React.useMemo(() => {
    let steps = [...(message.executionSteps || [])];
    
    // 1. Inject native reasoning if exists
    if (message.reasoning) {
      const hasDuplicate = steps.some(s => s.type === 'thinking' && s.content === message.reasoning);
      if (!hasDuplicate) {
        steps.unshift({
          id: 'native-reasoning',
          type: 'thinking',
          content: message.reasoning,
          timestamp: Date.now() - 1000 // Ensure it's the first
        });
      }
    }

    // 2. Filter out intermediate text-only 'thinking' steps that should be cards
    // Usually these are non-empty chunks that aren't the native reasoning
    return steps.filter(s => {
      if (s.type === 'thinking' && s.id !== 'native-reasoning') {
        // If it looks like content (not a tool progress update), hide from timeline
        return false;
      }
      return true;
    });
  }, [message.executionSteps, message.reasoning]);

  const hasExecutionSteps = processedSteps.length > 0;
  const hasToolResults = message.toolResults && message.toolResults.length > 0;

  if (!hasExecutionSteps && !hasToolResults) return null;

  return (
    <View>
      {/* Agentic Loop Timeline */}
      {hasExecutionSteps && (
        <View style={{ marginLeft: -15, marginRight: -12, marginBottom: 4 }}>
          <ToolExecutionTimeline 
            steps={processedSteps} 
            isMessageGenerating={isGenerating} 
            sessionId={sessionId} 
          />
        </View>
      )}

      {/* Tool Produced Artifacts */}
      {hasToolResults && (
        <View style={{ marginBottom: 12 }}>
          <ToolArtifacts artifacts={message.toolResults!} />
        </View>
      )}
    </View>
  );
});

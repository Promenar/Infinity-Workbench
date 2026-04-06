import React from 'react';
import { View } from 'react-native';
import { useMessageContext } from '../MessageContext';
import { ToolExecutionTimeline } from '../../../../../components/skills/ToolExecutionTimeline';
import { ToolArtifacts } from '../../ToolArtifacts';

export const ToolCallBlock: React.FC = React.memo(() => {
  const { message, isGenerating, sessionId } = useMessageContext();

  const hasExecutionSteps = message.executionSteps && message.executionSteps.length > 0;
  const hasToolResults = message.toolResults && message.toolResults.length > 0;

  if (!hasExecutionSteps && !hasToolResults) return null;

  return (
    <View>
      {/* Agentic Loop Timeline */}
      {hasExecutionSteps && (
        <View style={{ marginLeft: -15, marginRight: -12, marginBottom: 4 }}>
          <ToolExecutionTimeline 
            steps={message.executionSteps!} 
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

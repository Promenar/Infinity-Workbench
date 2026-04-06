import React from 'react';
import { Message } from '../../../types/chat';
import { MessageRow } from './message';

/**
 * ChatBubble Component
 * 
 * This is a thin wrapper around the refactored MessageRow component groups.
 * It maintains backward compatibility with the existing chat system while
 * utilizing the new atomic micro-component architecture.
 * 
 * Target Architecture:
 * - MessageRow (Container & Context)
 *   - MessageContextMenu (Action Logic)
 *     - MessageHeader (Avatar & RAG Indicators)
 *     - MessageContent (Content Blocks & Dispatcher)
 *     - MessageFooter (Metadata)
 */

interface ChatBubbleProps {
  message: Message;
  sessionId: string;
  isGenerating?: boolean;
  isLastAssistantMessage?: boolean;
  agentId?: string;
  agentAvatar?: string;
  agentColor?: string;
  agentName?: string;
  modelId?: string;
  onDelete?: () => void;
  onResend?: () => void;
  onRegenerate?: () => void;
  onExtractGraph?: () => void;
  onVectorize?: () => void; // Manually update vector
  onSummarize?: () => void; // Manually trigger summary
  onLayout?: (event: any) => void;
  globalPendingIntervention?: string;
}

export const ChatBubble = React.memo<ChatBubbleProps>((props) => {
  return <MessageRow {...props} />;
});

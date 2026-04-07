import React, { createContext, useContext } from 'react';
import { Message } from '../../../../types/chat';

export interface MessageContextValue {
  // Core Data
  message: Message;
  sessionId: string;
  
  // Theme & UI State
  isDark: boolean;
  colors: any;
  t: any;
  
  // Agent Info
  agentId?: string;
  agentAvatar?: string;
  agentColor?: string;
  agentName?: string;
  
  // Model Info
  modelId?: string;
  resolvedModelName?: string;
  
  // Flags
  isUser: boolean;
  isGenerating?: boolean;
  isLastAssistantMessage?: boolean;
  isProcessing: boolean;
  isArchived: boolean;
  
  // Actions
  onDelete?: () => void;
  onLongPress?: (message: Message) => void;
  onResend?: () => void;
  onRegenerate?: () => void;
  onExtractGraph?: () => void;
  onVectorize?: () => void;
  onSummarize?: () => void;
  onViewImage?: (uri: string | null) => void;
  onSelectText?: () => void;
  onOpenMenu?: (event: any) => void;
  
  // Rag and Local State
  isRagExpanded?: boolean;
  setIsRagExpanded?: (expanded: boolean) => void;
  processingState?: any;
  processingHistory?: any;
  sessionData?: any;
  userName?: string;
  userAvatar?: string;
  globalPendingIntervention?: string;
}

const MessageContext = createContext<MessageContextValue | undefined>(undefined);

export const MessageProvider: React.FC<{
  value: MessageContextValue;
  children: React.ReactNode;
}> = ({ value, children }) => {
  return <MessageContext.Provider value={value}>{children}</MessageContext.Provider>;
};

export const useMessageContext = () => {
  const context = useContext(MessageContext);
  if (context === undefined) {
    throw new Error('useMessageContext must be used within a MessageProvider');
  }
  return context;
};

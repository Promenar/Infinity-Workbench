import React, { useState, useMemo, useRef, useCallback } from 'react';
import { View } from 'react-native';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';
import { useTheme } from '../../../../theme/ThemeProvider';
import { useI18n } from '../../../../lib/i18n';
import { useSettingsStore } from '../../../../store/settings-store';
import { useRagStore } from '../../../../store/rag-store';
import { useChatStore } from '../../../../store/chat-store';
import { MessageProvider, MessageContextValue } from './MessageContext';
import { MessageHeader } from './MessageHeader';
import { MessageContent } from './MessageContent';
import { MessageFooter } from './MessageFooter';
import { MessageContextMenu } from './MessageContextMenu';
import { SelectTextModal, ImageViewerModal } from './modals';
import { getMessageStyles } from './styles/message-styles';
import { resolveModelIdToName } from '../../../../lib/llm/model-utils';
import { Message } from '../../../../types/chat';

export interface MessageRowProps {
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
  onVectorize?: () => void;
  onSummarize?: () => void;
  onLayout?: (event: any) => void;
  globalPendingIntervention?: string;
  modelName?: string;
}

export const MessageRow: React.FC<MessageRowProps> = React.memo((props) => {
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const { userAvatar, userName } = useSettingsStore();
  const { processingState, processingHistory } = useRagStore();
  const sessionData = useChatStore(
    useCallback((state) => state.sessions.find((s) => s.id === props.sessionId), [props.sessionId]),
  );

  // Local States for UI interaction
  const [isRagExpanded, setIsRagExpanded] = useState(false);
  const [isSelectModalVisible, setIsSelectModalVisible] = useState(false);
  const [viewImageUri, setViewImageUri] = useState<string | null>(null);
  const bubbleRef = useRef<View>(null);

  const isUser = props.message.role === 'user';
  const isRecent = useMemo(() => Date.now() - props.message.createdAt < 1000, [props.message.createdAt]);

  const resolvedModelName = useMemo(() => {
    if (props.modelName) return props.modelName;
    const targetId = props.message.modelId || props.modelId;
    return targetId ? resolveModelIdToName(targetId) : undefined;
  }, [props.message.modelId, props.modelId, props.modelName]);

  // Archive & Processing heuristics
  const isProcessing = useMemo(() => {
    return props.message.vectorizationStatus === 'processing' ||
      (!isUser && !props.isGenerating && !props.message.isArchived && !props.message.vectorizationStatus && Date.now() - props.message.createdAt < 60000);
  }, [props.message, isUser, props.isGenerating]);

  // Construct context value
  const contextValue: MessageContextValue = {
    ...props,
    isUser,
    isDark,
    colors,
    t,
    userName,
    userAvatar,
    resolvedModelName,
    isProcessing,
    isArchived: props.message.isArchived || false,
    isRagExpanded,
    setIsRagExpanded,
    onSelectText: () => setIsSelectModalVisible(true),
    onViewImage: (uri: string | null) => setViewImageUri(uri),
    // Store states needed by sub-components
    processingState,
    processingHistory,
    sessionData,
  } as any;

  const styles = useMemo(() => getMessageStyles(isDark, colors), [isDark, colors]);

  if (props.message.role === 'tool') return null;

  return (
    <MessageProvider value={contextValue}>
      <Animated.View
        entering={isRecent ? FadeIn.duration(300) : undefined}
        exiting={FadeOut.duration(300)}
        style={isUser ? styles.userRowContainer : styles.rowContainer}
        onLayout={props.onLayout}
      >
        <MessageContextMenu>
          <View ref={bubbleRef} collapsable={false}>
            <MessageHeader />
            <MessageContent />
            <MessageFooter />
          </View>
        </MessageContextMenu>

        <SelectTextModal
          isVisible={isSelectModalVisible}
          content={props.message.content}
          onClose={() => setIsSelectModalVisible(false)}
          isDark={isDark}
          t={t}
          colors={colors}
        />

        <ImageViewerModal
          visible={!!viewImageUri}
          uri={viewImageUri || ''}
          onClose={() => setViewImageUri(null)}
        />
      </Animated.View>
    </MessageProvider>
  );
});

import { useState, useRef, useEffect } from 'react';
import * as Haptics from '../../../../../lib/haptics';
import { useChatStore, ChatState } from '../../../../../store/chat-store';
import { ChatAttachment } from '../../../../../types/chat';

interface UseChatInputStateProps {
  sessionId: string;
  onSendMessage: (text: string, options?: any) => void;
  clearAttachments: () => void;
  webSearchEnabled: boolean;
  reasoningEnabled: boolean;
  selectedImages: string[];
  selectedFiles: ChatAttachment[];
  editingMessageId?: string;
  initialEditText?: string;
  onStop?: () => void;
}

export const useChatInputState = ({
  sessionId,
  onSendMessage,
  clearAttachments,
  webSearchEnabled,
  reasoningEnabled,
  selectedImages,
  selectedFiles,
  editingMessageId,
  initialEditText,
  onStop,
}: UseChatInputStateProps) => {
  const [text, setText] = useState('');
  const isSendingRef = useRef(false);
  const draftTimerRef = useRef<NodeJS.Timeout | null>(null);

  const session = useChatStore((state: ChatState) => state.getSession(sessionId));
  const updateSessionDraft = useChatStore((state: ChatState) => state.updateSessionDraft);

  // 1. Initial Load Draft
  useEffect(() => {
    if (session?.draft && !editingMessageId) {
      setText(session.draft);
    }
  }, [sessionId]);

  // 2. Initial Edit Mode Text
  useEffect(() => {
    if (editingMessageId && initialEditText !== undefined) {
      setText(initialEditText);
    }
  }, [editingMessageId, initialEditText]);

  // 3. Draft Persistence
  useEffect(() => {
    if (draftTimerRef.current) {
      clearTimeout(draftTimerRef.current);
      draftTimerRef.current = null;
    }

    if (isSendingRef.current) {
      isSendingRef.current = false;
      return;
    }

    draftTimerRef.current = setTimeout(() => {
      if (session && text !== (session.draft || '')) {
        updateSessionDraft(sessionId, text);
      }
      draftTimerRef.current = null;
    }, 500);

    return () => {
      if (draftTimerRef.current) clearTimeout(draftTimerRef.current);
    };
  }, [text, sessionId]);

  // 4. Send Handler
  const handleSend = (loading: boolean, disabled: boolean) => {
    if (loading && onStop) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        onStop();
      }, 10);
      return;
    }

    if ((!text.trim() && selectedImages.length === 0 && selectedFiles.length === 0) || disabled || loading) return;

    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);

      if (draftTimerRef.current) {
        clearTimeout(draftTimerRef.current);
        draftTimerRef.current = null;
      }

      isSendingRef.current = true;

      onSendMessage(text, {
        webSearch: webSearchEnabled,
        reasoning: reasoningEnabled,
        images: selectedImages.length > 0 ? selectedImages : undefined,
        files: selectedFiles.length > 0 ? selectedFiles : undefined,
      });

      setText('');
      updateSessionDraft(sessionId, '');
      clearAttachments();
    }, 10);
  };

  return {
    text,
    setText,
    handleSend,
    isSendingRef,
  };
};

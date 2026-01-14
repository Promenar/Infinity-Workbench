import { useCallback } from 'react';
import { useChatStore } from '../../../store/chat-store';

export function useChat(sessionId: string) {
  const { generateMessage, abortGeneration } = useChatStore();

  // Reactive session state
  const session = useChatStore(
    useCallback((state) => state.sessions.find((s) => s.id === sessionId), [sessionId]),
  );

  // Reactive loading state based on global store
  const isGenerating = useChatStore(
    useCallback((state) => state.currentGeneratingSessionId === sessionId, [sessionId]),
  );

  const messages = session?.messages || [];

  const stop = useCallback(() => {
    if (isGenerating) {
      abortGeneration(sessionId);
    }
  }, [isGenerating, sessionId, abortGeneration]);

  const sendMessage = useCallback(
    async (
      content: string,
      options?: { webSearch?: boolean; reasoning?: boolean; images?: string[] },
    ) => {
      if (
        (!content.trim() && (!options?.images || options.images.length === 0)) ||
        !sessionId ||
        !session
      )
        return;

      // Merge session-level options (reasoning, webSearch) with call-time overrides
      const mergedOptions = {
        webSearch: options?.webSearch ?? session.options?.webSearch,
        reasoning: options?.reasoning ?? session.options?.reasoning,
        images: options?.images,
        ragOptions: session.ragOptions,
      };

      // Steerable Loop Intervention
      if (session.loopStatus === 'running' || session.loopStatus === 'waiting_for_approval') {
        useChatStore.getState().setPendingIntervention(sessionId, content);
        // If waiting, resume immediately with rejection of current tools (user wants to intervene instead)
        if (session.loopStatus === 'waiting_for_approval') {
          await useChatStore.getState().resumeGeneration(sessionId, false, content);
        }
        return;
      }

      await generateMessage(sessionId, content, mergedOptions);
    },
    [sessionId, session, generateMessage],
  );

  return {
    messages,
    loading: isGenerating,
    sendMessage,
    stop,
    sessionStats: session?.stats,
  };
}

import { useCallback, useEffect } from 'react';
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

  // Auto-load messages on mount if empty
  const hasMessages = session?.messages && session.messages.length > 0;
  const loadSessionMessages = useChatStore(state => state.loadSessionMessages);

  useEffect(() => {
    // Only load if session exists, no messages, and we haven't reached end (or undefined meaning not loaded)
    if (sessionId && (!session || (!hasMessages && session.hasMore !== false))) {
      loadSessionMessages(sessionId, 5);
    }
  }, [sessionId, hasMessages, session?.hasMore, session, loadSessionMessages]);

  // Initial Load Effect
  // We use a flag to ensure we don't spam load
  // Actually, if we use SWR or query it handles this, but here we do manual.
  if (session && !hasMessages && session.hasMore !== false) {
    // This is bad practice in render. Use useEffect.
  }

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

  // Pagination Load More
  const loadMore = useCallback(async () => {
    if (!session || !session.messages.length) return;
    // Prevent loading if no more history
    if (session.hasMore === false) return;

    const oldestMessage = session.messages[0]; // Assuming messages are chronological (ASC)
    // Actually, store stores them ASC.
    // So messages[0] is the oldest.
    await useChatStore.getState().loadSessionMessages(sessionId, 5, oldestMessage.createdAt);
  }, [sessionId, session]);

  // Initial Load (if empty)
  // This ensures we load messages when entering the chat
  // if they were not loaded (since loadSessions is now metadata-only)
  // Or if we want to ensure freshness.
  // We use a ref or effect to trigger this once.
  // Actually, we can just check if messages is empty array AND hasMore is undefined (meaning never loaded?)
  // Or just always try to load if empty.
  const hasLoaded = session?.messages && session.messages.length > 0;

  if (session && !hasLoaded && session.hasMore !== false) {
    // Trigger load on next tick or effect?
    // Better to do in useEffect in component, or here?
    // Doing it here might cause loop if not careful.
    // Let's expose a method to trigger initial load or do it in useEffect.
  }

  return {
    messages,
    loading: isGenerating,
    sendMessage,
    stop,
    sessionStats: session?.stats,
    loadMore,
    hasMore: session?.hasMore ?? false,
  };
}

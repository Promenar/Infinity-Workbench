import { useState, useEffect } from 'react';
import { db } from '../../../../../lib/db';
import { InteractionManager } from 'react-native';
import { Message } from '../../../../../types/chat';

export const useMessageArchive = (
  message: Message, 
  sessionId: string, 
  isGenerating: boolean, 
  isProcessing: boolean
) => {
  const [isArchived, setIsArchived] = useState(message.isArchived || false);

  useEffect(() => {
    // 1. Force false if generating or processing or error
    if (isGenerating || isProcessing || message.vectorizationStatus === 'error' || message.vectorizationStatus === 'processing') {
      setIsArchived(false);
      return;
    }

    // 2. Trust explicit status
    if (message.vectorizationStatus === 'success' || message.isArchived) {
      setIsArchived(true);
      return;
    }

    // 3. DB Check (Fallback)
    if (Date.now() - message.createdAt < 2000) return;

    const interactionHandle = InteractionManager.runAfterInteractions(() => {
      const checkArchiveStatus = async () => {
        if (message.role === 'system' || message.role === 'tool') return;
        try {
          const result = await db.execute(
            'SELECT 1 FROM vectors WHERE (start_message_id = ? OR end_message_id = ?) AND session_id = ? LIMIT 1',
            [message.id, message.id, sessionId],
          );
          
          const rows = result.rows as any;
          const hasRecord = rows && (
            (rows._array && rows._array.length > 0) || 
            (rows.length > 0) || 
            (rows[0])
          );
          
          if (hasRecord) setIsArchived(true);
        } catch (e) {
          console.error('[useMessageArchive] Failed to check status:', e);
        }
      };
      checkArchiveStatus();
    });
    
    return () => interactionHandle.cancel();
  }, [message.id, sessionId, message.isArchived, message.vectorizationStatus, isGenerating, isProcessing]);

  return isArchived;
};

import { useChatStore } from '../../store/chat-store';
import type { CommandWebSocketServer } from './CommandWebSocketServer';
import { Message, Session } from '../../types/chat';

class StoreSyncService {
    private unsub: (() => void) | null = null;
    private lastState: any = null;
    private lastMessageLengths: Record<string, number> = {};
    private server: CommandWebSocketServer | null = null;

    public registerServer(server: CommandWebSocketServer) {
        this.server = server;
    }

    start() {
        console.log('[StoreSync] Starting service...');

        // Initial state
        this.lastState = useChatStore.getState();

        this.unsub = useChatStore.subscribe((state, prevState) => {
            this.handleStateChange(state, prevState);
        });
    }

    stop() {
        console.log('[StoreSync] Stopping service...');
        if (this.unsub) {
            this.unsub();
            this.unsub = null;
        }
    }

    private handleStateChange(state: any, prevState: any) {
        // 1. Handle Session List Changes (Add/Delete/Update Title)
        if (state.sessions !== prevState.sessions) {
            this.detectSessionChanges(state.sessions, prevState.sessions);
        }

        // 2. Handle Streaming (Generation)
        const generatingId = state.currentGeneratingSessionId;
        if (generatingId) {
            this.handleStreaming(state, generatingId);
        } else if (prevState.currentGeneratingSessionId) {
            // Just finished generating
            this.handleGenerationFinished(prevState.currentGeneratingSessionId);
        }
    }

    private detectSessionChanges(currentSessions: Session[], prevSessions: Session[]) {
        // Simple check: if length changed or specific fields changed.
        // For efficiency, we might just broadcast "SESSIONS_DIRTY" and let client fetch?
        // Or broadcast the specific update.
        // For list view, we usually care about: id, title, updatedAt, lastMessage, agentId.

        // If length different -> List changed
        if (currentSessions.length !== prevSessions.length) {
            this.server?.broadcast({ type: 'SESSION_LIST_UPDATED' });
            return;
        }

        // detect updates to top sessions (most likely to change)
        // This is a naive check; for now, let's just broadcast dirty signal to safe bandwidth 
        // vs sending diffs. Client calls `CMD_GET_SESSIONS` on signal.
        // But for "lastMessage" update, it happens frequent during gen. We don't want to spam SESSION_LIST_UPDATED.

        // We will separate "Streaming Update" from "Session List Update".
        // Session List Update should be for Title, New Session, Deleted Session.

        // Let's assume List Update if the ID list is different or Titles are different.
        const currentIds = currentSessions.map(s => s.id).join(',');
        const prevIds = prevSessions.map(s => s.id).join(',');

        if (currentIds !== prevIds) {
            this.server?.broadcast({ type: 'SESSION_LIST_UPDATED' });
        }
    }

    private handleStreaming(state: any, sessionId: string) {
        const session = state.sessions.find((s: Session) => s.id === sessionId);
        if (!session) return;

        const lastMsg = session.messages[session.messages.length - 1];
        if (!lastMsg || lastMsg.role !== 'assistant') return;

        // Check if content changed
        const prevLen = this.lastMessageLengths[lastMsg.id] || 0;
        if (lastMsg.content.length !== prevLen) {
            // It changed. Broadcast chunk or full content.
            // Sending full content is idempotent and easier for V1.
            // Sending delta saves bandwidth.
            // Let's send FULL content for the *current message* to ensure consistency, 
            // as it's local LAN.

            this.server?.broadcast({
                type: 'MSG_STREAM_UPDATE',
                payload: {
                    sessionId,
                    messageId: lastMsg.id,
                    content: lastMsg.content,
                    isDone: false
                }
            });

            this.lastMessageLengths[lastMsg.id] = lastMsg.content.length;
        }
    }

    private handleGenerationFinished(sessionId: string) {
        // Notify that generation is done
        this.server?.broadcast({
            type: 'MSG_STREAM_COMPLETE',
            payload: {
                sessionId
            }
        });

        // Also trigger session list refresh because "lastMessage" and "updatedAt" are final
        this.server?.broadcast({ type: 'SESSION_LIST_UPDATED' });

        // Clear cache
        this.lastMessageLengths = {};
    }
}

export const storeSyncService = new StoreSyncService();

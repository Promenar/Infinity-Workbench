import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Session, SessionId, AgentId, Message } from '../types/chat';

interface ChatState {
    sessions: Session[];
    addSession: (session: Session) => void;
    updateSession: (id: SessionId, updates: Partial<Session>) => void;
    deleteSession: (id: SessionId) => void;
    addMessage: (sessionId: SessionId, message: Message) => void;
    getSessionsByAgent: (agentId: AgentId) => Session[];
    getSession: (id: SessionId) => Session | undefined;
}

export const useChatStore = create<ChatState>()(
    persist(
        (set, get) => ({
            sessions: [], // Initially empty, will migrate mock data if needed
            addSession: (session) => set((state) => ({ sessions: [session, ...state.sessions] })),
            updateSession: (id, updates) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, ...updates } : s)
            })),
            deleteSession: (id) => set((state) => ({
                sessions: state.sessions.filter((s) => s.id !== id)
            })),
            addMessage: (sessionId, message) => set((state) => ({
                sessions: state.sessions.map((s) => {
                    if (s.id === sessionId) {
                        return {
                            ...s,
                            messages: [...s.messages, message],
                            lastMessage: message.content,
                            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                        };
                    }
                    return s;
                })
            })),
            getSessionsByAgent: (agentId) => get().sessions.filter((s) => s.agentId === agentId),
            getSession: (id) => get().sessions.find((s) => s.id === id),
        }),
        {
            name: 'chat-storage',
            storage: createJSONStorage(() => AsyncStorage),
        }
    )
);

import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { SuperAssistantPreferences, DEFAULT_SPA_PREFERENCES } from '../types/super-assistant';
import { db } from '../lib/db';

interface SPAState {
  preferences: SuperAssistantPreferences;

  // Actions
  updateFABConfig: (config: Partial<SuperAssistantPreferences['fab']>) => void;
  updateRAGStats: () => Promise<void>;
  resetToDefaults: () => void;
}

export const useSPAStore = create<SPAState>()(
  persist(
    (set, get) => ({
      preferences: DEFAULT_SPA_PREFERENCES,

      updateFABConfig: (config) => {
        set((state) => ({
          preferences: {
            ...state.preferences,
            fab: {
              ...state.preferences.fab,
              ...config,
            },
          },
        }));
      },

      updateRAGStats: async () => {
        try {
          // 统计文档数
          const docResult = await db.execute('SELECT COUNT(*) as count FROM documents');
          const totalDocuments = (docResult.rows?.[0]?.count as number) || 0;

          // 统计会话数（排除超级助手自己）
          const sessionResult = await db.execute(
            "SELECT COUNT(*) as count FROM sessions WHERE id != 'super_assistant'",
          );
          const totalSessions = (sessionResult.rows?.[0]?.count as number) || 0;

          // 统计向量数
          const vectorResult = await db.execute('SELECT COUNT(*) as count FROM vectors');
          const totalVectors = (vectorResult.rows?.[0]?.count as number) || 0;

          set((state) => ({
            preferences: {
              ...state.preferences,
              ragStats: {
                totalDocuments,
                totalSessions,
                totalVectors,
                lastUpdated: Date.now(),
              },
            },
          }));
        } catch (e) {
          console.error('[SPAStore] Failed to update RAG stats:', e);
        }
      },

      resetToDefaults: () => {
        set({ preferences: DEFAULT_SPA_PREFERENCES });
      },
    }),
    {
      name: 'spa-storage',
      storage: createJSONStorage(() => AsyncStorage),
    },
  ),
);

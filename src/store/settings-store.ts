import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';

export type Language = 'en' | 'zh';

interface SettingsState {
    language: Language;
    setLanguage: (lang: Language) => void;
    _hasHydrated: boolean;
    setHasHydrated: (state: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
    persist(
        (set) => ({
            language: 'zh',
            setLanguage: (lang) => set({ language: lang }),
            _hasHydrated: false,
            setHasHydrated: (state) => set({ _hasHydrated: state }),
        }),
        {
            name: 'settings-storage',
            storage: createJSONStorage(() => AsyncStorage),
            partialize: (state) => ({ language: state.language }), // 仅持久化语言，排除水合状态
            onRehydrateStorage: () => (state) => {
                state?.setHasHydrated(true);
            },
        }
    )
);

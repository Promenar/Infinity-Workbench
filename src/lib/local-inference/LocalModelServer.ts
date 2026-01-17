import { initLlama, LlamaContext, CompletionParams, TokenData, RNLlamaOAICompatibleMessage, RerankResult } from 'llama.rn';
import { AppState, AppStateStatus } from 'react-native';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { produce } from 'immer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system/legacy';
import { emitToast } from '../utils/toast-emitter';

interface SlotState {
    context: LlamaContext | null;
    modelPath: string | null;
    isLoaded: boolean;
    loadProgress: number;
}

interface ServerState {
    isModelLoaded: boolean; // Map to main.isLoaded for backward compatibility
    isGenerating: boolean;
    error: string | null;
    loadProgress: number;   // Map to main.loadProgress

    // Slots
    main: SlotState;
    embedding: SlotState;
    rerank: SlotState;

    // Actions
    loadModel: (path: string, slot?: 'main' | 'embedding' | 'rerank') => Promise<void>;
    unloadModel: (slot?: 'main' | 'embedding' | 'rerank') => Promise<void>;
    completion: (params: CompletionParams, callback?: (data: TokenData) => void) => Promise<any>;
    stopCompletion: () => Promise<void>;
    generateEmbedding: (text: string) => Promise<number[]>;
    performRerank: (query: string, documents: string[], topK?: number) => Promise<RerankResult[]>;
}

interface PersistedState {
    lastLoadedModel: string | null;     // Main
    lastEmbeddingModel: string | null;  // New
    lastRerankModel: string | null;     // New
    autoLoadEnabled: boolean;
    _hasHydrated: boolean;
}

type StoreType = ServerState & PersistedState & {
    setAutoLoad: (enabled: boolean) => void;
    initialize: () => Promise<boolean>;
    setHasHydrated: (state: boolean) => void;
};

export const useLocalModelStore = create<StoreType>()(
    persist(
        (set, get) => {
            let context: LlamaContext | null = null;
            // eslint-disable-next-line @typescript-eslint/no-unused-vars
            let appStateSubscription: any = null;

            // Handle AppState changes
            const handleAppStateChange = (nextAppState: AppStateStatus) => {
                if (nextAppState === 'background') {
                    // Check if we need to release resources
                    // For now, keep loaded
                }
            };

            appStateSubscription = AppState.addEventListener('change', handleAppStateChange);

            // Mutex implementation for shared contexts
            const mutexMap = new WeakMap<LlamaContext, Promise<any>>();
            const runWithLock = async (ctx: LlamaContext, operation: () => Promise<any>) => {
                const prev = mutexMap.get(ctx) || Promise.resolve();
                // Sequence correctly even on previous failure
                const next = prev.then(() => { }, () => { }).then(operation);
                mutexMap.set(ctx, next);
                return next;
            };


            return {
                isModelLoaded: false,
                isGenerating: false,
                error: null,
                loadProgress: 0,
                lastLoadedModel: null,
                lastEmbeddingModel: null,
                lastRerankModel: null,
                autoLoadEnabled: true,
                _hasHydrated: false,

                main: { context: null, modelPath: null, isLoaded: false, loadProgress: 0 },
                embedding: { context: null, modelPath: null, isLoaded: false, loadProgress: 0 },
                rerank: { context: null, modelPath: null, isLoaded: false, loadProgress: 0 },

                setHasHydrated: (val) => set({ _hasHydrated: val }),
                setAutoLoad: (enabled) => set({ autoLoadEnabled: enabled }),

                initialize: async (): Promise<boolean> => {
                    const state = get();
                    if (!state.autoLoadEnabled) return false;
                    let loadedCount = 0;

                    // 1. Auto-load Main Model (Chat)
                    if (state.lastLoadedModel) {
                        const fileInfo = await FileSystem.getInfoAsync(state.lastLoadedModel);
                        if (fileInfo.exists) {
                            console.log('[LocalServer] Auto-loading Main model:', state.lastLoadedModel);
                            await get().loadModel(state.lastLoadedModel, 'main');
                            loadedCount++;
                        } else {
                            console.warn('[LocalServer] Main model file not found:', state.lastLoadedModel);
                        }
                    }

                    // 2. Auto-load Embedding Model
                    if (state.lastEmbeddingModel) {
                        const fileInfo = await FileSystem.getInfoAsync(state.lastEmbeddingModel);
                        if (fileInfo.exists) {
                            console.log('[LocalServer] Auto-loading Embedding model:', state.lastEmbeddingModel);
                            await get().loadModel(state.lastEmbeddingModel, 'embedding');
                            loadedCount++;
                        } else {
                            console.warn('[LocalServer] Embedding model file not found:', state.lastEmbeddingModel);
                        }
                    }

                    // 3. Auto-load Rerank Model
                    if (state.lastRerankModel) {
                        const fileInfo = await FileSystem.getInfoAsync(state.lastRerankModel);
                        if (fileInfo.exists) {
                            console.log('[LocalServer] Auto-loading Rerank model:', state.lastRerankModel);
                            await get().loadModel(state.lastRerankModel, 'rerank');
                            loadedCount++;
                        } else {
                            console.warn('[LocalServer] Rerank model file not found:', state.lastRerankModel);
                        }
                    }

                    console.log(`[LocalServer] Auto-load complete. Loaded ${loadedCount} model(s).`);
                    return loadedCount > 0;
                },

                loadModel: async (path: string, slot: 'main' | 'embedding' | 'rerank' = 'main') => {
                    try {
                        const state = get();
                        const currentSlot = state[slot];

                        if (currentSlot.context) {
                            console.log(`[LocalServer] Releasing previous context for slot ${slot}`);
                            await runWithLock(currentSlot.context, () => currentSlot.context!.release());
                        }

                        set(produce((s: StoreType) => {
                            s.error = null;
                            s[slot].loadProgress = 0;
                            s[slot].isLoaded = false;
                            s[slot].modelPath = path; // Show pending path in UI
                            if (slot === 'main') s.loadProgress = 0;
                        }));

                        console.log(`[LocalServer] Loading model into ${slot}:`, path);

                        const ctx = await initLlama({
                            model: path,
                            use_mlock: true,
                            n_ctx: slot === 'main' ? 2048 : 2048, // Increase context for Rerank/Embedding as well
                            n_gpu_layers: 0,
                            embedding: true,
                        }, (progress) => {
                            set(produce((s: StoreType) => {
                                s[slot].loadProgress = progress;
                                if (slot === 'main') s.loadProgress = progress;
                            }));
                        });

                        set(produce((s: StoreType) => {
                            s[slot] = {
                                context: ctx,
                                modelPath: path,
                                isLoaded: true,
                                loadProgress: 100
                            };
                            if (slot === 'main') {
                                s.isModelLoaded = true;
                                s.lastLoadedModel = path;
                                s.loadProgress = 100;
                            } else if (slot === 'embedding') {
                                s.lastEmbeddingModel = path;
                            } else if (slot === 'rerank') {
                                s.lastRerankModel = path;
                            }
                        }));
                        console.log(`[LocalServer] Model loaded into ${slot} successfully`);

                    } catch (err: any) {
                        console.error(`[LocalServer] Failed to load model into ${slot}:`, err);
                        set({ error: err.message || 'Failed to load model' });
                        throw err;
                    }
                },

                unloadModel: async (slot: 'main' | 'embedding' | 'rerank' = 'main') => {
                    const currentSlot = get()[slot];
                    if (currentSlot.context) {
                        await currentSlot.context.release();
                    }
                    set(produce((s: StoreType) => {
                        s[slot] = { context: null, modelPath: null, isLoaded: false, loadProgress: 0 };
                        if (slot === 'main') s.isModelLoaded = false;
                    }));
                },

                completion: async (params: CompletionParams, callback?: (data: TokenData) => void) => {
                    const { main } = get();
                    if (!main.context) throw new Error('No local model loaded in main slot');

                    set({ isGenerating: true });
                    try {
                        return await runWithLock(main.context, () => main.context!.completion(params, callback));
                    } finally {
                        set({ isGenerating: false });
                    }
                },

                stopCompletion: async () => {
                    const { main } = get();
                    if (main.context) await main.context.stopCompletion();
                    set({ isGenerating: false });
                },

                generateEmbedding: async (text: string) => {
                    const { embedding, main } = get();
                    const ctx = embedding.context || main.context;
                    if (!ctx) throw new Error('No local model loaded');
                    console.log(`[LocalServer] generateEmbedding: Starting for text length ${text.length}`);
                    const result = await runWithLock(ctx, async () => {
                        console.log('[LocalServer] generateEmbedding: Acquired lock, calling native embedding...');
                        return ctx!.embedding(text);
                    });
                    console.log(`[LocalServer] generateEmbedding: Complete, vector length ${result?.embedding?.length}`);
                    return result.embedding;
                },

                performRerank: async (query: string, documents: string[], topK?: number) => {
                    const { rerank, main } = get();
                    const ctx = rerank.context || main.context;
                    if (!ctx) throw new Error('No local model loaded');

                    console.log(`[LocalServer] Reranking using ${rerank.context ? 'Rerank' : 'Main'} slot`);
                    const results = await runWithLock(ctx, () => ctx!.rerank(query, documents, { normalize: 1 }));


                    const firstItem = results[0] as any;
                    console.log(`[LocalServer] Rerank returned ${results.length} items. First score: ${firstItem?.score ?? firstItem?.relevance_score ?? 'N/A'}`);

                    if (results.length === 0) {
                        console.warn('[LocalServer] Rerank returned zero results. Model may not support Cross-Encoding.');
                    }
                    return results;
                }
            };
        },
        {
            name: 'local-model-storage',
            storage: createJSONStorage(() => AsyncStorage),
            partialize: (state) => ({
                lastLoadedModel: state.lastLoadedModel,
                lastEmbeddingModel: state.lastEmbeddingModel,
                lastRerankModel: state.lastRerankModel,
                autoLoadEnabled: state.autoLoadEnabled
            }),
            onRehydrateStorage: () => (state) => {
                if (state) {
                    state.setHasHydrated(true);
                    if (state.autoLoadEnabled && (state.lastLoadedModel || state.lastEmbeddingModel || state.lastRerankModel)) {
                        console.log('[LocalServer] Hydration complete, scheduling auto-load in 3s...');
                        // Delay auto-load by 3 seconds to prevent startup crashes
                        setTimeout(() => {
                            emitToast('正在自动加载本地模型...', 'info');
                            state.initialize()
                                .then((anyLoaded) => {
                                    if (anyLoaded) {
                                        emitToast('本地模型已就绪', 'success');
                                    } else {
                                        console.warn('[LocalServer] No models were loaded (files may not exist).');
                                    }
                                })
                                .catch((e: any) => {
                                    console.error('[LocalServer] Auto-load failed:', e);
                                    emitToast('本地模型加载失败', 'error');
                                });
                        }, 3000);
                    }
                }
            },
        }
    )
);

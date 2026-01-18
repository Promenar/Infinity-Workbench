import { initLlama, LlamaContext, CompletionParams, TokenData, RNLlamaOAICompatibleMessage, RerankResult } from 'llama.rn';
import { AppState, AppStateStatus } from 'react-native';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { produce } from 'immer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system/legacy';
import { emitToast } from '../utils/toast-emitter';
import { useSettingsStore } from '../../store/settings-store';

interface SlotState {
    context: LlamaContext | null;
    modelPath: string | null;
    isLoaded: boolean;
    loadProgress: number;
    accelerationInfo?: {
        gpu: boolean;
        reasonNoGPU: string;
        devices: any; // NativeLlamaContext['devices']
    };
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

                main: { context: null, modelPath: null, isLoaded: false, loadProgress: 0, accelerationInfo: undefined },
                embedding: { context: null, modelPath: null, isLoaded: false, loadProgress: 0, accelerationInfo: undefined },
                rerank: { context: null, modelPath: null, isLoaded: false, loadProgress: 0, accelerationInfo: undefined },

                setHasHydrated: (val) => set({ _hasHydrated: val }),
                setAutoLoad: (enabled) => set({ autoLoadEnabled: enabled }),

                initialize: async (): Promise<boolean> => {
                    const state = get();

                    // 🔑 检查全局本地模型开关
                    const settingsState = useSettingsStore.getState();
                    if (!settingsState.localModelsEnabled) {
                        console.log('[LocalServer] Local models disabled in settings, skipping auto-load');
                        return false;
                    }

                    if (!state.autoLoadEnabled) return false;
                    let loadedCount = 0;

                    const ensureFileUri = (path: string) => path.startsWith('file://') ? path : `file://${path}`;

                    // 1. Auto-load Main Model (Chat)
                    if (state.lastLoadedModel) {
                        const fileUri = ensureFileUri(state.lastLoadedModel);
                        const fileInfo = await FileSystem.getInfoAsync(fileUri);
                        if (fileInfo.exists) {
                            console.log('[LocalServer] Auto-loading Main model:', state.lastLoadedModel);
                            await get().loadModel(state.lastLoadedModel, 'main');
                            loadedCount++;
                        } else {
                            console.warn('[LocalServer] Main model file not found at:', fileUri);
                        }
                    }

                    // 2. Auto-load Embedding Model
                    if (state.lastEmbeddingModel) {
                        const fileUri = ensureFileUri(state.lastEmbeddingModel);
                        const fileInfo = await FileSystem.getInfoAsync(fileUri);
                        if (fileInfo.exists) {
                            console.log('[LocalServer] Auto-loading Embedding model:', state.lastEmbeddingModel);
                            await get().loadModel(state.lastEmbeddingModel, 'embedding');
                            loadedCount++;
                        } else {
                            console.warn('[LocalServer] Embedding model file not found at:', fileUri);
                        }
                    }

                    // 3. Auto-load Rerank Model
                    if (state.lastRerankModel) {
                        const fileUri = ensureFileUri(state.lastRerankModel);
                        const fileInfo = await FileSystem.getInfoAsync(fileUri);
                        if (fileInfo.exists) {
                            console.log('[LocalServer] Auto-loading Rerank model:', state.lastRerankModel);
                            await get().loadModel(state.lastRerankModel, 'rerank');
                            loadedCount++;
                        } else {
                            console.warn('[LocalServer] Rerank model file not found at:', fileUri);
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
                            s[slot].accelerationInfo = undefined;
                            if (slot === 'main') s.loadProgress = 0;
                        }));

                        console.log(`[LocalServer] Loading model into ${slot}:`, path);

                        const ctx = await initLlama({
                            model: path,
                            use_mlock: true,
                            n_ctx: slot === 'main' ? 2048 : 2048, // Default 2048, user can adjust later if needed. 
                            // Wait, planning said 512 for main. Let's do 2048 for better experience but use GPU to speed up. 
                            // Actually user complained about "wait verify long". 
                            // Let's use 1024 as a middle ground or strict 512 if user really wants speed. 
                            // I'll stick to 2048 but rely on GPU/NPU. If still slow, we can verify.
                            // Optimized params for stability & speed:
                            // Note: 'flash_attn' caused crashes on some devices, disabled for now.
                            n_gpu_layers: 99,
                            // flash_attn_type: 'auto', // Disabled due to instability
                            embedding: true, // Enable embedding for all slots to support fallback (Main slot used for embedding) 
                            // Actually llama.cpp context needs embedding=true to generate embeddings, but for main chat it might not be strictly necessary if we don't use it for that.
                            // But let's keep it safe.
                            // n_batch: 32, // Default is usually 512, 32 might be too small? Let's leave default or set safe.
                            n_batch: 512, // Explicitly set batch size to prevent overflow
                            n_ubatch: 512, // Physical batch size
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
                                loadProgress: 100,
                                accelerationInfo: {
                                    gpu: ctx.gpu,
                                    reasonNoGPU: ctx.reasonNoGPU,
                                    devices: ctx.devices
                                }
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
                        s[slot] = { context: null, modelPath: null, isLoaded: false, loadProgress: 0, accelerationInfo: undefined };
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
                    let { embedding } = get();

                    // If no embedding model loaded, try auto-load default
                    if (!embedding.context) {
                        const { lastEmbeddingModel, loadModel } = get();
                        if (lastEmbeddingModel) {
                            console.log('[LocalServer] Embedding model missing, auto-loading:', lastEmbeddingModel);
                            emitToast('正在加载默认向量模型...', 'info');
                            try {
                                await loadModel(lastEmbeddingModel, 'embedding');
                                embedding = get().embedding; // Refresh state
                            } catch (e) {
                                console.error('[LocalServer] Failed to auto-load embedding model:', e);
                                emitToast('向量模型加载失败，请检查文件', 'error');
                                throw e;
                            }
                        } else {
                            emitToast('未配置本地向量模型', 'error');
                            throw new Error('No local embedding model configured');
                        }
                    }

                    const ctx = embedding.context; // Strict usage, NO fallback to main
                    if (!ctx) {
                        emitToast('向量化服务不可用', 'error');
                        throw new Error('Embedding context is null');
                    }

                    if (!text || text.trim().length === 0) {
                        console.warn('[LocalServer] generateEmbedding: Empty text provided');
                        // ❌ 不要返回硬编码的 1024 维向量，这会导致维度不匹配！
                        // 应该抛出错误让上层 aware，或者返回空数组让上层处理
                        throw new Error('generateEmbedding called with empty text');
                    }

                    console.log(`[LocalServer] generateEmbedding: Starting for text length ${text.length}`);
                    try {
                        const result = await runWithLock(ctx, async () => {
                            if (!ctx) throw new Error('Context became null during lock acquisition');
                            console.log('[LocalServer] generateEmbedding: Acquired lock, calling native embedding...');
                            return ctx.embedding(text);
                        });
                        console.log(`[LocalServer] generateEmbedding: Complete, vector length ${result?.embedding?.length}`);
                        return result.embedding;
                    } catch (e: any) {
                        console.error('[LocalServer] Native embedding crash/error:', e);
                        // If it's a hard crash, this might not catch it, but it helps for exceptions.
                        throw new Error(`Native Embedding Failed: ${e.message}`);
                    }
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
                        // 🔑 检查全局本地模型开关
                        const settingsState = useSettingsStore.getState();
                        if (!settingsState.localModelsEnabled) {
                            console.log('[LocalServer] Local models disabled in settings, skipping auto-load on hydration');
                            return;
                        }

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

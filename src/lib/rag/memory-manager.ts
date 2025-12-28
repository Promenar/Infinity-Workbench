import { Session, Message } from '../../types/chat';
import { vectorStore, SearchResult } from './vector-store';
import { EmbeddingClient } from './embedding';
import { useApiStore } from '../../store/api-store';
import { RecursiveCharacterTextSplitter } from './text-splitter';
import { db } from '../db';

export class MemoryManager {
    static async retrieveContext(query: string, sessionId: string, options: {
        enableMemory?: boolean;
        enableDocs?: boolean;
        activeDocIds?: string[];
        activeFolderIds?: string[];
    } = {}): Promise<string> {

        const { enableMemory = true, enableDocs = true, activeDocIds = [], activeFolderIds = [] } = options;
        const apiStore = useApiStore.getState();

        // 1. 获取查询向量 (Get Embedding)
        // ... (Embedding logic remains the same)
        let provider = apiStore.providers.find(p => p.enabled && p.type === 'openai' && p.models.some(m => m.enabled && m.type === 'embedding'));

        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (
                p.type === 'siliconflow' ||
                p.type === 'deepseek' ||
                p.type === 'moonshot' ||
                p.type === 'zhipu'
            ) && p.models.some(m => m.enabled && m.type === 'embedding'));
        }

        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'gemini' || p.type === 'google') && p.models.some(m => m.enabled && m.type === 'embedding'));
        }

        if (!provider) {
            // Fallback for legacy
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'openai' || p.type === 'gemini' || p.type === 'google'));
        }

        if (!provider) {
            console.warn('[MemoryManager] 未找到 Embedding 提供商，跳过 RAG。');
            return '';
        }

        // Find the active embedding model
        const embeddingModelConfig = provider.models.find(m => m.enabled && m.type === 'embedding');
        const modelId = embeddingModelConfig?.id || (provider.type === 'openai' ? 'text-embedding-3-small' : undefined);

        const embeddingClient = new EmbeddingClient(provider, modelId);
        let queryEmbedding: number[];

        try {
            queryEmbedding = await embeddingClient.embedQuery(query);
        } catch (e) {
            console.error('[MemoryManager] 查询向量化失败:', e);
            return '';
        }

        const results: SearchResult[] = [];

        // 2. 搜索记忆 (长期对话历史)
        if (enableMemory) {
            try {
                const memResults = await vectorStore.search(queryEmbedding, {
                    limit: 3,
                    filter: { sessionId, type: 'memory' },
                    threshold: 0.75
                });
                results.push(...memResults);
            } catch (e) {
                console.error('[MemoryManager] 记忆搜索失败:', e);
            }
        }

        // 3. 搜索文档 (知识库)
        if (enableDocs) {
            // 权限控制：如果启用了知识库但没有授权任何文档或文件夹，则不应检索任何内容
            const hasAuthorizedItems = (activeDocIds && activeDocIds.length > 0) || (activeFolderIds && activeFolderIds.length > 0);

            if (hasAuthorizedItems) {
                try {
                    // 解析文件夹下的所有文档 ID
                    const authorizedDocIds = new Set(activeDocIds);

                    if (activeFolderIds && activeFolderIds.length > 0) {
                        const folderPlaceholders = activeFolderIds.map(() => '?').join(',');
                        const folderDocs = await db.execute(
                            `SELECT id FROM documents WHERE folder_id IN (${folderPlaceholders})`,
                            activeFolderIds
                        );
                        if (folderDocs.rows) {
                            for (let i = 0; i < folderDocs.rows.length; i++) {
                                authorizedDocIds.add(folderDocs.rows[i].id as string);
                            }
                        }
                    }

                    const docResults = await vectorStore.search(queryEmbedding, {
                        limit: 8, // Increased slightly since we filter later
                        filter: { type: 'doc' },
                        threshold: 0.5
                    });

                    // 穿透式过滤：仅保留属于授权文档的检索结果
                    const filteredDocs = docResults.filter(r => authorizedDocIds.has(r.docId || ''));

                    results.push(...filteredDocs);
                } catch (e) {
                    console.error('[MemoryManager] 文档搜索失败:', e);
                }
            } else {
                console.log('[MemoryManager] 知识库已开启但未选择授权范围，跳过检索。');
            }
        }

        // 4.原本格式化上下文 (Format Context)
        if (results.length === 0) return '';

        // 按相似度排序组合结果
        results.sort((a, b) => b.similarity - a.similarity);

        // 去重 (以防万一)
        const uniqueResults = results.filter((v, i, a) => a.findIndex(t => t.id === v.id) === i);

        // 取前 5 个结果
        const finalResults = uniqueResults.slice(0, 5);

        const contextBlock = finalResults.map(r => {
            const typeLabel = r.metadata?.type === 'memory' ? '记忆 (Memory)' : '文档 (Document)';
            const date = new Date(r.createdAt).toLocaleDateString();
            return `[${typeLabel} - ${date}]: ${r.content}`;
        }).join('\n\n');

        return `relevant_context_block (参考上下文):\n${contextBlock}`;
    }

    /**
     * 将一个“对话轮次” (用户消息 + AI 回复) 归档到向量记忆中
     */
    static async addTurnToMemory(sessionId: string, userContent: string, aiContent: string) {
        if (!userContent || !aiContent) return;

        const apiStore = useApiStore.getState();

        let provider = apiStore.providers.find(p => p.enabled && p.type === 'openai' && p.models.some(m => m.enabled && m.type === 'embedding'));
        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (
                p.type === 'siliconflow' || p.type === 'deepseek' || p.type === 'moonshot' || p.type === 'zhipu'
            ) && p.models.some(m => m.enabled && m.type === 'embedding'));
        }
        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'gemini' || p.type === 'google'));
        }

        if (!provider) return;

        // Find the active embedding model
        const embeddingModelConfig = provider.models.find(m => m.enabled && m.type === 'embedding');
        const modelId = embeddingModelConfig?.id || (provider.type === 'openai' ? 'text-embedding-3-small' : undefined);

        try {
            // Ensure session exists in SQLite for FK constraint
            // Since ChatStore uses AsyncStorage, we must sync the ID here
            const now = Date.now();
            await db.execute(
                `INSERT OR IGNORE INTO sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)`,
                [sessionId, 'Shadow Session', now, now]
            );

            // 格式: "User: ... \n Assistant: ..."
            const turnText = `User: ${userContent}\nAssistant: ${aiContent}`;

            // 检查长度。如果太长，进行切分。
            // 对于单个轮次通常没问题，但 DeepSeek R1 的输出可能很长。
            // 以后备用 Splitter
            const splitter = new RecursiveCharacterTextSplitter({ chunkSize: 1000, chunkOverlap: 100 });
            const chunks = splitter.splitText(turnText);

            const embeddingClient = new EmbeddingClient(provider, modelId);
            const embeddings = await embeddingClient.embedDocuments(chunks);

            const vectors = chunks.map((chunk, i) => ({
                sessionId,
                content: chunk,
                embedding: embeddings[i],
                metadata: { type: 'memory', chunkIndex: i }
            }));

            await vectorStore.addVectors(vectors);
            console.log(`[MemoryManager] 已归档会话 ${sessionId} (${vectors.length} 个切片)`);

        } catch (e) {
            console.error('[MemoryManager] 归档轮次失败:', e);
        }
    }
}

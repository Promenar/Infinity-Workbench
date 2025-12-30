import { generateId } from '../utils/id-generator';
import { db } from '../db';
import { VectorizationTask } from '../../types/rag';
import { RecursiveCharacterTextSplitter } from './text-splitter';
import { EmbeddingClient } from './embedding';
import { vectorStore } from './vector-store';
import { useApiStore } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';

/**
 * 后台向量化任务队列管理器
 * 负责异步处理文档向量化，避免阻塞UI
 */
export class VectorizationQueue {
    private queue: VectorizationTask[] = [];
    private isProcessing = false;
    private onStateChange?: (queue: VectorizationTask[], currentTask: VectorizationTask | null) => void;

    constructor(onStateChange?: (queue: VectorizationTask[], current: VectorizationTask | null) => void) {
        this.onStateChange = onStateChange;
    }

    /**
     * 将文档加入向量化队列
     */
    async enqueue(docId: string, docTitle: string, content: string) {
        const task: VectorizationTask = {
            id: generateId(),
            docId,
            docTitle,
            status: 'pending',
            progress: 0,
            createdAt: Date.now()
        };

        this.queue.push(task);
        this.notifyStateChange();

        // 标记文档为"处理中"
        await db.execute(
            'UPDATE documents SET vectorized = 1 WHERE id = ?',
            [docId]
        );

        if (!this.isProcessing) {
            this.processNext();
        }
    }

    /**
     * 处理队列中的下一个任务
     */
    private async processNext() {
        if (this.queue.length === 0) {
            this.isProcessing = false;
            this.notifyStateChange();
            return;
        }

        this.isProcessing = true;
        const task = this.queue[0];
        task.status = 'processing';
        this.notifyStateChange();

        try {
            // 获取文档内容
            const docResult = await db.execute(
                'SELECT content, title FROM documents WHERE id = ?',
                [task.docId]
            );

            if (!docResult.rows || docResult.rows.length === 0) {
                throw new Error('Document not found');
            }

            const content = docResult.rows[0].content as string;

            // 文本分割
            task.progress = 10;
            this.notifyStateChange();

            // 从配置获取文档切块参数
            const settings = useSettingsStore.getState();
            const ragConfig = settings.globalRagConfig;

            const splitter = new RecursiveCharacterTextSplitter({
                chunkSize: ragConfig.docChunkSize,
                chunkOverlap: ragConfig.chunkOverlap
            });
            const chunks = splitter.splitText(content);

            // 获取embedding provider
            task.progress = 20;
            this.notifyStateChange();

            const apiStore = useApiStore.getState();
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
                throw new Error('No embedding provider available');
            }

            const embeddingModelConfig = provider.models.find(m => m.enabled && m.type === 'embedding');
            const modelId = embeddingModelConfig?.id;

            // 向量化（批量处理以显示进度）
            const embeddingClient = new EmbeddingClient(provider, modelId);
            const batchSize = 10;
            const allEmbeddings: number[][] = [];

            for (let i = 0; i < chunks.length; i += batchSize) {
                const batch = chunks.slice(i, i + batchSize);
                const batchEmbeddings = await embeddingClient.embedDocuments(batch);
                allEmbeddings.push(...batchEmbeddings);

                task.progress = 20 + Math.floor((i / chunks.length) * 60);
                this.notifyStateChange();
            }

            // 存储向量
            task.progress = 85;
            this.notifyStateChange();

            const vectors = chunks.map((chunk, index) => ({
                docId: task.docId,
                content: chunk,
                embedding: allEmbeddings[index],
                metadata: { source: 'import', type: 'doc', chunkIndex: index }
            }));

            await vectorStore.addVectors(vectors);

            // 更新文档状态
            await db.execute(
                'UPDATE documents SET vectorized = 2, vector_count = ? WHERE id = ?',
                [vectors.length, task.docId]
            );

            task.status = 'completed';
            task.progress = 100;
            console.log(`[VectorizationQueue] Completed: ${task.docTitle} (${vectors.length} vectors)`);

        } catch (error) {
            console.error('[VectorizationQueue] Failed:', error);
            task.status = 'failed';
            task.error = (error as Error).message;

            // 标记文档为失败
            await db.execute(
                'UPDATE documents SET vectorized = -1 WHERE id = ?',
                [task.docId]
            );
        } finally {
            // 移出队列
            this.queue.shift();
            this.notifyStateChange();

            // 处理下一个（小延迟避免资源占用）
            setTimeout(() => this.processNext(), 500);
        }
    }

    /**
     * 通知状态变化
     */
    private notifyStateChange() {
        if (this.onStateChange) {
            const currentTask = this.queue.length > 0 ? this.queue[0] : null;
            this.onStateChange([...this.queue], currentTask);
        }
    }

    /**
     * 获取当前队列状态
     */
    getState() {
        return {
            queue: [...this.queue],
            currentTask: this.queue.length > 0 ? this.queue[0] : null,
            isProcessing: this.isProcessing
        };
    }

    /**
     * 清空队列（谨慎使用）
     */
    clear() {
        this.queue = [];
        this.isProcessing = false;
        this.notifyStateChange();
    }
}

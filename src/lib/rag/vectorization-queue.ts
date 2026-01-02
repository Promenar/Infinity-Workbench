import { generateId } from '../utils/id-generator';
import { VectorizationTask } from '../../types/rag';
import { useApiStore } from '../../store/api-store';
import { useRagStore } from '../../store/rag-store';
import { useSettingsStore } from '../../store/settings-store';
import { TrigramTextSplitter } from './text-splitter';
import { EmbeddingClient } from './embedding';
import { db } from '../db';  // ✅ 正确路径
import { vectorStore } from './vector-store';

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

            // ✅ 使用 Trigram 分词器（中文友好）
            const splitter = new TrigramTextSplitter({
                chunkSize: ragConfig.docChunkSize,
                chunkOverlap: ragConfig.chunkOverlap
            });
            const chunks = splitter.splitText(content);

            //获取embedding provider
            task.progress = 20;
            this.notifyStateChange();

            const apiStore = useApiStore.getState();
            const preferredModelId = settings.defaultEmbeddingModel;

            let provider: any;
            let modelId: string | undefined;

            // 优先级1: 用户显式选择的 Embedding 模型
            if (preferredModelId) {
                provider = apiStore.providers.find(p =>
                    p.enabled && p.models.some((m: any) =>
                        (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled
                    )
                );

                if (provider) {
                    const model = provider.models.find((m: any) =>
                        m.uuid === preferredModelId || m.id === preferredModelId
                    );
                    modelId = model?.id;
                }
            }

            // 优先级2: Fallback 到第一个可用的 Embedding 模型（无 Provider 偏好）
            if (!provider || !modelId) {
                provider = apiStore.providers.find(p =>
                    p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding')
                );

                if (provider) {
                    const embeddingModel = provider.models.find((m: any) => m.enabled && m.type === 'embedding');
                    modelId = embeddingModel?.id;
                }
            }

            if (!provider || !modelId) {
                throw new Error('No embedding provider available');
            }

            // 向量化（批量处理以显示进度）
            const embeddingClient = new EmbeddingClient(provider, modelId);
            const batchSize = 10;
            const allEmbeddings: number[][] = [];

            for (let i = 0; i < chunks.length; i += batchSize) {
                const batch = chunks.slice(i, i + batchSize);
                const result = await embeddingClient.embedDocuments(batch);
                allEmbeddings.push(...result.embeddings);  // ✅ 访问 embeddings 属性

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
            task.error = this.getFriendlyErrorMessage(error as Error);

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
     * 将技术错误转换为用户友好的中文提示
     */
    private getFriendlyErrorMessage(error: Error): string {
        const msg = error.message.toLowerCase();

        // API 密钥问题
        if (msg.includes('api key') || msg.includes('unauthorized') || msg.includes('401')) {
            return '❌ API 密钥无效或已过期，请检查服务商配置';
        }

        // 配额/余额问题
        if (msg.includes('quota') || msg.includes('rate limit') || msg.includes('insufficient') || msg.includes('429')) {
            return '⚠️ API 配额已用尽或请求过于频繁，请稍后重试';
        }

        // 网络问题
        if (msg.includes('network') || msg.includes('timeout') || msg.includes('enotfound') || msg.includes('fetch')) {
            return '🌐 网络连接失败，请检查网络或代理设置';
        }

        // Embedding 模型问题
        if (msg.includes('no embedding') || msg.includes('embedding not')) {
            return '🔧 未配置 Embedding 模型，请在设置中添加并启用';
        }

        // 文件格式问题
        if (msg.includes('parse') || msg.includes('invalid format')) {
            return '📄 文件格式不支持或已损坏，请尝试其他文件';
        }

        // 权限问题
        if (msg.includes('permission') || msg.includes('forbidden') || msg.includes('403')) {
            return '🔒 权限不足，请检查 API 权限配置';
        }

        // Google 特定错误
        if (msg.includes('google') && msg.includes('not yet')) {
            return '⏳ Google Embedding 初始化中，请稍后重试';
        }

        // 默认：保留原始错误但添加友好前缀
        return `⚙️ 向量化失败: ${error.message.substring(0, 100)}`;
    }

    /**
     * 获取当前队列长度
     */
    getQueueLength(): number {
        return this.queue.length;
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

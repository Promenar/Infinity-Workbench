import { generateId } from '../utils/id-generator';
import { VectorizationTask } from '../../types/rag';
import { RagReference } from '../../types/chat';
import { useApiStore } from '../../store/api-store';
import { useRagStore } from '../../store/rag-store';
import { useSettingsStore } from '../../store/settings-store';
import { TrigramTextSplitter } from './text-splitter';
import { EmbeddingClient } from './embedding';
import { db } from '../db';
import { vectorStore } from './vector-store';

/**
 * 后台向量化任务队列管理器
 * 负责异步处理文档向量化，避免阻塞UI
 */
export class VectorizationQueue {
  private queue: VectorizationTask[] = [];
  private isProcessing = false;
  private onStateChange?: (
    queue: VectorizationTask[],
    currentTask: VectorizationTask | null,
  ) => void;

  constructor(
    onStateChange?: (queue: VectorizationTask[], current: VectorizationTask | null) => void,
  ) {
    this.onStateChange = onStateChange;
  }

  /**
   * 将文档加入向量化队列 (Supports KG Strategy Override)
   */
  async enqueue(docId: string, docTitle: string, content: string, kgStrategy?: 'full' | 'summary-first' | 'on-demand') {
    const task: VectorizationTask = {
      id: generateId(),
      docId,
      docTitle,
      status: 'pending',
      progress: 0,
      createdAt: Date.now(),
      kgStrategy,
    };

    this.queue.push(task);
    this.notifyStateChange();

    // 标记文档为"处理中"
    await db.execute('UPDATE documents SET vectorized = 1 WHERE id = ?', [docId]);

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
    let skipShift = false;

    try {
      // 获取文档内容
      const docResult = await db.execute('SELECT content, title, is_global FROM documents WHERE id = ?', [
        task.docId,
      ]);

      if (!docResult.rows || docResult.rows.length === 0) {
        throw new Error('Document not found');
      }

      const content = docResult.rows[0].content as string;
      const settings = useSettingsStore.getState();
      const ragConfig = settings.globalRagConfig;

      // 0. 增量哈希检查 (Incremental Hash)
      const contentHash = this.simpleHash(content);
      const existingHashResult = await db.execute('SELECT content_hash, vectorized FROM documents WHERE id = ?', [task.docId]);
      const existingHash = existingHashResult.rows?.[0]?.content_hash;
      const existingVectorized = existingHashResult.rows?.[0]?.vectorized;

      if (ragConfig.enableIncrementalHash && existingHash === contentHash && existingVectorized === 2) {
        console.log(`[VectorizationQueue] Skip: Content hash matched for ${task.docTitle}`);
        task.status = 'completed';
        task.progress = 100;
        return;
      }

      // 0.1 本地预处理
      let processedContent = content;
      if (ragConfig.enableLocalPreprocess) {
        processedContent = this.preprocessText(content);
      }

      // 1. 文本分割
      task.status = 'chunking';
      task.progress = 10;
      this.notifyStateChange();

      const splitter = new TrigramTextSplitter({
        chunkSize: ragConfig.docChunkSize,
        chunkOverlap: ragConfig.chunkOverlap,
      });
      const chunks = await splitter.splitText(processedContent);

      // 2. 获取 Provider
      task.status = 'vectorizing';
      task.progress = 20;
      this.notifyStateChange();

      const apiStore = useApiStore.getState();
      const preferredModelId = settings.defaultEmbeddingModel; // This is usually UUID

      let provider: any = undefined;
      let modelId: string | undefined = undefined;
      let foundModel: any = undefined;

      // Priority 1: Match by UUID (user's explicit selection)
      if (preferredModelId) {
        for (const p of apiStore.providers) {
          if (!p.enabled) continue;
          foundModel = p.models.find((m: any) => (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled);
          if (foundModel) {
            provider = p;
            modelId = foundModel.id; // Use API-facing model.id, NOT uuid
            break;
          }
        }
      }

      // Priority 2: Fallback to any enabled embedding model
      if (!provider || !modelId) {
        for (const p of apiStore.providers) {
          if (!p.enabled) continue;
          foundModel = p.models.find((m: any) => m.enabled && m.type === 'embedding');
          if (foundModel) {
            provider = p;
            modelId = foundModel.id;
            break;
          }
        }
      }

      if (!provider || !modelId) throw new Error('No embedding provider available');
      console.log(`[VectorizationQueue] Using Provider: ${provider.name}, Model: ${modelId}`);

      // 3. 向量提取
      const embeddingClient = new EmbeddingClient(provider, modelId);
      // Use batch size of 1 for local models to show per-chunk progress (since local embedding is slower)
      const batchSize = provider.type === 'local' ? 1 : 10;
      const allEmbeddings: number[][] = [];
      console.log(`[VectorizationQueue] Processing ${chunks.length} chunks with batch size ${batchSize}`);

      for (let i = 0; i < chunks.length; i += batchSize) {
        const batch = chunks.slice(i, i + batchSize);
        const result = await embeddingClient.embedDocuments(batch);
        allEmbeddings.push(...result.embeddings);

        const completedChunks = Math.min(i + batchSize, chunks.length);
        task.progress = 20 + (completedChunks / chunks.length) * 60;
        console.log(`[VectorizationQueue] Progress: ${task.progress.toFixed(1)}% (${completedChunks}/${chunks.length})`);
        this.notifyStateChange();
        await new Promise(resolve => setTimeout(resolve, 0)); // Yield
      }

      // 4. 存储向量
      task.status = 'saving';
      task.progress = 85;
      this.notifyStateChange();

      const vectors = chunks.map((chunk, index) => ({
        docId: task.docId,
        content: chunk,
        embedding: allEmbeddings[index],
        metadata: { source: 'import', type: 'doc', chunkIndex: index },
      }));

      await vectorStore.addVectors(vectors);
      await db.execute('UPDATE documents SET vectorized = 2, vector_count = ?, content_hash = ? WHERE id = ?', [
        vectors.length,
        contentHash,
        task.docId,
      ]);

      // 5. 知识图谱 (可选)
      if (ragConfig.enableKnowledgeGraph) {
        task.status = 'extracting';
        this.notifyStateChange();
        const { graphExtractor } = require('./graph-extractor');
        const strategy = task.kgStrategy || ragConfig.costStrategy || 'on-demand';

        if (strategy === 'full') {
          for (let k = 0; k < chunks.length; k++) {
            await graphExtractor.extractAndSave(chunks[k], task.docId);
            await new Promise(resolve => setTimeout(resolve, 50));
          }
        } else if (strategy === 'summary-first') {
          const sample = [chunks[0], chunks[Math.floor(chunks.length / 2)], chunks[chunks.length - 1]].filter(Boolean);
          await graphExtractor.extractAndSave(sample.join('\n\n'), task.docId);
        }
      }

      task.status = 'completed';
      task.progress = 100;
      console.log(`[VectorizationQueue] Finished: ${task.docTitle}`);

    } catch (error) {
      const msg = (error instanceof Error ? error.message : String(error)).toLowerCase();

      if (msg.includes('local model not loaded') || msg.includes('context is predicting')) {
        console.warn(`[VectorizationQueue] Busy/Not Ready (${msg}), retrying in 3s...`);
        skipShift = true;
        setTimeout(() => this.processNext(), 3000);
      } else {
        console.error('[VectorizationQueue] Error:', error);
        task.status = 'failed';
        const errObj = error instanceof Error ? error : new Error(String(error));
        task.error = this.getFriendlyErrorMessage(errObj);
        await db.execute('UPDATE documents SET vectorized = -1 WHERE id = ?', [task.docId]);
      }
    } finally {
      if (!skipShift && this.queue.length > 0 && this.queue[0] === task) {
        this.queue.shift();
      }
      this.notifyStateChange();

      if (this.queue.length > 0) {
        if (!skipShift) setTimeout(() => this.processNext(), 500);
      } else {
        this.isProcessing = false;
      }
    }
  }

  private simpleHash(str: string): string {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = (hash << 5) - hash + char;
      hash = hash & hash;
    }
    return hash.toString(36) + str.length.toString(36);
  }

  private preprocessText(text: string): string {
    return text.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
  }

  private notifyStateChange() {
    if (this.onStateChange) {
      const currentTask = this.queue.length > 0 ? this.queue[0] : null;
      this.onStateChange([...this.queue], currentTask);
    }
  }

  private getFriendlyErrorMessage(error: Error): string {
    const msg = error.message.toLowerCase();
    if (msg.includes('api key') || msg.includes('401')) return '❌ API 密钥无效';
    if (msg.includes('quota') || msg.includes('429')) return '⚠️ 配额不足或请求频繁';
    if (msg.includes('network') || msg.includes('timeout')) return '🌐 网络连接失败';
    if (msg.includes('no embedding')) return '🔧 未配置 Embedding 模型';
    return `⚙️ 失败: ${error.message.substring(0, 50)}`;
  }

  getQueueLength(): number { return this.queue.length; }
  getState() {
    return {
      queue: [...this.queue],
      currentTask: this.queue.length > 0 ? this.queue[0] : null,
      isProcessing: this.isProcessing,
    };
  }
  cancel(docId: string) {
    this.queue = this.queue.filter(t => t.docId !== docId);
    this.notifyStateChange();
  }
  clear() {
    this.queue = [];
    this.isProcessing = false;
    this.notifyStateChange();
  }
}

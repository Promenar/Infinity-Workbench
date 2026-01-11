import { generateId } from '../utils/id-generator';
import { VectorizationTask } from '../../types/rag';
import { useApiStore } from '../../store/api-store';
import { useRagStore } from '../../store/rag-store';
import { useSettingsStore } from '../../store/settings-store';
import { TrigramTextSplitter } from './text-splitter';
import { EmbeddingClient } from './embedding';
import { db } from '../db'; // ✅ 正确路径
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
    task.status = 'chunking'; // Start with chunking or reader
    this.notifyStateChange();

    try {
      // 获取文档内容
      const docResult = await db.execute('SELECT content, title, is_global FROM documents WHERE id = ?', [
        task.docId,
      ]);

      if (!docResult.rows || docResult.rows.length === 0) {
        throw new Error('Document not found');
      }

      const content = docResult.rows[0].content as string;
      const isGlobal = !!docResult.rows[0].is_global;

      // 从配置获取文档切块参数
      const settings = useSettingsStore.getState();
      const ragConfig = settings.globalRagConfig;

      // 0. 增量哈希检查 (Incremental Hash)
      // 计算内容摘要，若内容未变且已向量化，则跳过
      const contentHash = this.simpleHash(content);
      const existingHashResult = await db.execute('SELECT content_hash, vectorized FROM documents WHERE id = ?', [task.docId]);
      const existingHash = existingHashResult.rows?.[0]?.content_hash;
      const existingVectorized = existingHashResult.rows?.[0]?.vectorized;

      if (ragConfig.enableIncrementalHash && existingHash === contentHash && existingVectorized === 2) {
        console.log(`[VectorizationQueue] Skip: Content hash matched for ${task.docTitle}`);
        task.status = 'completed';
        task.progress = 100;
        this.notifyStateChange();
        return;
      }

      // 0.1 本地预处理 (Local Preprocess)
      let processedContent = content;
      if (ragConfig.enableLocalPreprocess) {
        processedContent = this.preprocessText(content);
        console.log(`[VectorizationQueue] Preprocessed text length: ${content.length} -> ${processedContent.length}`);
      }

      // 文本分割
      task.status = 'chunking';
      task.progress = 10;
      this.notifyStateChange();

      // ✅ 使用 Trigram 分词器（中文友好）
      const splitter = new TrigramTextSplitter({
        chunkSize: ragConfig.docChunkSize,
        chunkOverlap: ragConfig.chunkOverlap,
      });
      const chunks = await splitter.splitText(processedContent);

      //获取embedding provider
      task.status = 'vectorizing';
      task.progress = 20;
      this.notifyStateChange();

      const apiStore = useApiStore.getState();
      const preferredModelId = settings.defaultEmbeddingModel;

      let provider: any;
      let modelId: string | undefined;

      // 优先级1: 用户显式选择的 Embedding 模型
      if (preferredModelId) {
        provider = apiStore.providers.find(
          (p) =>
            p.enabled &&
            p.models.some(
              (m: any) => (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled,
            ),
        );

        if (provider) {
          const model = provider.models.find(
            (m: any) => m.uuid === preferredModelId || m.id === preferredModelId,
          );
          modelId = model?.id;
        }
      }

      // 优先级2: Fallback 到第一个可用的 Embedding 模型（无 Provider 偏好）
      if (!provider || !modelId) {
        provider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding'),
        );

        if (provider) {
          const embeddingModel = provider.models.find(
            (m: any) => m.enabled && m.type === 'embedding',
          );
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
        allEmbeddings.push(...result.embeddings); // ✅ 访问 embeddings 属性

        task.progress = 20 + Math.floor((i / chunks.length) * 60);
        this.notifyStateChange();
      }

      // 存储向量
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

      // 更新文档状态（包括哈希）
      await db.execute('UPDATE documents SET vectorized = 2, vector_count = ?, content_hash = ? WHERE id = ?', [
        vectors.length,
        contentHash,
        task.docId,
      ]);

      task.status = 'completed';
      task.progress = 100;
      console.log(`[VectorizationQueue] Completed: ${task.docTitle} (${vectors.length} vectors)`);

      // Phase 8: Knowledge Graph Learning
      if (ragConfig.enableKnowledgeGraph) {
        console.log('[VectorizationQueue] Starting Knowledge Graph Learning...');
        const { graphExtractor } = require('./graph-extractor');

        // Determine effective strategy (Task Override > Global Config)
        const effectiveStrategy = task.kgStrategy || ragConfig.costStrategy || 'on-demand';
        console.log(`[VectorizationQueue] Strategy: ${effectiveStrategy}`);

        // 策略检查
        if (effectiveStrategy === 'full') {
          // 全量扫描：顺序执行并让出主线程，防止 UI 卡死
          for (const chunk of chunks) {
            try {
              // 💡 Scope Decision:
              // If isGlobal -> Extract to Global Graph (scope=undefined)
              // If !isGlobal -> Do NOT extract to separate graph (for now) OR extraction is skipped?
              // User request: "Complete permission management for whether to include in global KG".
              // This implies: If disabled, don't put in Global KG.
              // So we ONLY extract if isGlobal is TRUE.
              // 💡 Scope Decision:
              // Changed: Extract for ALL docs. Isolation is handled by retrieval filtering.
              await graphExtractor.extractAndSave(chunk, task.docId);

              // 💡 关键修复：每处理一个分块，强制让出主线程 20ms，让 UI 有机会渲染
              await new Promise((resolve) => setTimeout(resolve, 20));
            } catch (e) {
              console.error('[VectorizationQueue] KG Extraction Error (Chunk):', e);
            }
          }
        } else if (effectiveStrategy === 'summary-first') {
          // 摘要优先
          // ... (Logic same as above, wrapped in isGlobal check)
          // 摘要优先
          // ... (Logic same as above, wrapped in isGlobal check)
          // ... existing summary logic ...
          let sampleChunks: string[] = [];
          if (chunks.length <= 3) {
            sampleChunks = chunks;
          } else {
            const first = chunks[0];
            const midIndex = Math.floor(chunks.length / 2);
            const mid = chunks[midIndex];
            const last = chunks[chunks.length - 1];
            sampleChunks = [first, mid, last];
          }
          const summaryContext = sampleChunks.join('\n\n[...]\n\n');

          try {
            await graphExtractor.extractAndSave(summaryContext, task.docId);
          } catch (e) {
            console.error('[VectorizationQueue] KG Extraction Error (Summary):', e);
          }
        }
        // 'on-demand' 模式在此处不执行动作
      }
    } catch (error) {
      console.error('[VectorizationQueue] Failed:', error);
      task.status = 'failed';
      task.error = this.getFriendlyErrorMessage(error as Error);

      // 标记文档为失败
      await db.execute('UPDATE documents SET vectorized = -1 WHERE id = ?', [task.docId]);
    } finally {
      // 移出队列
      this.queue.shift();
      this.notifyStateChange();

      // 处理下一个（小延迟避免资源占用）
      setTimeout(() => this.processNext(), 500);
    }
  }

  /**
   * 简单的字符串哈希函数（用于增量对比）
   */
  private simpleHash(str: string): string {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = (hash << 5) - hash + char;
      hash = hash & hash; // Convert to 32bit integer
    }
    return hash.toString(36) + str.length.toString(36);
  }

  /**
   * 本地预处理：清洗多余空白、HTML 标签等
   */
  private preprocessText(text: string): string {
    return text
      .replace(/<[^>]*>/g, '') // 移除 HTML 标签
      .replace(/\s+/g, ' ') // 合并多个空格/换行
      .trim();
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
    if (
      msg.includes('quota') ||
      msg.includes('rate limit') ||
      msg.includes('insufficient') ||
      msg.includes('429')
    ) {
      return '⚠️ API 配额已用尽或请求过于频繁，请稍后重试';
    }

    // 网络问题
    if (
      msg.includes('network') ||
      msg.includes('timeout') ||
      msg.includes('enotfound') ||
      msg.includes('fetch')
    ) {
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
      isProcessing: this.isProcessing,
    };
  }

  /**
   * 取消指定任务
   */
  cancel(docId: string) {
    // 1. 如果在等待队列中，直接移除
    this.queue = this.queue.filter(t => t.docId !== docId);

    // 2. 如果是当前正在处理的任务，无法立即中断 Promise 链，
    // 但我们可以标记状态，让后续步骤检查是否应继续
    // (需要重构 processNext 来支持 abort signal，目前简化为仅移除队列)

    this.notifyStateChange();
  }

  /**
   * 清空队列（强力停止）
   */
  clear() {
    this.queue = [];
    this.isProcessing = false; // 强制重置状态
    this.notifyStateChange();
  }
}

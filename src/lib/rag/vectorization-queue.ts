import { generateId } from '../utils/id-generator';
import { VectorizationTask } from '../../types/rag';
import { useApiStore } from '../../store/api-store';
import { useRagStore } from '../../store/rag-store';
import { useSettingsStore } from '../../store/settings-store';
import { TrigramTextSplitter } from './text-splitter';
import { EmbeddingClient } from './embedding';
import { db } from '../db';
import { vectorStore } from './vector-store';
import { estimateTokens } from '../../features/chat/utils/token-counter';

/**
 * 统一向量化任务队列管理器
 * 支持文档向量化和消息记忆归档，实现非阻塞后台处理
 * 
 * 🔑 核心特性:
 * - 支持 'document' 和 'memory' 两种任务类型
 * - 串行处理，避免并发资源竞争
 * - 心跳更新，支持断点恢复检测
 * - 统一的进度上报机制
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

  // ==================== 公共入队方法 ====================

  /**
   * 将文档加入向量化队列
   */
  async enqueueDocument(docId: string, docTitle: string, content: string, kgStrategy?: 'full' | 'summary-first' | 'on-demand') {
    const task: VectorizationTask = {
      id: generateId(),
      type: 'document',
      docId,
      docTitle,
      status: 'pending',
      progress: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      kgStrategy,
    };

    this.queue.push(task);
    await this.saveTaskToDb(task); // 🔑 持久化任务
    this.notifyStateChange();

    // 标记文档为"处理中"
    await db.execute('UPDATE documents SET vectorized = 1 WHERE id = ?', [docId]);

    if (!this.isProcessing) {
      this.processNext();
    }
  }

  /**
   * 将消息对话轮次加入向量化队列 (Memory Archiving)
   * 🔑 非阻塞：立即返回，后台异步处理
   */
  async enqueueMemory(
    sessionId: string,
    userContent: string,
    aiContent: string,
    userMessageId: string,
    assistantMessageId: string,
  ) {
    // Sanitize: Strip massive Base64 image data
    const sanitize = (text: string) =>
      text.replace(/!\[(.*?)\]\(data:image\/.*?;base64,.*?\)/g, '[Image: $1]');

    const task: VectorizationTask = {
      id: generateId(),
      type: 'memory',
      sessionId,
      userContent: sanitize(userContent),
      aiContent: sanitize(aiContent),
      userMessageId,
      assistantMessageId,
      status: 'pending',
      progress: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    this.queue.push(task);
    await this.saveTaskToDb(task); // 🔑 持久化任务
    this.notifyStateChange();
    console.log(`[VectorizationQueue] Memory task enqueued for session ${sessionId}`);

    if (!this.isProcessing) {
      this.processNext();
    }
  }

  /**
   * 兼容旧版 API: enqueue() 默认为文档任务
   * @deprecated 请使用 enqueueDocument 或 enqueueMemory
   */
  async enqueue(docId: string, docTitle: string, content: string, kgStrategy?: 'full' | 'summary-first' | 'on-demand') {
    return this.enqueueDocument(docId, docTitle, content, kgStrategy);
  }

  /**
   * 🔑 将会话 KG 批量任务加入队列
   * 由 rag-store.accumulateForKG 在累积满后调用
   */
  async enqueueSessionKG(
    sessionId: string,
    contents: string[],
    messageIds: string[],
  ) {
    const task: VectorizationTask = {
      id: generateId(),
      type: 'session-kg',
      sessionId,
      kgBatchContent: contents,
      kgMessageIds: messageIds,
      status: 'pending',
      progress: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    this.queue.push(task);
    await this.saveTaskToDb(task);
    this.notifyStateChange();
    console.log(`[VectorizationQueue] Session KG batch task enqueued for session ${sessionId} (${contents.length} turns)`);

    if (!this.isProcessing) {
      this.processNext();
    }
  }

  // ==================== 核心处理逻辑 ====================

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
      // 更新心跳并持久化
      task.status = 'vectorizing';
      task.updatedAt = Date.now();
      await this.saveTaskToDb(task); // 🔑 持久化任务状态
      this.notifyStateChange();

      if (task.type === 'document') {
        await this.processDocumentTask(task);
      } else if (task.type === 'memory') {
        await this.processMemoryTask(task);
      } else if (task.type === 'session-kg') {
        await this.processSessionKGTask(task);
      }

      task.status = 'completed';
      task.progress = 100;
      await this.removeTaskFromDb(task.id); // 🔑 完成后删除持久化记录
      const taskLabel = task.type === 'document' ? task.docTitle
        : task.type === 'session-kg' ? `SessionKG-${task.sessionId}`
          : `Memory-${task.sessionId}`;
      console.log(`[VectorizationQueue] Finished: ${taskLabel}`);

    } catch (error) {
      const msg = (error instanceof Error ? error.message : String(error)).toLowerCase();

      if (msg.includes('local model not loaded') || msg.includes('context is predicting')) {
        console.warn(`[VectorizationQueue] Busy/Not Ready (${msg}), retrying in 3s...`);
        skipShift = true;
        await this.saveTaskToDb(task); // 🔑 保存重试状态
        setTimeout(() => this.processNext(), 3000);
      } else {
        console.error('[VectorizationQueue] Error:', error);
        task.status = 'failed';
        const errObj = error instanceof Error ? error : new Error(String(error));
        task.error = this.getFriendlyErrorMessage(errObj);
        await this.saveTaskToDb(task); // 🔑 保存失败状态

        // 仅文档任务需要更新数据库状态
        if (task.type === 'document' && task.docId) {
          await db.execute('UPDATE documents SET vectorized = -1 WHERE id = ?', [task.docId]);
        }
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
        // 🔑 队列空时清理已完成的持久化记录
        this.cleanupCompletedTasks();
      }
    }
  }


  /**
   * 处理文档向量化任务
   */
  private async processDocumentTask(task: VectorizationTask) {
    if (!task.docId) throw new Error('Document task missing docId');

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
    task.totalChunks = chunks.length;

    // 2. 获取 Provider
    task.status = 'vectorizing';
    task.progress = 20;
    this.notifyStateChange();

    const { provider, modelId } = this.getEmbeddingProvider();
    if (!provider || !modelId) throw new Error('No embedding provider available');
    console.log(`[VectorizationQueue] Using Provider: ${provider.name}, Model: ${modelId}`);

    // 3. 向量提取
    const embeddingClient = new EmbeddingClient(provider, modelId);
    const batchSize = provider.type === 'local' ? 1 : 10;
    const allEmbeddings: number[][] = [];
    console.log(`[VectorizationQueue] Processing ${chunks.length} chunks with batch size ${batchSize}`);

    const startIndex = task.lastChunkIndex || 0; // 支持断点续传
    for (let i = startIndex; i < chunks.length; i += batchSize) {
      const batch = chunks.slice(i, i + batchSize);
      const result = await embeddingClient.embedDocuments(batch);
      allEmbeddings.push(...result.embeddings);

      const completedChunks = Math.min(i + batchSize, chunks.length);
      task.progress = 20 + (completedChunks / chunks.length) * 60;
      task.lastChunkIndex = completedChunks;
      task.updatedAt = Date.now(); // 心跳更新
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
      task.progress = 85; // 🔑 KG 抽取阶段起始进度
      this.notifyStateChange();
      const { graphExtractor } = require('./graph-extractor');
      const strategy = task.kgStrategy || ragConfig.costStrategy || 'on-demand';

      if (strategy === 'full') {
        // 🔑 Full 策略：逐 chunk 抽取，细粒度进度
        for (let k = 0; k < chunks.length; k++) {
          await graphExtractor.extractAndSave(chunks[k], task.docId);
          // 进度从 85% 到 100%，按 chunk 比例分配
          task.progress = 85 + ((k + 1) / chunks.length) * 15;
          task.updatedAt = Date.now();
          this.notifyStateChange();
          await new Promise(resolve => setTimeout(resolve, 50));
        }
      } else if (strategy === 'summary-first') {
        // 🔑 Summary-first 策略：3 个采样点，33%/66%/100% 进度
        const sample = [chunks[0], chunks[Math.floor(chunks.length / 2)], chunks[chunks.length - 1]].filter(Boolean);

        for (let s = 0; s < sample.length; s++) {
          await graphExtractor.extractAndSave(sample[s], task.docId);
          // 进度: 85% + (s+1)/3 * 15% = 90%, 95%, 100%
          task.progress = 85 + ((s + 1) / sample.length) * 15;
          task.updatedAt = Date.now();
          this.notifyStateChange();
          await new Promise(resolve => setTimeout(resolve, 50));
        }
      }
    }
  }

  /**
   * 处理消息记忆归档任务
   */
  private async processMemoryTask(task: VectorizationTask) {
    if (!task.sessionId || !task.userContent || !task.aiContent) {
      throw new Error('Memory task missing required fields');
    }

    const settings = useSettingsStore.getState();
    const { provider, modelId } = this.getEmbeddingProvider();
    if (!provider || !modelId) {
      console.warn('[VectorizationQueue] No embedding provider for memory task, skipping');
      return;
    }

    // Ensure session exists in SQLite for FK constraint
    const now = Date.now();
    await db.execute(
      `INSERT OR IGNORE INTO sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)`,
      [task.sessionId, 'Shadow Session', now, now],
    );

    // 格式化对话内容
    const turnText = `User: ${task.userContent}\nAssistant: ${task.aiContent}`;

    // 动态获取 RAG 配置
    const ragConfig = settings.globalRagConfig;

    // 文本分割
    task.status = 'chunking';
    task.progress = 20;
    this.notifyStateChange();

    const splitter = new TrigramTextSplitter({
      chunkSize: ragConfig.memoryChunkSize,
      chunkOverlap: ragConfig.chunkOverlap,
    });
    const chunks = await splitter.splitText(turnText);
    task.totalChunks = chunks.length;

    // 向量化
    task.status = 'vectorizing';
    task.progress = 40;
    this.notifyStateChange();

    const embeddingClient = new EmbeddingClient(provider, modelId);
    const { embeddings, usage } = await embeddingClient.embedDocuments(chunks);

    // Track usage for billing
    let archiveTokens = 0;
    let isEstimated = false;

    if (usage) {
      archiveTokens = usage.total_tokens;
    } else {
      archiveTokens = estimateTokens(chunks.join('\n'));
      isEstimated = true;
    }

    // Report to global stats store
    try {
      const { useTokenStatsStore } = await import('../../store/token-stats-store');
      useTokenStatsStore.getState().trackUsage({
        modelId: modelId || 'embedding-model',
        usage: {
          ragSystem: { count: archiveTokens, isEstimated },
        },
      });
    } catch (e) {
      console.warn('[VectorizationQueue] Stats tracking failed', e);
    }

    // 存储向量
    task.status = 'saving';
    task.progress = 80;
    this.notifyStateChange();

    const vectors = chunks.map((chunk, i) => ({
      sessionId: task.sessionId,
      content: chunk,
      embedding: embeddings[i],
      metadata: { type: 'memory', chunkIndex: i },
      startMessageId: task.userMessageId,
      endMessageId: task.assistantMessageId,
    }));

    await vectorStore.addVectors(vectors);
    console.log(`[VectorizationQueue] Memory archived for session ${task.sessionId} (${vectors.length} chunks)`);
  }

  /**
   * 🔑 处理会话 KG 批量抽取任务
   */
  private async processSessionKGTask(task: VectorizationTask) {
    if (!task.sessionId || !task.kgBatchContent || task.kgBatchContent.length === 0) {
      throw new Error('Session KG task missing required fields');
    }

    task.status = 'extracting';
    task.progress = 10;
    this.notifyStateChange();

    // 合并批量内容
    const combinedText = task.kgBatchContent.join('\n\n---\n\n');
    const totalTurns = task.kgBatchContent.length;

    console.log(`[VectorizationQueue] Processing session KG batch: ${totalTurns} turns, ${combinedText.length} chars`);

    // 调用 graphExtractor
    const { graphExtractor } = require('./graph-extractor');

    task.progress = 30;
    task.updatedAt = Date.now();
    this.notifyStateChange();

    await graphExtractor.extractAndSave(combinedText, undefined, {
      sessionId: task.sessionId,
      messageId: task.kgMessageIds?.[task.kgMessageIds.length - 1], // 使用最后一条消息 ID 关联 UI
    });

    task.progress = 90;
    task.updatedAt = Date.now();
    this.notifyStateChange();

    console.log(`[VectorizationQueue] Session KG batch completed for session ${task.sessionId}`);
  }

  // ==================== 辅助方法 ====================

  /**
   * 获取 Embedding 服务提供者
   */
  private getEmbeddingProvider(): { provider: any; modelId: string | undefined } {
    const apiStore = useApiStore.getState();
    const settings = useSettingsStore.getState();
    const preferredModelId = settings.defaultEmbeddingModel;

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
          modelId = foundModel.id;
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

    return { provider, modelId };
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

  // ==================== 公共 API ====================

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

  // ==================== 持久化方法 ====================

  /**
   * 保存任务到数据库 (检查点持久化)
   */
  private async saveTaskToDb(task: VectorizationTask) {
    const now = Date.now();
    try {
      await db.execute(
        `INSERT OR REPLACE INTO vectorization_tasks 
         (id, type, status, doc_id, doc_title, session_id, user_content, ai_content, 
          user_message_id, assistant_message_id, last_chunk_index, total_chunks, 
          progress, error, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          task.id,
          task.type,
          task.status,
          task.docId || null,
          task.docTitle || null,
          task.sessionId || null,
          task.userContent || null,
          task.aiContent || null,
          task.userMessageId || null,
          task.assistantMessageId || null,
          task.lastChunkIndex || 0,
          task.totalChunks || null,
          task.progress,
          task.error || null,
          task.createdAt,
          now,
        ]
      );
    } catch (e) {
      console.warn('[VectorizationQueue] Failed to persist task:', e);
    }
  }

  /**
   * 从数据库删除已完成的任务
   */
  private async removeTaskFromDb(taskId: string) {
    try {
      await db.execute('DELETE FROM vectorization_tasks WHERE id = ?', [taskId]);
    } catch (e) {
      console.warn('[VectorizationQueue] Failed to remove persisted task:', e);
    }
  }

  /**
   * 标记任务为已中断 (用于心跳检测)
   */
  private async markTaskAsInterrupted(taskId: string) {
    try {
      await db.execute(
        'UPDATE vectorization_tasks SET status = ?, updated_at = ? WHERE id = ?',
        ['interrupted', Date.now(), taskId]
      );
    } catch (e) {
      console.warn('[VectorizationQueue] Failed to mark task interrupted:', e);
    }
  }

  /**
   * 加载中断的任务 (App 唤醒时调用)
   * 🔑 核心恢复逻辑：检测并恢复未完成的任务
   */
  async loadInterruptedTasks(): Promise<VectorizationTask[]> {
    try {
      // 1. 先将长时间未更新的 'processing' 任务标记为 'interrupted'
      const staleThreshold = Date.now() - 30000; // 30秒无心跳视为中断
      await db.execute(
        `UPDATE vectorization_tasks 
         SET status = 'interrupted' 
         WHERE status = 'processing' AND updated_at < ?`,
        [staleThreshold]
      );

      // 2. 加载所有需要恢复的任务
      const result = await db.execute(
        `SELECT * FROM vectorization_tasks 
         WHERE status IN ('pending', 'interrupted') 
         ORDER BY created_at ASC`
      );

      if (!result.rows || result.rows.length === 0) {
        return [];
      }

      const tasks: VectorizationTask[] = [];
      for (let i = 0; i < result.rows.length; i++) {
        const row = result.rows[i] as any;
        tasks.push({
          id: row.id,
          type: row.type as 'document' | 'memory',
          docId: row.doc_id || undefined,
          docTitle: row.doc_title || undefined,
          sessionId: row.session_id || undefined,
          userContent: row.user_content || undefined,
          aiContent: row.ai_content || undefined,
          userMessageId: row.user_message_id || undefined,
          assistantMessageId: row.assistant_message_id || undefined,
          status: row.status as VectorizationTask['status'],
          progress: row.progress || 0,
          error: row.error || undefined,
          createdAt: row.created_at,
          updatedAt: row.updated_at,
          lastChunkIndex: row.last_chunk_index || 0,
          totalChunks: row.total_chunks || undefined,
        });
      }

      console.log(`[VectorizationQueue] Loaded ${tasks.length} interrupted tasks for recovery`);
      return tasks;
    } catch (e) {
      console.error('[VectorizationQueue] Failed to load interrupted tasks:', e);
      return [];
    }
  }

  /**
   * 恢复中断的任务
   * 🔑 将中断的任务加入队列并开始处理
   */
  async resumeInterruptedTasks() {
    const interruptedTasks = await this.loadInterruptedTasks();

    if (interruptedTasks.length === 0) {
      return;
    }

    // 将中断的任务加入队列头部（优先恢复）
    this.queue = [...interruptedTasks, ...this.queue];
    this.notifyStateChange();

    console.log(`[VectorizationQueue] Resuming ${interruptedTasks.length} interrupted tasks`);

    if (!this.isProcessing) {
      this.processNext();
    }
  }

  /**
   * 清理已完成的持久化任务 (定期调用)
   */
  async cleanupCompletedTasks() {
    try {
      await db.execute(
        `DELETE FROM vectorization_tasks WHERE status IN ('completed', 'failed')`
      );
    } catch (e) {
      console.warn('[VectorizationQueue] Failed to cleanup completed tasks:', e);
    }
  }
}

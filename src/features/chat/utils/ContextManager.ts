import { Message, Agent } from '../../../types/chat';
import { createLlmClient } from '../../../lib/llm/factory';
import { estimateTokens } from './token-counter';
import { db } from '../../../lib/db';
import { vectorStore } from '../../../lib/rag/vector-store';
import { useApiStore } from '../../../store/api-store';
import { EmbeddingClient } from '../../../lib/rag/embedding';
import { useSettingsStore } from '../../../store/settings-store';
import { getFullMessageContent } from './message-utils';

export interface ContextConfig {
  maxMessages: number; // Max messages in active window (e.g., 20)
  summarizeThreshold: number; // Trigger summary when unsummarized messages exceed this (e.g., 30)
}

export interface ContextSummary {
  id: string;
  sessionId: string;
  startMessageId: string;
  endMessageId: string;
  summaryContent: string;
  createdAt: number;
  tokenUsage?: number;
}

export class ContextManager {
  static async checkAndSummarize(
    sessionId: string,
    messages: Message[],
    agent?: Agent,
    config?: ContextConfig,
  ): Promise<void> {
    // 从设置存储获取配置，优先使用助手级配置
    const settings = useSettingsStore.getState();
    const ragConfig = agent?.ragConfig || settings.globalRagConfig;

    const finalConfig = config || {
      maxMessages: ragConfig.contextWindow,
      summarizeThreshold: ragConfig.summaryThreshold,
    };
    // 0. Yield thread to ensure UI responsiveness before heavy operations
    await new Promise((resolve) => setTimeout(resolve, 0));

    // 1. Identify unsummarized messages
    try {
      // Fetch existing summaries to determine the "summarized frontier"
      const result = await db.execute(
        'SELECT end_message_id FROM context_summaries WHERE session_id = ? ORDER BY created_at DESC LIMIT 1',
        [sessionId],
      );

      let lastSummarizedMsgId = null;
      if (result.rows) {
        // Safe access for op-sqlite
        const row = (result.rows as any)._array
          ? (result.rows as any)._array[0]
          : (result.rows as any)[0];
        if (row) lastSummarizedMsgId = row.end_message_id;
      }

      // Filter out system messages and find the index of the last summarized message
      const contentMessages = messages.filter((m) => m.role !== 'system');

      let startIndex = 0;
      if (lastSummarizedMsgId) {
        const idx = contentMessages.findIndex((m) => m.id === lastSummarizedMsgId);
        if (idx !== -1) {
          startIndex = idx + 1;
        }
      }

      // Calculate how many unsummarized messages we have pending
      // Design: Keep the last X (activeWindowSize) messages as active context
      // Only messages BEFORE the active window are candidates for summarization
      // Summarize in batches of Y (summaryThreshold) messages
      const activeWindowSize = finalConfig.maxMessages; // X
      const summaryBatchSize = finalConfig.summarizeThreshold || 10; // Y

      // Total content messages in session
      const totalMessages = contentMessages.length;

      // Messages that should remain in active window (not summarized)
      // These are the last X messages
      const activeWindowStart = Math.max(0, totalMessages - activeWindowSize);

      // Unsummarized messages that are OUTSIDE the active window
      // = messages from startIndex to activeWindowStart
      const pendingForSummary = activeWindowStart - startIndex;

      console.log(
        `[ContextManager] 摘要检查: total=${totalMessages}, activeWindow=${activeWindowSize}, startIdx=${startIndex}, activeWindowStart=${activeWindowStart}, pendingForSummary=${pendingForSummary}, batchSize=${summaryBatchSize}`,
      );

      // Only summarize if we have at least Y messages pending outside the active window
      if (pendingForSummary < summaryBatchSize) {
        console.log(`[ContextManager] 跳过摘要：超出活跃窗口的消息 (${pendingForSummary}) 未达到批次大小 (${summaryBatchSize})`);
        return;
      }

      // Select exactly Y messages for this batch (or all pending if less than Y remain)
      const batchEnd = Math.min(startIndex + summaryBatchSize, activeWindowStart);
      const chunk = contentMessages.slice(startIndex, batchEnd);
      if (chunk.length === 0) return;

      console.log(
        `[ContextManager] Summarizing ${chunk.length} messages for session ${sessionId}...`,
      );

      // 2. Generate Summary
      const summaryResult = await this.generateSummary(chunk, ragConfig.summaryPrompt);
      if (!summaryResult) return;

      const { summary, tokenUsage, usageDetails } = summaryResult;

      // 3. Defensive Check: Ensure session exists before inserting (FK constraint)
      const sessionCheck = await db.execute(
        'SELECT id FROM sessions WHERE id = ?',
        [sessionId]
      );
      const sessionExists = sessionCheck.rows &&
        ((sessionCheck.rows as any)._array?.length > 0 || (sessionCheck.rows as any).length > 0);

      if (!sessionExists) {
        console.warn('[ContextManager] Session not found, skipping summary storage:', sessionId);
        return;
      }

      // 4. Store Summary in DB
      const id = `summary_${Date.now()}`;
      await db.execute(
        `INSERT INTO context_summaries (id, session_id, start_message_id, end_message_id, summary_content, created_at, token_usage) VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [
          id,
          sessionId,
          chunk[0].id,
          chunk[chunk.length - 1].id,
          summary,
          Date.now(),
          tokenUsage, // ✅ 保存实际token使用量
        ],
      );

      // 4. Track Global Stats
      try {
        const { useTokenStatsStore } = await import('../../../store/token-stats-store');
        // Use default model ID if not found (summary logic uses provider model)
        const modelId = ragConfig.summaryModel || 'default-summary-model';
        useTokenStatsStore.getState().trackUsage({
          modelId,
          usage: {
            ragSystem: {
              count: tokenUsage,
              isEstimated: usageDetails ? usageDetails.isEstimated : true,
            },
          },
        });
      } catch (e) {
        console.warn('[ContextManager] Failed to track stats:', e);
      }

      // 4. Vectorize Summary for RAG
      await this.vectorizeSummary(sessionId, summary);

      // 5. 清理已摘要的实时归档向量（降低冗余）
      const cleanedCount = await this.cleanupSummarizedMemoryVectors(
        sessionId,
        chunk[0].id,
        chunk[chunk.length - 1].id,
        id,
      );

      console.log(
        `[ContextManager] Summary generated and stored. Cleaned ${cleanedCount} redundant memory vectors.`,
      );
    } catch (e) {
      console.error('[ContextManager] Failed to summarize:', e);
    }
  }

  private static async generateSummary(
    messages: Message[],
    customPrompt?: string,
  ): Promise<{
    summary: string;
    tokenUsage: number;
    usageDetails?: { input: number; output: number; total: number; isEstimated: boolean };
  } | null> {
    const apiStore = useApiStore.getState();
    // Use a lightweight model if possible, or the current active provider
    const provider = apiStore.providers.find((p) => p.enabled);
    if (!provider) {
      throw new Error('No enabled AI provider found.');
    }

    const transcript = messages.map((m) => `${m.role}: ${getFullMessageContent(m)}`).join('\n');

    // 使用用户定义的 Prompt 或回退到默认
    const basePrompt =
      customPrompt ||
      'Summarize the following conversation segment concisely, capturing key facts, decisions, and context. Do not lose important details.';
    const prompt = `${basePrompt}\n\n${transcript}`;

    try {
      // ✅ Fix: Use configured Summary Model (Fast Model) from Settings Store
      const settings = useSettingsStore.getState();
      const configuredModelId = settings.defaultSummaryModel;

      // Default fallback logic if no specific summary model is set
      const fallbackModel = provider.models.find((m) => m.enabled && (!m.type || m.type === 'chat')) || provider.models[0];

      // Effective Model ID
      const summaryModelName = configuredModelId || fallbackModel?.id || 'gpt-3.5-turbo';

      const config = {
        ...provider,
        provider: provider.type,
        id: summaryModelName, // CORRECT: override provider.id with model.id for the factory
        modelName: summaryModelName,
        apiKey: provider.apiKey,
        baseUrl: provider.baseUrl,
      };

      const client = createLlmClient(config as any);
      // Capture API usage if available
      let apiUsage: { input: number; output: number; total: number } | undefined;
      let fullResponse = '';

      await new Promise<void>((resolve, reject) => {
        client
          .streamChat(
            [{ role: 'user', content: prompt }],
            (chunk) => {
              if (chunk.content) fullResponse += chunk.content;
              if (chunk.usage) apiUsage = chunk.usage;
            },
            (err) => reject(err),
          )
          .then(() => resolve())
          .catch(reject);
      });

      // ✅ 计算/获取 token 使用量
      let tokenUsage = 0;
      let usageDetails:
        | { input: number; output: number; total: number; isEstimated: boolean }
        | undefined;

      if (apiUsage) {
        tokenUsage = apiUsage.total;
        usageDetails = { ...apiUsage, isEstimated: false };
      } else {
        const { estimateTokens } = await import('./token-counter');
        const inputTokens = estimateTokens(prompt);
        const outputTokens = estimateTokens(fullResponse);
        tokenUsage = inputTokens + outputTokens;
        usageDetails = {
          input: inputTokens,
          output: outputTokens,
          total: tokenUsage,
          isEstimated: true,
        };
      }

      return {
        summary: fullResponse,
        tokenUsage: tokenUsage,
        usageDetails, // Return detailed usage for stats store
      };
    } catch (e) {
      console.error('[ContextManager] LLM call failed:', e);
      throw e; // Re-throw to let UI handle it
    }
  }

  private static async vectorizeSummary(sessionId: string, summary: string) {
    // Yield thread before network/db heavy lifting
    await new Promise((resolve) => setTimeout(resolve, 0));

    const apiStore = useApiStore.getState();
    let provider = apiStore.providers.find(
      (p) =>
        p.enabled &&
        p.type === 'openai' &&
        p.models.some((m) => m.enabled && m.type === 'embedding'),
    );

    if (!provider) {
      provider = apiStore.providers.find(
        (p) =>
          p.enabled &&
          (p.type === 'siliconflow' ||
            p.type === 'deepseek' ||
            p.type === 'moonshot' ||
            p.type === 'zhipu') &&
          p.models.some((m) => m.enabled && m.type === 'embedding'),
      );
    }

    if (!provider) {
      provider = apiStore.providers.find(
        (p) => p.enabled && (p.type === 'gemini' || p.type === 'google'),
      );
    }

    if (!provider) return;

    const embeddingModelConfig = provider.models.find((m) => m.enabled && m.type === 'embedding');
    const modelId =
      embeddingModelConfig?.id ||
      (provider.type === 'openai' ? 'text-embedding-3-small' : undefined);

    try {
      const embeddingClient = new EmbeddingClient(provider, modelId);
      const embedding = await embeddingClient.embedQuery(summary);

      await vectorStore.addVectors([
        {
          sessionId,
          content: summary,
          embedding: embedding.embedding,
          metadata: { type: 'summary' },
        },
      ]);
    } catch (e) {
      console.error('[ContextManager] Vectorization failed:', e);
    }
  }

  /**
   * 清理已被摘要覆盖的实时归档向量
   * 通过消息 ID 精确删除，降低向量冗余率
   */
  private static async cleanupSummarizedMemoryVectors(
    sessionId: string,
    startMessageId: string,
    endMessageId: string,
    summaryId: string,
  ): Promise<number> {
    try {
      // 删除指定消息范围内的 type='memory' 向量
      // 使用新增的 start_message_id 和 end_message_id 字段进行精确匹配
      const result = await db.execute(
        `
                DELETE FROM vectors 
                WHERE session_id = ? 
                  AND json_extract(metadata, '$.type') = 'memory'
                  AND start_message_id >= ? 
                  AND end_message_id <= ?
            `,
        [sessionId, startMessageId, endMessageId],
      );

      const deletedCount = result.rowsAffected || 0;

      if (deletedCount > 0) {
        console.log(
          `[ContextManager] Cleaned up ${deletedCount} memory vectors for summary ${summaryId}`,
        );
      }

      return deletedCount;
    } catch (error) {
      console.error('[ContextManager] Failed to cleanup memory vectors:', error);
      // 非致命错误，不抛出异常
      return 0;
    }
  }

  static async deleteSummary(id: string): Promise<void> {
    try {
      await db.execute('DELETE FROM context_summaries WHERE id = ?', [id]);
      // Attempt to remove related vector data (rough cleanup)
      await db.execute('DELETE FROM vectors WHERE metadata LIKE ?', [`%"type":"summary"%${id}%`]);
      console.log(`[ContextManager] Deleted summary ${id}`);
    } catch (e) {
      console.error('[ContextManager] Failed to delete summary:', e);
      throw e;
    }
  }

  static async getRelevantContext(sessionId: string): Promise<string[]> {
    // Retrieve relevant summaries
    try {
      const result = await db.execute(
        'SELECT summary_content FROM context_summaries WHERE session_id = ? ORDER BY created_at DESC LIMIT 1',
        [sessionId],
      );

      if (result.rows) {
        // Safe access
        const row = (result.rows as any)._array
          ? (result.rows as any)._array[0]
          : (result.rows as any)[0];
        if (row) return [row.summary_content];
      }
    } catch (e) {
      console.error('[ContextManager] Failed to get context:', e);
    }
    return [];
  }

  static trimContext(messages: Message[], maxMessages: number = 10): Message[] {
    // 使用动态配置的窗口大小，回退到 10
    if (messages.length <= maxMessages) return messages;
    return messages.slice(-maxMessages);
  }
}

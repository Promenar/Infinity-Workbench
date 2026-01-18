import { db } from '../db';
import { generateId } from '../utils/id-generator';

export interface VectorRecord {
  id: string;
  docId?: string;
  sessionId?: string;
  content: string;
  embedding: number[]; // stored as blob, handled as array here
  metadata?: Record<string, any>;
  startMessageId?: string; // 向量覆盖的起始消息ID
  endMessageId?: string; // 向量覆盖的结束消息ID
  createdAt: number;
}

export interface SearchResult extends VectorRecord {
  similarity: number;
  originalSimilarity?: number; // 🚨 新增：原始相似度分数
}

export class VectorStore {
  /**
   * Convert float array to Buffer/Blob for SQLite storage
   */
  private toBlob(embedding: number[]): ArrayBuffer {
    return new Float32Array(embedding).buffer;
  }

  /**
   * Convert Blob back to float array
   */
  private fromBlob(blob: any): number[] {
    // op-sqlite returns blob as ArrayBuffer or keys depending on platform/config
    // Safest is to handle ArrayBuffer
    return Array.from(new Float32Array(blob));
  }

  async addVectors(vectors: Omit<VectorRecord, 'id' | 'createdAt'>[]): Promise<void> {
    try {
      await db.execute('BEGIN TRANSACTION');
      for (const vec of vectors) {
        const id = generateId();
        const createdAt = Date.now();
        const blob = this.toBlob(vec.embedding);
        const metadataStr = vec.metadata ? JSON.stringify(vec.metadata) : null;

        await db.execute(
          'INSERT INTO vectors (id, doc_id, session_id, content, embedding, metadata, start_message_id, end_message_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
          [
            id,
            vec.docId || null,
            vec.sessionId || null,
            vec.content,
            blob,
            metadataStr,
            vec.startMessageId || null,
            vec.endMessageId || null,
            createdAt,
          ],
        );
      }
      await db.execute('COMMIT');
    } catch (e) {
      await db.execute('ROLLBACK');
      throw e;
    } finally {
      // Optional: finalize statement if library requires it, op-sqlite manages this usually
    }
  }

  /**
   * Brute-force Cosine Similarity Search
   * For <10k-50k vectors, this is surprisingly fast in JS on modern devices.
   * 1. Fetch all candidate vectors (filter by session/doc/type)
   * 2. Calculate similarity in JS loop
   * 3. Sort and slice
   */
  async search(
    queryEmbedding: number[],
    options: {
      limit?: number;
      threshold?: number;
      filter?: { docId?: string; docIds?: string[]; sessionId?: string; type?: string };
    } = {},
  ): Promise<SearchResult[]> {
    const Limit = options.limit || 5;
    const Threshold = options.threshold || 0.7;

    // Build Query
    let sql = 'SELECT * FROM vectors';
    const params: any[] = [];
    const conditions: string[] = [];

    // 🔑 核心改进：支持多文档 ID 过滤 (SQL 下沉)
    if (options.filter?.docIds && options.filter.docIds.length > 0) {
      const placeholders = options.filter.docIds.map(() => '?').join(',');
      conditions.push(`doc_id IN (${placeholders})`);
      params.push(...options.filter.docIds);
    } else if (options.filter?.docId) {
      conditions.push('doc_id = ?');
      params.push(options.filter.docId);
    }

    if (options.filter?.sessionId) {
      conditions.push('session_id = ?');
      params.push(options.filter.sessionId);
    }
    if (options.filter?.type) {
      // Check metadata json -> this is slower, avoid if possible or add index
      // Simple string check for now
      conditions.push(`json_extract(metadata, '$.type') = ?`);
      params.push(options.filter.type);
    }

    if (conditions.length > 0) {
      sql += ' WHERE ' + conditions.join(' AND ');
    }

    const results = await db.execute(sql, params);
    if (!results.rows) return [];

    // JS Calculation
    const candidates: SearchResult[] = [];
    // Pre-calculate query magnitude for optimization
    const queryMag = Math.sqrt(queryEmbedding.reduce((sum, val) => sum + val * val, 0));
    let maxSimilarity = 0;
    let dimensionMismatchCount = 0;

    // @ts-ignore
    for (let i = 0; i < results.rows.length; i++) {
      // 🛡️ 强制防阻塞：每计算 100 个向量，主动让出主线程 5ms
      if (i % 100 === 0) await new Promise(resolve => setTimeout(resolve, 5));

      // @ts-ignore
      const row = results.rows[i];
      const vec = this.fromBlob(row.embedding);

      // Dimension check
      if (vec.length !== queryEmbedding.length) {
        if (dimensionMismatchCount === 0) {
          console.error(`[VectorStore] Dimension mismatch! Query: ${queryEmbedding.length}, Stored: ${vec.length} (ID: ${row.id})`);
        }
        dimensionMismatchCount++;
        continue; // Skip mismatched vectors
      }

      const similarity = this.cosineSimilarity(queryEmbedding, vec, queryMag);
      if (similarity > maxSimilarity) maxSimilarity = similarity;

      if (similarity >= Threshold) {
        candidates.push({
          id: row.id as string,
          docId: row.doc_id as string | undefined,
          sessionId: row.session_id as string | undefined,
          content: row.content as string,
          embedding: vec,
          metadata: row.metadata ? JSON.parse(row.metadata as string) : undefined,
          createdAt: row.created_at as number,
          similarity,
        });
      }
    }

    // Diagnostics
    if (candidates.length === 0 && dimensionMismatchCount > 0) {
      console.error(`[VectorStore] CRITICAL: Search found 0 candidates due to dimension mismatch in ${dimensionMismatchCount} vectors.`);
      // Optional: Notify via Toast if in Dev mode or critical
      const { emitToast } = require('../utils/toast-emitter'); // Lazy import to avoid cycle
      emitToast(`检索失败: 向量维度不匹配 (Q:${queryEmbedding.length}, DB:${dimensionMismatchCount}个不符)`, 'error');
    }

    console.log(`[VectorStore] Search: ${results.rows?.length || 0} vectors, Query dim: ${queryEmbedding.length}, Max similarity: ${maxSimilarity.toFixed(4)}, Above threshold (${Threshold}): ${candidates.length}`);
    if (dimensionMismatchCount > 0) {
      console.warn(`[VectorStore] ⚠️ ${dimensionMismatchCount} vectors skipped due to dimension mismatch!`);
    }

    // Sort desc
    candidates.sort((a, b) => b.similarity - a.similarity);

    return candidates.slice(0, Limit);
  }

  private cosineSimilarity(vecA: number[], vecB: number[], magA?: number): number {
    if (vecA.length !== vecB.length) return 0;

    let dot = 0;
    let magB = 0;

    for (let i = 0; i < vecA.length; i++) {
      dot += vecA[i] * vecB[i];
      magB += vecB[i] * vecB[i];
    }

    const mA = magA || Math.sqrt(vecA.reduce((sum, v) => sum + v * v, 0));
    magB = Math.sqrt(magB);

    if (mA === 0 || magB === 0) return 0;
    return dot / (mA * magB);
  }

  async deleteDocumentVectors(docId: string) {
    await db.execute('DELETE FROM vectors WHERE doc_id = ?', [docId]);
  }

  async clearSessionMemory(sessionId: string) {
    await db.execute('DELETE FROM vectors WHERE session_id = ?', [sessionId]);
  }

  /**
   * Clear ALL vectors in the database
   * Use with EXTREME CAUTION - this deletes ALL vector data
   * Note: Knowledge Graph is now cleared separately via clearKnowledgeGraph()
   */
  async clearAllVectors() {
    await db.execute('DELETE FROM vectors');
    // 🛡️ 强制 SSOT: 重置所有文档的向量化状态和计数
    await db.execute('UPDATE documents SET vectorized = 0, vector_count = 0');
  }

  /**
   * Clear ALL Knowledge Graph data (nodes and edges)
   * Use with EXTREME CAUTION - this deletes ALL graph data
   */
  async clearKnowledgeGraph() {
    await db.execute('DELETE FROM kg_edges'); // 先删边，再删节点（遵循外键约束）
    await db.execute('DELETE FROM kg_nodes');
  }

  /**
   * Get Knowledge Graph statistics
   */
  async getKnowledgeGraphStats(): Promise<{ nodeCount: number; edgeCount: number }> {
    const nodesResult = await db.execute('SELECT COUNT(*) as count FROM kg_nodes');
    const edgesResult = await db.execute('SELECT COUNT(*) as count FROM kg_edges');
    return {
      nodeCount: (nodesResult.rows?.[0] as any)?.count || 0,
      edgeCount: (edgesResult.rows?.[0] as any)?.count || 0,
    };
  }

  /**
   * Delete all vector memories that belong to sessions NOT in the active list.
   * This cleans up "ghost" data from previously deleted sessions.
   */
  async pruneOrphanSessions(activeSessionIds: string[]) {
    if (activeSessionIds.length === 0) {
      // If no active sessions, delete ALL memory vectors
      await db.execute(
        "DELETE FROM vectors WHERE session_id IS NOT NULL AND json_extract(metadata, '$.type') = 'memory'",
      );
      return;
    }

    // SQLite limit is usually 999 variables, so we should be careful.
    // If list is large, we do it in chunks or use a temporary table (overkill here).
    // Let's assume < 500 sessions.

    const placeholders = activeSessionIds.map(() => '?').join(',');

    // Delete vectors that HAVE a session_id AND that session_id is NOT in the active list
    await db.execute(
      `DELETE FROM vectors WHERE session_id IS NOT NULL AND session_id NOT IN (${placeholders})`,
      activeSessionIds,
    );

    // Also clean up the sessions table (used for stats and foreign keys)
    // Ensure we don't accidentally delete super_assistant if it's not in the list (though it should be)
    await db.execute(
      `DELETE FROM sessions WHERE id IS NOT NULL AND id != 'super_assistant' AND id NOT IN (${placeholders})`,
      activeSessionIds,
    );
  }

  /**
   * 一键清理冗余的记忆向量
   * 删除已有摘要覆盖的实时归档向量（通过 message_id 精确匹配）
   */
  async cleanupRedundantMemoryVectors(): Promise<{
    checked: number;
    deleted: number;
  }> {
    try {
      // 1. 获取所有摘要记录
      const summaries = await db.execute(`
                SELECT id, session_id, start_message_id, end_message_id 
                FROM context_summaries 
                ORDER BY created_at ASC
            `);

      if (!summaries.rows || summaries.rows.length === 0) {
        return { checked: 0, deleted: 0 };
      }

      let totalDeleted = 0;

      // 2. 遍历每个摘要，删除其覆盖范围内的记忆向量
      for (let i = 0; i < summaries.rows.length; i++) {
        const summary = summaries.rows[i];

        const result = await db.execute(
          `
                    DELETE FROM vectors 
                    WHERE session_id = ? 
                      AND json_extract(metadata, '$.type') = 'memory'
                      AND start_message_id >= ? 
                      AND end_message_id <= ?
                `,
          [summary.session_id, summary.start_message_id, summary.end_message_id],
        );

        totalDeleted += result.rowsAffected || 0;
      }

      console.log(
        `[VectorStore] Cleanup complete: checked ${summaries.rows.length} summaries, deleted ${totalDeleted} redundant vectors`,
      );

      return {
        checked: summaries.rows.length,
        deleted: totalDeleted,
      };
    } catch (error) {
      console.error('[VectorStore] Cleanup failed:', error);
      throw error;
    }
  }
}

export const vectorStore = new VectorStore();

import { db } from '../db';
import { generateId } from '../utils/id-generator';
import { searchVectors, isNativeModuleAvailable } from '../../native/VectorSearch';

export interface VectorRecord {
  id: string;
  docId?: string;
  sessionId?: string;
  content: string;
  embedding: number[];
  metadata?: Record<string, any>;
  startMessageId?: string;
  endMessageId?: string;
  createdAt: number;
}

export interface SearchResult extends VectorRecord {
  similarity: number;
  originalSimilarity?: number;
}

export class VectorStore {
  private toBlob(embedding: number[]): ArrayBuffer {
    return new Float32Array(embedding).buffer;
  }

  private fromBlob(blob: any): number[] {
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
    }
  }

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

    let sql = 'SELECT * FROM vectors';
    const params: any[] = [];
    const conditions: string[] = [];

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
      conditions.push(`json_extract(metadata, '$.type') = ?`);
      params.push(options.filter.type);
    }

    if (conditions.length > 0) {
      sql += ' WHERE ' + conditions.join(' AND ');
    }

    const results = await db.execute(sql, params);
    if (!results.rows) return [];

    // 尝试使用原生模块
    if (isNativeModuleAvailable() && results.rows.length > 0) {
      try {
        return await this.searchNative(queryEmbedding, results.rows, Threshold, Limit);
      } catch (e) {
        console.warn('[VectorStore] Native search failed, falling back to JS:', e);
      }
    }

    // 降级到 JS 实现
    return this.searchJS(queryEmbedding, results.rows, Threshold, Limit);
  }

  private async searchNative(
    queryEmbedding: number[],
    rows: any[],
    threshold: number,
    limit: number
  ): Promise<SearchResult[]> {
    const queryTyped = new Float32Array(queryEmbedding);
    const candidates: Array<{ id: string; embedding: Float32Array }> = [];
    const rowDataMap = new Map<string, any>();

    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const vec = new Float32Array(this.fromBlob(row.embedding));
      
      if (vec.length !== queryEmbedding.length) {
        continue;
      }

      candidates.push({
        id: row.id as string,
        embedding: vec,
      });
      rowDataMap.set(row.id as string, row);
    }

    if (candidates.length === 0) {
      return [];
    }

    const nativeResults = await searchVectors(queryTyped, candidates, threshold, limit);

    return nativeResults.map(r => {
      const row = rowDataMap.get(r.id)!;
      return {
        id: r.id,
        docId: row.doc_id as string | undefined,
        sessionId: row.session_id as string | undefined,
        content: row.content as string,
        embedding: this.fromBlob(row.embedding),
        metadata: row.metadata ? JSON.parse(row.metadata as string) : undefined,
        createdAt: row.created_at as number,
        similarity: r.similarity,
      };
    });
  }

  private searchJS(
    queryEmbedding: number[],
    rows: any[],
    threshold: number,
    limit: number
  ): SearchResult[] {
    const candidates: SearchResult[] = [];
    const queryMag = Math.sqrt(queryEmbedding.reduce((sum, val) => sum + val * val, 0));
    let maxSimilarity = 0;
    let dimensionMismatchCount = 0;

    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const vec = this.fromBlob(row.embedding);

      if (vec.length !== queryEmbedding.length) {
        if (dimensionMismatchCount === 0) {
          console.error(`[VectorStore] Dimension mismatch! Query: ${queryEmbedding.length}, Stored: ${vec.length} (ID: ${row.id})`);
        }
        dimensionMismatchCount++;
        continue;
      }

      const similarity = this.cosineSimilarity(queryEmbedding, vec, queryMag);
      if (similarity > maxSimilarity) maxSimilarity = similarity;

      if (similarity >= threshold) {
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

    if (candidates.length === 0 && dimensionMismatchCount > 0) {
      console.error(`[VectorStore] CRITICAL: Search found 0 candidates due to dimension mismatch in ${dimensionMismatchCount} vectors.`);
      const { emitToast } = require('../utils/toast-emitter');
      emitToast(`检索失败: 向量维度不匹配 (Q:${queryEmbedding.length}, DB:${dimensionMismatchCount}个不符)`, 'error');
    }

    console.log(`[VectorStore] Search: ${rows.length} vectors, Query dim: ${queryEmbedding.length}, Max similarity: ${maxSimilarity.toFixed(4)}, Above threshold (${threshold}): ${candidates.length}`);
    if (dimensionMismatchCount > 0) {
      console.warn(`[VectorStore] ⚠️ ${dimensionMismatchCount} vectors skipped due to dimension mismatch!`);
    }

    candidates.sort((a, b) => b.similarity - a.similarity);

    return candidates.slice(0, limit);
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

  async clearAllVectors() {
    await db.execute('DELETE FROM vectors');
    await db.execute('UPDATE documents SET vectorized = 0, vector_count = 0');
  }

  async clearKnowledgeGraph() {
    await db.execute('DELETE FROM kg_edges');
    await db.execute('DELETE FROM kg_nodes');
  }

  async getKnowledgeGraphStats(): Promise<{ nodeCount: number; edgeCount: number }> {
    const nodesResult = await db.execute('SELECT COUNT(*) as count FROM kg_nodes');
    const edgesResult = await db.execute('SELECT COUNT(*) as count FROM kg_edges');
    return {
      nodeCount: (nodesResult.rows?.[0] as any)?.count || 0,
      edgeCount: (edgesResult.rows?.[0] as any)?.count || 0,
    };
  }

  async pruneOrphanSessions(activeSessionIds: string[]) {
    if (activeSessionIds.length === 0) {
      await db.execute(
        "DELETE FROM vectors WHERE session_id IS NOT NULL AND json_extract(metadata, '$.type') = 'memory'",
      );
      // 清理知识图谱孤立数据
      await db.execute('DELETE FROM kg_edges WHERE session_id IS NOT NULL');
      await db.execute('DELETE FROM kg_nodes WHERE session_id IS NOT NULL');
      return;
    }

    const placeholders = activeSessionIds.map(() => '?').join(',');

    await db.execute(
      `DELETE FROM vectors WHERE session_id IS NOT NULL AND session_id NOT IN (${placeholders})`,
      activeSessionIds,
    );

    await db.execute(
      `DELETE FROM sessions WHERE id IS NOT NULL AND id != 'super_assistant' AND id NOT IN (${placeholders})`,
      activeSessionIds,
    );

    // 清理知识图谱孤立数据（session_id 不在活跃会话列表中）
    await db.execute(
      `DELETE FROM kg_edges WHERE session_id IS NOT NULL AND session_id NOT IN (${placeholders})`,
      activeSessionIds,
    );

    // 清理孤立节点（没有边连接的节点）
    await db.execute(`
      DELETE FROM kg_nodes 
      WHERE id NOT IN (SELECT source_id FROM kg_edges) 
      AND id NOT IN (SELECT target_id FROM kg_edges)
    `);
  }

  async pruneOrphanDocumentKG(activeDocIds: string[]): Promise<{ edgesDeleted: number; nodesDeleted: number }> {
    const placeholders = activeDocIds.map(() => '?').join(',');

    let edgesDeleted = 0;
    let nodesDeleted = 0;

    if (activeDocIds.length === 0) {
      const edgesResult = await db.execute('DELETE FROM kg_edges WHERE doc_id IS NOT NULL');
      edgesDeleted = edgesResult.rowsAffected || 0;
    } else {
      const edgesResult = await db.execute(
        `DELETE FROM kg_edges WHERE doc_id IS NOT NULL AND doc_id NOT IN (${placeholders})`,
        activeDocIds,
      );
      edgesDeleted = edgesResult.rowsAffected || 0;
    }

    const nodesResult = await db.execute(`
      DELETE FROM kg_nodes 
      WHERE id NOT IN (SELECT source_id FROM kg_edges) 
      AND id NOT IN (SELECT target_id FROM kg_edges)
    `);
    nodesDeleted = nodesResult.rowsAffected || 0;

    return { edgesDeleted, nodesDeleted };
  }

  async cleanupRedundantMemoryVectors(): Promise<{
    checked: number;
    deleted: number;
  }> {
    try {
      const summaries = await db.execute(`
                SELECT id, session_id, start_message_id, end_message_id 
                FROM context_summaries 
                ORDER BY created_at ASC
            `);

      if (!summaries.rows || summaries.rows.length === 0) {
        return { checked: 0, deleted: 0 };
      }

      let totalDeleted = 0;

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

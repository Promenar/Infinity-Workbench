import { db } from '../db';
import { SearchResult, VectorRecord } from './vector-store';

/**
 * 简单的关键词搜索实现 (基于 SQLite FTS5 或 LIKE)
 * 由于 React Native SQLite 环境限制，我们暂时使用 LIKE 进行简单的关键词匹配。
 * 如果 op-sqlite 构建包含 FTS5，可以升级为 FTS。
 */
export class KeywordSearch {
  /**
   * 执行关键词搜索
   * @param query 用户查询
   * @param limit 限制数量
   * @param options 过滤选项
   */
  async search(
    query: string,
    limit: number = 5,
    options: {
      sessionId?: string;
      docIds?: Set<string>;
      excludeDocs?: boolean;
    } = {},
  ): Promise<SearchResult[]> {
    // 1. 尝试使用 FTS5 全文索引进行检索
    if (!query || query.trim().length === 0) return [];

    try {
      // 使用 FTS5 MATCH 查询（比 LIKE 快得多）
      let sql = `
                SELECT v.* 
                FROM vectors v
                INNER JOIN vectors_fts fts ON v.rowid = fts.rowid
                WHERE fts.content MATCH ?
            `;
      const params: any[] = [query]; // FTS5 自动分词和匹配
      const conditions: string[] = [];

      // 过滤条件
      if (options.sessionId) {
        conditions.push(`v.session_id = ?`);
        params.push(options.sessionId);
      }

      // Doc filtering
      let memoryDocFilter: Set<string> | null = null;
      if (options.excludeDocs) {
        // Explicitly exclude all docs
        conditions.push(`v.doc_id IS NULL`);
      } else if (options.docIds && options.docIds.size > 0) {
        if (options.docIds.size < 100) {
          const placeholders = Array.from(options.docIds)
            .map(() => '?')
            .join(',');
          conditions.push(`v.doc_id IN (${placeholders})`);
          params.push(...Array.from(options.docIds));
        } else {
          memoryDocFilter = options.docIds;
          // Don't add SQL condition
          conditions.push(`v.doc_id IS NOT NULL`);
        }
      }

      if (conditions.length > 0) {
        sql += ' AND ' + conditions.join(' AND ');
      }

      // FTS5 Standard Sort (rank)
      sql += ` ORDER BY rank LIMIT ${limit * 5}`;

      const result = await db.execute(sql, params);
      if (!result.rows || result.rows.length === 0) return [];

      const rows = (result.rows as any)._array || (result.rows as any);
      let candidates: SearchResult[] = rows.map((row: any) => ({
        id: row.id,
        docId: row.doc_id,
        sessionId: row.session_id,
        content: row.content,
        embedding: [], // Don't need embedding for keyword result
        metadata: row.metadata ? JSON.parse(row.metadata) : undefined,
        createdAt: row.created_at,
        similarity: 1.0, // FTS5 返回的已经是按相关性排序，我们用固定值或者 bm25 rank
      }));

      // Memory Filter
      if (memoryDocFilter) {
        candidates = candidates.filter((c) => c.docId && memoryDocFilter!.has(c.docId));
      }

      // FTS5 bm25() 返回的是负数（越接近 0 越相关），我们需要归一化
      // 简化起见，FTS5 已按相关性排序，我们只需取前 N 个
      return candidates.slice(0, limit);
    } catch (e) {
      console.error('[KeywordSearch] FTS5 search failed, falling back to LIKE:', e);

      // 降级：使用原有的 LIKE 查询（如果 FTS5 不可用）
      return this.fallbackLikeSearch(query, limit, options);
    }
  }

  /**
   * 降级方案：使用 LIKE 查询（当 FTS5 不可用时）
   */
  private async fallbackLikeSearch(
    query: string,
    limit: number,
    options: {
      sessionId?: string;
      docIds?: Set<string>;
    },
  ): Promise<SearchResult[]> {
    // 1. 简单的分词 (按空格拆分)
    const keywords = query.split(/\s+/).filter((k) => k.length > 1);
    if (keywords.length === 0) return [];

    // 2. 构建 SQL 查询
    let sql = `SELECT * FROM vectors WHERE `;
    const params: any[] = [];
    const conditions: string[] = [];

    // 关键词匹配 (OR 逻辑)
    const keywordConditions: string[] = [];
    keywords.forEach((kw) => {
      keywordConditions.push(`content LIKE ?`);
      params.push(`%${kw}%`);
    });

    if (keywordConditions.length > 0) {
      conditions.push(`(${keywordConditions.join(' OR ')})`);
    }

    // 过滤条件
    if (options.sessionId) {
      conditions.push(`session_id = ?`);
      params.push(options.sessionId);
    }

    let memoryDocFilter: Set<string> | null = null;
    if (options.docIds && options.docIds.size > 0) {
      if (options.docIds.size < 100) {
        const placeholders = Array.from(options.docIds)
          .map(() => '?')
          .join(',');
        conditions.push(`doc_id IN (${placeholders})`);
        params.push(...Array.from(options.docIds));
      } else {
        memoryDocFilter = options.docIds;
        conditions.push(`doc_id IS NOT NULL`);
      }
    }

    if (conditions.length === 0) return [];

    sql += conditions.join(' AND ');
    sql += ` LIMIT ${limit * 5}`;

    try {
      const result = await db.execute(sql, params);
      if (!result.rows || result.rows.length === 0) return [];

      const rows = (result.rows as any)._array || (result.rows as any);
      let candidates: SearchResult[] = rows.map((row: any) => ({
        id: row.id,
        docId: row.doc_id,
        sessionId: row.session_id,
        content: row.content,
        embedding: [],
        metadata: row.metadata ? JSON.parse(row.metadata) : undefined,
        createdAt: row.created_at,
        similarity: 0,
      }));

      // Memory Filter
      if (memoryDocFilter) {
        candidates = candidates.filter((c) => c.docId && memoryDocFilter!.has(c.docId));
      }

      // Scoring (count of keywords matched)
      candidates.forEach((c) => {
        let score = 0;
        const contentLower = c.content.toLowerCase();
        keywords.forEach((kw) => {
          if (contentLower.includes(kw.toLowerCase())) score += 1.0;
        });
        c.similarity = score;
      });

      // Sort by score
      candidates.sort((a, b) => b.similarity - a.similarity);

      return candidates.slice(0, limit);
    } catch (e) {
      console.error('[KeywordSearch] Fallback search failed:', e);
      return [];
    }
  }
}

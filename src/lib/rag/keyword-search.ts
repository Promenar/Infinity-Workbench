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
    async search(query: string, limit: number = 5, options: {
        sessionId?: string;
        docIds?: Set<string>;
    } = {}): Promise<SearchResult[]> {
        // 1. 简单的分词 (按空格拆分)
        const keywords = query.split(/\s+/).filter(k => k.length > 1);
        if (keywords.length === 0) return [];

        // 2. 构建 SQL 查询
        // 我们搜索 vectors 表的 content 字段 (假设 content 是明文存储的)
        let sql = `SELECT * FROM vectors WHERE `;
        const params: any[] = [];
        const conditions: string[] = [];

        // 关键词匹配 (AND 逻辑 for high precision, OR for high recall? Let's use OR for recall since vector is precision)
        // Actually, for Keyword Search, we usually want specificity.
        // Let's use simple OR for now to boost recall.
        const keywordConditions: string[] = [];
        keywords.forEach(kw => {
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

        // Doc filtering is tricky with LIKE if we have many docs.
        // We can filter in memory or use IN clause if list is small.
        // If docIds is provided, we only search those docs. 
        // Note: docIds set can be large.
        // Since sqlite limit is ~999 args, we might skip SQL filtering for docs if too many, and filter in memory.
        let memoryDocFilter: Set<string> | null = null;
        if (options.docIds && options.docIds.size > 0) {
            if (options.docIds.size < 100) {
                const placeholders = Array.from(options.docIds).map(() => '?').join(',');
                conditions.push(`doc_id IN (${placeholders})`);
                params.push(...Array.from(options.docIds));
            } else {
                memoryDocFilter = options.docIds;
                // Don't add SQL condition
                conditions.push(`doc_id IS NOT NULL`);
            }
        }

        if (conditions.length === 0) return [];

        sql += conditions.join(' AND ');

        // Add limit (fetch more if we need memory filtering)
        const fetchLimit = memoryDocFilter ? limit * 5 : limit;
        sql += ` LIMIT ${fetchLimit}`;

        try {
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
                similarity: 0 // Placeholder, will calculate score
            }));

            // Memory Filter
            if (memoryDocFilter) {
                candidates = candidates.filter(c => c.docId && memoryDocFilter!.has(c.docId));
            }

            // Scoring (BM25-ish simplistic)
            // Score = count of keywords matched
            candidates.forEach(c => {
                let score = 0;
                const contentLower = c.content.toLowerCase();
                keywords.forEach(kw => {
                    if (contentLower.includes(kw.toLowerCase())) score += 1.0;
                });
                c.similarity = score; // Normalize? No need, distinct from cosine.
            });

            // Sort by score
            candidates.sort((a, b) => b.similarity - a.similarity);

            return candidates.slice(0, limit);

        } catch (e) {
            console.error('[KeywordSearch] Search failed:', e);
            return [];
        }
    }
}

import { db } from '../db';

export interface VectorStats {
  total: number;
  byType: {
    memory: number;
    summary: number;
    doc: number;
  };
  bySession: Array<{
    sessionId: string;
    count: number;
  }>;
  redundancyRate: number; // 冗余率
  storageSize: number; // 估算存储大小(MB)
}

export class VectorStatsService {
  /**
   * 获取向量库统计信息
   */
  static async getStats(): Promise<VectorStats> {
    // 1. 总向量数
    const totalResult = await db.execute('SELECT COUNT(*) as count FROM vectors');
    const total = Number(totalResult.rows?.[0]?.count) || 0;

    // 2. 按类型统计
    const typeResult = await db.execute(`
            SELECT 
                json_extract(metadata, '$.type') as type,
                COUNT(*) as count
            FROM vectors
            WHERE metadata IS NOT NULL
            GROUP BY type
        `);

    const byType = {
      memory: 0,
      summary: 0,
      doc: 0,
    };

    if (typeResult.rows) {
      for (let i = 0; i < typeResult.rows.length; i++) {
        const row = typeResult.rows[i];
        const type = row.type as string;
        const count = row.count as number;

        if (type in byType) {
          byType[type as keyof typeof byType] = count;
        }
      }
    }

    // 3. 按会话统计（Top 10）
    const sessionResult = await db.execute(`
            SELECT session_id, COUNT(*) as count
            FROM vectors
            WHERE session_id IS NOT NULL
            GROUP BY session_id
            ORDER BY count DESC
            LIMIT 10
        `);

    const bySession: Array<{ sessionId: string; count: number }> = [];
    if (sessionResult.rows) {
      for (let i = 0; i < sessionResult.rows.length; i++) {
        bySession.push({
          sessionId: sessionResult.rows[i].session_id as string,
          count: sessionResult.rows[i].count as number,
        });
      }
    }

    // 4. 计算冗余率（精确匹配）
    // 冗余定义：类型为 'memory' 且其消息 ID 落在该会话已有的任何摘要范围内的向量
    const redundancyResult = await db.execute(`
            SELECT COUNT(*) as count 
            FROM vectors v
            WHERE json_extract(v.metadata, '$.type') = 'memory'
              AND EXISTS (
                SELECT 1 FROM context_summaries s 
                WHERE s.session_id = v.session_id 
                  AND v.start_message_id >= s.start_message_id 
                  AND v.end_message_id <= s.end_message_id
              )
        `);

    const redundantCount = Number(redundancyResult.rows?.[0]?.count) || 0;
    const memoryCount = byType.memory;

    const redundancyRate = memoryCount > 0 ? redundantCount / memoryCount : 0;

    // 5. 估算存储大小（假设每个向量平均2KB）
    const storageSize = (Number(total) * 2) / 1024; // MB

    return {
      total: Number(total),
      byType,
      bySession,
      redundancyRate,
      storageSize,
    };
  }

  /**
   * 获取检索日志（预留接口，当前返回空）
   */
  static async getRetrievalLogs(limit: number = 50): Promise<
    Array<{
      timestamp: number;
      query: string;
      resultsCount: number;
      avgSimilarity: number;
      duration: number;
    }>
  > {
    // TODO: 实现日志记录功能
    return [];
  }
}

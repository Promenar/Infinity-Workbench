import { db } from '../db';
import { generateId } from '../utils/id-generator';

export type AuditAction = 'read' | 'write' | 'delete' | 'list' | 'execute' | 'create' | 'update';
export type ResourceType = 'file' | 'document' | 'sandbox' | 'session' | 'agent' | 'skill' | 'tool' | 'mcp';
export type AuditStatus = 'success' | 'error' | 'denied';

export interface AuditLogEntry {
  action: AuditAction;
  resourceType: ResourceType;
  resourcePath?: string;
  sessionId?: string;
  agentId?: string;
  skillId?: string;
  status: AuditStatus;
  errorMessage?: string;
  metadata?: Record<string, any>;
}

interface QueuedLog {
  entry: AuditLogEntry;
  id: string;
  createdAt: number;
}

class AuditService {
  private queue: QueuedLog[] = [];
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly FLUSH_INTERVAL = 1000;
  private isFlushing = false;

  async log(entry: AuditLogEntry): Promise<void> {
    const id = generateId();
    const createdAt = Date.now();

    this.queue.push({ entry, id, createdAt });
    this.scheduleFlush();
  }

  private scheduleFlush(): void {
    if (this.flushTimer) return;

    this.flushTimer = setTimeout(() => {
      this.flush();
      this.flushTimer = null;
    }, this.FLUSH_INTERVAL);
  }

  private async flush(): Promise<void> {
    if (this.queue.length === 0 || this.isFlushing) return;

    this.isFlushing = true;
    const batch = this.queue.splice(0, this.queue.length);

    try {
      await db.execute('BEGIN TRANSACTION');

      for (const { entry, id, createdAt } of batch) {
        await db.execute(
          `INSERT INTO audit_logs (id, action, resource_type, resource_path, session_id, agent_id, skill_id, status, error_message, metadata, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [
            id,
            entry.action,
            entry.resourceType,
            entry.resourcePath || null,
            entry.sessionId || null,
            entry.agentId || null,
            entry.skillId || null,
            entry.status,
            entry.errorMessage || null,
            entry.metadata ? JSON.stringify(entry.metadata) : null,
            createdAt,
          ],
        );
      }

      await db.execute('COMMIT');
    } catch (e) {
      await db.execute('ROLLBACK');
      console.error('[AuditService] Flush failed:', e);
    } finally {
      this.isFlushing = false;
    }
  }

  async queryLogs(options: {
    sessionId?: string;
    action?: AuditAction;
    resourceType?: ResourceType;
    startTime?: number;
    endTime?: number;
    limit?: number;
    offset?: number;
  }): Promise<any[]> {
    const conditions: string[] = [];
    const params: any[] = [];

    if (options.sessionId) {
      conditions.push('session_id = ?');
      params.push(options.sessionId);
    }
    if (options.action) {
      conditions.push('action = ?');
      params.push(options.action);
    }
    if (options.resourceType) {
      conditions.push('resource_type = ?');
      params.push(options.resourceType);
    }
    if (options.startTime) {
      conditions.push('created_at >= ?');
      params.push(options.startTime);
    }
    if (options.endTime) {
      conditions.push('created_at <= ?');
      params.push(options.endTime);
    }

    let sql = 'SELECT * FROM audit_logs';
    if (conditions.length > 0) {
      sql += ' WHERE ' + conditions.join(' AND ');
    }
    sql += ' ORDER BY created_at DESC';

    if (options.limit) {
      sql += ` LIMIT ${options.limit}`;
      if (options.offset) {
        sql += ` OFFSET ${options.offset}`;
      }
    }

    const result = await db.execute(sql, params);
    return result.rows || [];
  }

  async getSessionLogs(sessionId: string, limit: number = 100): Promise<any[]> {
    return this.queryLogs({ sessionId, limit });
  }

  async getRecentErrors(limit: number = 50): Promise<any[]> {
    const result = await db.execute(
      `SELECT * FROM audit_logs WHERE status = 'error' ORDER BY created_at DESC LIMIT ?`,
      [limit],
    );
    return result.rows || [];
  }

  async getStats(since?: number): Promise<{
    total: number;
    byAction: Record<string, number>;
    byStatus: Record<string, number>;
    errorRate: number;
  }> {
    const startTime = since || Date.now() - 24 * 60 * 60 * 1000;

    const totalResult = await db.execute(
      'SELECT COUNT(*) as count FROM audit_logs WHERE created_at >= ?',
      [startTime],
    );
    const total = Number(totalResult.rows?.[0]?.count || 0);

    const actionResult = await db.execute(
      'SELECT action, COUNT(*) as count FROM audit_logs WHERE created_at >= ? GROUP BY action',
      [startTime],
    );
    const byAction: Record<string, number> = {};
    for (const row of actionResult.rows || []) {
      byAction[row.action as string] = Number(row.count);
    }

    const statusResult = await db.execute(
      'SELECT status, COUNT(*) as count FROM audit_logs WHERE created_at >= ? GROUP BY status',
      [startTime],
    );
    const byStatus: Record<string, number> = {};
    for (const row of statusResult.rows || []) {
      byStatus[row.status as string] = Number(row.count);
    }

    const errorCount = byStatus['error'] || 0;
    const errorRate = total > 0 ? errorCount / total : 0;

    return { total, byAction, byStatus, errorRate };
  }

  async forceFlush(): Promise<void> {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    await this.flush();
  }

  async clearOldLogs(olderThanDays: number = 30): Promise<number> {
    const cutoff = Date.now() - olderThanDays * 24 * 60 * 60 * 1000;
    const result = await db.execute('DELETE FROM audit_logs WHERE created_at < ?', [cutoff]);
    return result.rowsAffected || 0;
  }
}

export const auditService = new AuditService();

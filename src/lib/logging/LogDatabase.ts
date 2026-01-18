import { open } from '@op-engineering/op-sqlite';
import { LogEntry } from './LogSchema';

const DB_NAME = 'app_logs.sqlite';

/**
 * 日志数据库管理类
 * 负责 SQLite 操作：表创建、批量写入、清理
 */
export class LogDatabase {
    private db = open({ name: DB_NAME });
    private static instance: LogDatabase;

    private constructor() {
        this.init();
    }

    public static getInstance(): LogDatabase {
        if (!LogDatabase.instance) {
            LogDatabase.instance = new LogDatabase();
        }
        return LogDatabase.instance;
    }

    private async init() {
        try {
            await this.db.execute(`
                CREATE TABLE IF NOT EXISTS logs (
                    id TEXT PRIMARY KEY,
                    level INTEGER NOT NULL,
                    tag TEXT NOT NULL,
                    message TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    metadata TEXT,
                    session_id TEXT
                );
            `);
            await this.db.execute(`CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp DESC);`);
            await this.db.execute(`CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level);`);
        } catch (e) {
            console.error('Failed to initialize LogDatabase:', e);
        }
    }

    /**
     * 批量插入日志 (事务处理)
     */
    public async insertLogs(logs: LogEntry[]) {
        if (logs.length === 0) return;

        try {
            await this.db.execute('BEGIN TRANSACTION');

            for (const log of logs) {
                await this.db.execute(`
                    INSERT INTO logs (id, level, tag, message, timestamp, metadata, session_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                `, [
                    log.id,
                    log.level,
                    log.tag,
                    log.message,
                    log.timestamp,
                    log.metadata ? JSON.stringify(log.metadata) : null,
                    log.session_id || null
                ]);
            }

            await this.db.execute('COMMIT');
        } catch (e) {
            console.error('Failed to insert logs:', e);
            try {
                await this.db.execute('ROLLBACK');
            } catch (_) { }
        }
    }

    /**
     * 清理旧日志
     */
    public async pruneLogs(maxCount: number = 10000) {
        try {
            await this.db.execute(`
                DELETE FROM logs 
                WHERE id NOT IN (
                    SELECT id FROM logs 
                    ORDER BY timestamp DESC 
                    LIMIT ?
                )
            `, [maxCount]);
        } catch (e) {
            console.error('Failed to prune logs:', e);
        }
    }

    /**
     * 获取最近的日志
     */
    public async getRecentLogs(limit: number = 100): Promise<LogEntry[]> {
        try {
            const results = await this.db.execute(`
                SELECT * FROM logs ORDER BY timestamp DESC LIMIT ?
            `, [limit]);

            const rows = (results.rows as any)?._array || (results.rows as any) || [];
            return rows.map((row: any) => ({
                id: row.id,
                level: row.level,
                tag: row.tag,
                message: row.message,
                timestamp: row.timestamp,
                metadata: row.metadata ? JSON.parse(row.metadata) : undefined,
                session_id: row.session_id
            }));
        } catch (e) {
            console.error('Failed to get recent logs:', e);
            return [];
        }
    }

    /**
     * 强制清空所有日志
     */
    public async clearAll() {
        try {
            await this.db.execute('DELETE FROM logs');
        } catch (e) {
            console.error('Failed to clear logs:', e);
        }
    }
}

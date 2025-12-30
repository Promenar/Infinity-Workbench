import { db } from './index';

/**
 * 数据库迁移脚本
 * 安全地升级现有表结构，不丢失数据
 */
export const migrateDatabase = async () => {
  try {
    console.log('[DB Migration] Starting database migration...');

    // 检查documents表是否有folder_id列
    const tableInfo = await db.execute('PRAGMA table_info(documents)');
    const columns = tableInfo.rows?.map((row: any) => row.name as string) || [];

    // Migration 1: 添加文件夹相关字段到documents表
    if (!columns.includes('folder_id')) {
      console.log('[DB Migration] Adding folder_id column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN folder_id TEXT');
    }

    if (!columns.includes('vectorized')) {
      console.log('[DB Migration] Adding vectorized column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN vectorized INTEGER DEFAULT 0');
    }

    if (!columns.includes('vector_count')) {
      console.log('[DB Migration] Adding vector_count column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN vector_count INTEGER DEFAULT 0');
    }

    if (!columns.includes('file_size')) {
      console.log('[DB Migration] Adding file_size column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN file_size INTEGER');
    }

    // Migration 2: 创建folders表（如果不存在）
    await db.execute(`
      CREATE TABLE IF NOT EXISTS folders (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        parent_id TEXT,
        created_at INTEGER NOT NULL,
        FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE CASCADE
      );
    `);

    // Migration 3: 更新已有文档的vectorized状态
    // 检查哪些文档已经有向量（通过vectors表关联）
    await db.execute(`
      UPDATE documents 
      SET vectorized = 2, vector_count = (
        SELECT COUNT(*) FROM vectors WHERE vectors.doc_id = documents.id
      )
      WHERE id IN (SELECT DISTINCT doc_id FROM vectors WHERE doc_id IS NOT NULL)
    `);

    // Migration 4: 为 vectors 表添加消息关联字段（支持精确清理）
    const vectorsInfo = await db.execute('PRAGMA table_info(vectors)');
    const vectorsColumns = vectorsInfo.rows?.map((row: any) => row.name as string) || [];

    if (!vectorsColumns.includes('start_message_id')) {
      console.log('[DB Migration] Adding start_message_id column to vectors...');
      await db.execute('ALTER TABLE vectors ADD COLUMN start_message_id TEXT');
    }

    if (!vectorsColumns.includes('end_message_id')) {
      console.log('[DB Migration] Adding end_message_id column to vectors...');
      await db.execute('ALTER TABLE vectors ADD COLUMN end_message_id TEXT');
    }

    console.log('[DB Migration] Migration completed successfully!');
  } catch (error) {
    console.error('[DB Migration] Migration failed:', error);
    throw error;
  }
};

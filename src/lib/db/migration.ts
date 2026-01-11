
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
      CREATE TABLE IF NOT EXISTS folders(
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  parent_id TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(parent_id) REFERENCES folders(id) ON DELETE CASCADE
);
`);

    // Migration 3: 更新已有文档的vectorized状态
    // 检查哪些文档已经有向量（通过vectors表关联）
    await db.execute(`
      UPDATE documents 
      SET vectorized = 2, vector_count = (
  SELECT COUNT(*) FROM vectors WHERE vectors.doc_id = documents.id
      )
      WHERE id IN(SELECT DISTINCT doc_id FROM vectors WHERE doc_id IS NOT NULL);
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

    // Migration 5 (Phase 8): Tags and KG tables
    const tagsInfo = await db.execute(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='tags'",
    );
    if (!tagsInfo.rows || tagsInfo.rows.length === 0) {
      console.log('[DB Migration] Creating tags table...');
      await db.execute(`
        CREATE TABLE IF NOT EXISTS tags(
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  color TEXT DEFAULT '#6366f1',
  created_at INTEGER NOT NULL
);
`);
    }

    const docTagsInfo = await db.execute(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='document_tags'",
    );
    if (!docTagsInfo.rows || docTagsInfo.rows.length === 0) {
      console.log('[DB Migration] Creating document_tags table...');
      await db.execute(`
        CREATE TABLE IF NOT EXISTS document_tags(
  doc_id TEXT NOT NULL,
  tag_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(doc_id, tag_id),
  FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE,
  FOREIGN KEY(tag_id) REFERENCES tags(id) ON DELETE CASCADE
);
`);
    }

    // Migration 6 (Phase 8): Knowledge Graph tables
    const kgNodesInfo = await db.execute(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='kg_nodes'",
    );
    if (!kgNodesInfo.rows || kgNodesInfo.rows.length === 0) {
      console.log('[DB Migration] Creating kg_nodes table...');
      await db.execute(`
        CREATE TABLE IF NOT EXISTS kg_nodes(
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  type TEXT DEFAULT 'concept',
  metadata TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER,
  UNIQUE(name)
);
`);
    } else {
      // Double check if updated_at exists (for existing tables)
      const kgNodesColumns = await db.execute('PRAGMA table_info(kg_nodes)');
      const hasUpdatedAt = kgNodesColumns.rows?.some((col: any) => col.name === 'updated_at');
      if (!hasUpdatedAt) {
        console.log('[DB Migration] Adding updated_at column to existing kg_nodes table...');
        await db.execute('ALTER TABLE kg_nodes ADD COLUMN updated_at INTEGER');
      }
    }

    const kgEdgesInfo = await db.execute(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='kg_edges'",
    );
    if (!kgEdgesInfo.rows || kgEdgesInfo.rows.length === 0) {
      console.log('[DB Migration] Creating kg_edges table...');
      await db.execute(`
        CREATE TABLE IF NOT EXISTS kg_edges(
  id TEXT PRIMARY KEY NOT NULL,
  source_id TEXT NOT NULL,
  target_id TEXT NOT NULL,
  relation TEXT NOT NULL,
  weight REAL DEFAULT 1.0,
  doc_id TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(source_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
  FOREIGN KEY(target_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
  FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE
);
`);
    }

    // Migration 7 (Phase 8): Cost Optimization - Incremental Hash
    if (!columns.includes('kg_processed_hash')) {
      console.log('[DB Migration] Adding kg_processed_hash column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN kg_processed_hash TEXT');
    }

    if (!columns.includes('thumbnail_path')) {
      console.log('[DB Migration] Adding thumbnail_path column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN thumbnail_path TEXT');
    }

    // Migration 8 (Phase 9 - KG 2.0): Add Scope to KG tables
    const kgNodesCols = (await db.execute('PRAGMA table_info(kg_nodes)')).rows?.map((row: any) => row.name as string) || [];
    if (!kgNodesCols.includes('session_id')) {
      console.log('[DB Migration] Adding session_id/agent_id to kg_nodes...');
      await db.execute('ALTER TABLE kg_nodes ADD COLUMN session_id TEXT');
      await db.execute('ALTER TABLE kg_nodes ADD COLUMN agent_id TEXT');
      await db.execute('CREATE INDEX IF NOT EXISTS idx_kg_nodes_session ON kg_nodes(session_id)');
      await db.execute('CREATE INDEX IF NOT EXISTS idx_kg_nodes_agent ON kg_nodes(agent_id)');
    }

    const kgEdgesCols = (await db.execute('PRAGMA table_info(kg_edges)')).rows?.map((row: any) => row.name as string) || [];
    if (!kgEdgesCols.includes('session_id')) {
      console.log('[DB Migration] Adding session_id/agent_id to kg_edges...');
      await db.execute('ALTER TABLE kg_edges ADD COLUMN session_id TEXT');
      await db.execute('ALTER TABLE kg_edges ADD COLUMN agent_id TEXT');
      await db.execute('CREATE INDEX IF NOT EXISTS idx_kg_edges_session ON kg_edges(session_id)');
    }

    console.log('[DB Migration] Migration completed successfully!');
  } catch (error) {
    console.error('[DB Migration] Migration failed:', error);
    // Don't throw, just log. We don't want to crash app on startup for non-critical migration failures
  }
};

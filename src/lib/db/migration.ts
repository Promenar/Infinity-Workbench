
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

    // Migration 4.5 (Phase 4b Repair): 补全 messages 和 sessions 表缺失的字段
    // 这是一个关键修复，因为 schema.ts 更新了但缺少迁移脚本，导致旧版本升级后无法写入新字段
    const msgInfo = await db.execute('PRAGMA table_info(messages)');
    const msgCols = msgInfo.rows?.map((row: any) => row.name as string) || [];

    const msgNewCols = [
      { name: 'reasoning', type: 'TEXT' },
      { name: 'thought_signature', type: 'TEXT' },
      { name: 'rag_references', type: 'TEXT' },
      { name: 'rag_progress', type: 'TEXT' },
      { name: 'rag_metadata', type: 'TEXT' },
      { name: 'rag_references_loading', type: 'INTEGER DEFAULT 0' },
      { name: 'execution_steps', type: 'TEXT' },
      { name: 'tool_calls', type: 'TEXT' },
      { name: 'pending_approval_tool_ids', type: 'TEXT' },
      { name: 'tool_call_id', type: 'TEXT' },
      { name: 'name', type: 'TEXT' },
      { name: 'planning_task', type: 'TEXT' },
      { name: 'vectorization_status', type: 'TEXT' },
      { name: 'layout_height', type: 'REAL' },
      { name: 'is_archived', type: 'INTEGER DEFAULT 0' }
    ];

    for (const col of msgNewCols) {
      if (!msgCols.includes(col.name)) {
        console.log(`[DB Migration] Adding ${col.name} column to messages...`);
        await db.execute(`ALTER TABLE messages ADD COLUMN ${col.name} ${col.type}`);
      }
    }

    const sessionInfo = await db.execute('PRAGMA table_info(sessions)');
    const sessionCols = sessionInfo.rows?.map((row: any) => row.name as string) || [];

    const sessionNewCols = [
      { name: 'approval_request', type: 'TEXT' },
      { name: 'rag_options', type: 'TEXT' },
      { name: 'inference_params', type: 'TEXT' },
      { name: 'active_task', type: 'TEXT' },
      { name: 'stats', type: 'TEXT' },
      { name: 'options', type: 'TEXT' }
    ];

    for (const col of sessionNewCols) {
      if (!sessionCols.includes(col.name)) {
        console.log(`[DB Migration] Adding ${col.name} column to sessions...`);
        await db.execute(`ALTER TABLE sessions ADD COLUMN ${col.name} ${col.type}`);
      }
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

    if (!columns.includes('content_hash')) {
      console.log('[DB Migration] Adding content_hash column to documents...');
      await db.execute('ALTER TABLE documents ADD COLUMN content_hash TEXT');
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

    // Migration 9: Deduplicate workspace folders and cleanup explosion (High Efficiency)
    const WORKSPACE_NAME = 'workspace';

    // 1. Ensure indexes exist for fast lookup and deletion
    await db.execute('CREATE INDEX IF NOT EXISTS idx_folders_name_parent ON folders(name, parent_id)');
    await db.execute('CREATE INDEX IF NOT EXISTS idx_documents_folder ON documents(folder_id)');

    const workspaceCountResult = await db.execute(
      'SELECT COUNT(*) as count FROM folders WHERE name = ? AND parent_id IS NULL',
      [WORKSPACE_NAME]
    );
    const workspaceCount = (workspaceCountResult.rows?.[0] as any)?.count || 0;

    if (workspaceCount > 1) {
      console.log(`[DB Migration] Detected ${workspaceCount} workspace folders. Starting optimized deduplication...`);

      // 2. 获取要保留的 ID (最早创建的一个)
      const primaryResult = await db.execute(
        'SELECT id FROM folders WHERE name = ? AND parent_id IS NULL ORDER BY created_at ASC LIMIT 1',
        [WORKSPACE_NAME]
      );
      const primaryId = (primaryResult.rows?.[0] as any)?.id;

      if (primaryId) {
        // 3. 将重复节点下的文档指回 Primary
        // 由于已建立索引，只需处理 folder_id 不等于 primaryId 的情形
        await db.execute(`
          UPDATE documents 
          SET folder_id = ? 
          WHERE folder_id IS NOT NULL 
          AND folder_id != ?
          AND folder_id IN (
            SELECT id FROM folders 
            WHERE name = ? AND parent_id IS NULL
          )
        `, [primaryId, primaryId, WORKSPACE_NAME]);

        // 4. 大规模删除冗余文件夹 (利用索引直接删除)
        await db.execute(`
          DELETE FROM folders 
          WHERE name = ? 
          AND parent_id IS NULL 
          AND id != ?
        `, [WORKSPACE_NAME, primaryId]);

        console.log(`[DB Migration] Cleanup finished. Records reduced by ${workspaceCount - 1}.`);
      }
    }

    console.log('[DB Migration] Migration completed successfully!');
  } catch (error) {
    console.error('[DB Migration] Migration failed:', error);
    // Don't throw, just log. We don't want to crash app on startup for non-critical migration failures
  }

  // Migration 10: Add active_mcp/skill_ids to sessions
  try {
    const sessionCols = (await db.execute('PRAGMA table_info(sessions)')).rows?.map((row: any) => row.name as string) || [];

    if (!sessionCols.includes('active_mcp_server_ids')) {
      console.log('[DB Migration] Adding active_mcp_server_ids to sessions...');
      await db.execute('ALTER TABLE sessions ADD COLUMN active_mcp_server_ids TEXT');
    }

    if (!sessionCols.includes('active_skill_ids')) {
      console.log('[DB Migration] Adding active_skill_ids to sessions...');
      await db.execute('ALTER TABLE sessions ADD COLUMN active_skill_ids TEXT');
    }

    // 🔍 修复：强制检查 options 和 rag_options，防止 Migration 4.5 漏网
    if (!sessionCols.includes('options')) {
      console.log('[DB Migration] Recovering missing options column in sessions...');
      await db.execute('ALTER TABLE sessions ADD COLUMN options TEXT');
    }

    if (!sessionCols.includes('rag_options')) {
      console.log('[DB Migration] Recovering missing rag_options column in sessions...');
      await db.execute('ALTER TABLE sessions ADD COLUMN rag_options TEXT');
    }
  } catch (e) {
    console.warn('[DB Migration] Migration 10 failed:', e);
  }
};

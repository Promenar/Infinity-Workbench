import { db } from './index';

export const createTables = async () => {
  try {
    // 1. Sessions Table
    await db.execute(`
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY NOT NULL,
        title TEXT DEFAULT 'New Chat',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        mode TEXT DEFAULT 'chat', -- 'chat' | 'writer'
        pinned INTEGER DEFAULT 0,
        model_config TEXT -- JSON string for specific model override
      );
    `);

    // 2. Messages Table
    await db.execute(`
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY NOT NULL,
        session_id TEXT NOT NULL,
        role TEXT NOT NULL, -- 'user' | 'assistant' | 'system'
        content TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        parent_id TEXT, -- For tree-based history if needed later
        metadata TEXT, -- JSON for tokens, timing, etc.
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );
    `);

    // 3. Attachments/Files (Multimodal)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS attachments (
        id TEXT PRIMARY KEY NOT NULL,
        message_id TEXT NOT NULL,
        type TEXT NOT NULL, -- 'image' | 'file'
        uri TEXT NOT NULL,
        local_uri TEXT,
        FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
      );
    `);

    // 4. Folders (Knowledge Base Organization)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS folders (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        parent_id TEXT,
        created_at INTEGER NOT NULL,
        FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE CASCADE
      );
    `);

    // 5. Documents (Knowledge Base)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS documents (
        id TEXT PRIMARY KEY NOT NULL,
        title TEXT,
        content TEXT,
        source TEXT, -- 'import' | 'url' | 'paste'
        type TEXT DEFAULT 'text', -- 'text' | 'pdf' | 'note'
        folder_id TEXT, -- Link to folders table
        vectorized INTEGER DEFAULT 0, -- 0=未处理, 1=处理中, 2=已完成, -1=失败
        vector_count INTEGER DEFAULT 0,
        file_size INTEGER,
        created_at INTEGER NOT NULL,
        updated_at INTEGER,
        metadata TEXT, -- JSON
        is_global INTEGER DEFAULT 0, -- 0=Session Scoped (if imported inside session) or Private, 1=Global Knowledge
        content_hash TEXT, -- Content hash for incremental vectorization
        FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE SET NULL
      );
    `);

    // Migration: ensure is_global exists
    try {
      await db.execute('ALTER TABLE documents ADD COLUMN is_global INTEGER DEFAULT 0;');
    } catch (e) {
      // Column likely exists
    }

    // 6. Vectors (Embeddings)
    // Store embeddings as BLOB. SQLite handles BLOBs efficiently.
    // We will perform naive/brute-force cosine similarity in JS/SQL function for <100k items
    await db.execute(`
      CREATE TABLE IF NOT EXISTS vectors (
        id TEXT PRIMARY KEY NOT NULL,
        doc_id TEXT, -- Link to documents table (nullable if pure memory)
        session_id TEXT, -- Link to sessions table (if memory)
        content TEXT NOT NULL, -- Chunk text
        embedding BLOB NOT NULL, -- Float32Array binary
        metadata TEXT, -- JSON: source, type('doc'|'memory'), chunk_index
        start_message_id TEXT, -- 向量覆盖的起始消息ID（用于精确清理）
        end_message_id TEXT, -- 向量覆盖的结束消息ID（用于精确清理）
        created_at INTEGER NOT NULL,
        FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );
    `);

    // 7. Context Summaries (Advanced Context Management)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS context_summaries (
        id TEXT PRIMARY KEY NOT NULL,
        session_id TEXT NOT NULL,
        start_message_id TEXT NOT NULL,
        end_message_id TEXT NOT NULL,
        summary_content TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        token_usage INTEGER, -- Cost tracking
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );
    `);

    // 8. FTS5 全文索引虚拟表（用于混合检索）
    // op-sqlite 支持 FTS5 扩展（需在 package.json 中配置 "fts5": true）
    try {
      // 创建 FTS5 虚拟表
      await db.execute(
        'CREATE VIRTUAL TABLE IF NOT EXISTS vectors_fts USING fts5(content, content="vectors", content_rowid="rowid")',
      );

      // 触发器：插入时同步
      await db.execute(
        'CREATE TRIGGER IF NOT EXISTS vectors_fts_insert AFTER INSERT ON vectors BEGIN INSERT INTO vectors_fts(rowid, content) VALUES(new.rowid, new.content); END',
      );

      // 触发器：更新时同步
      await db.execute(
        'CREATE TRIGGER IF NOT EXISTS vectors_fts_update AFTER UPDATE ON vectors BEGIN UPDATE vectors_fts SET content = new.content WHERE rowid = old.rowid; END',
      );

      // 触发器：删除时同步
      await db.execute(
        'CREATE TRIGGER IF NOT EXISTS vectors_fts_delete AFTER DELETE ON vectors BEGIN DELETE FROM vectors_fts WHERE rowid = old.rowid; END',
      );

      console.log('[DB] FTS5 full-text search enabled');
    } catch (ftsError: any) {
      // FTS5 未启用或不可用时，会自动回退到 LIKE 查询（keyword-search.ts 中已实现）
      console.warn(
        '[DB] FTS5 not available, keyword search will fall back to LIKE:',
        ftsError.message,
      );
    }

    // 9. Tags System (Smart Tags)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS tags (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        color TEXT DEFAULT '#6366f1',
        created_at INTEGER NOT NULL
      );
    `);

    await db.execute(`
      CREATE TABLE IF NOT EXISTS document_tags (
        doc_id TEXT NOT NULL,
        tag_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (doc_id, tag_id),
        FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
        FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
      );
    `);

    // 10. Knowledge Graph System
    await db.execute(`
      CREATE TABLE IF NOT EXISTS kg_nodes (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        type TEXT DEFAULT 'concept', -- 'concept'|'person'|'org'|'location'...
        metadata TEXT, -- JSON
        created_at INTEGER NOT NULL,
        updated_at INTEGER,
        UNIQUE(name)
      );
    `);

    await db.execute(`
      CREATE TABLE IF NOT EXISTS kg_edges (
        id TEXT PRIMARY KEY NOT NULL,
        source_id TEXT NOT NULL,
        target_id TEXT NOT NULL,
        relation TEXT NOT NULL,
        weight REAL DEFAULT 1.0,
        doc_id TEXT, -- Source document for attribution
        created_at INTEGER NOT NULL,
        FOREIGN KEY (source_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
        FOREIGN KEY (target_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
        FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE
      );
    `);

    console.log('[DB] Tables created successfully');
  } catch (e) {
    console.error('[DB] Error creating tables:', e);
  }
};

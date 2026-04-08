import { db } from './index';

export const createTables = async () => {
  try {
    // 1. Sessions Table (🔑 Phase 4b: 完整字段版本)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY NOT NULL,
        agent_id TEXT NOT NULL,
        title TEXT DEFAULT 'New Chat',
        last_message TEXT,
        time TEXT,
        unread INTEGER DEFAULT 0,
        model_id TEXT,
        custom_prompt TEXT,
        is_pinned INTEGER DEFAULT 0,
        scroll_offset REAL,
        draft TEXT,
        execution_mode TEXT DEFAULT 'auto',
        loop_status TEXT DEFAULT 'idle',
        pending_intervention TEXT,
        approval_request TEXT, -- JSON
        rag_options TEXT, -- JSON
        inference_params TEXT, -- JSON
        active_task TEXT, -- JSON
        stats TEXT, -- JSON (billing usage)
        options TEXT, -- JSON (webSearch, reasoning, toolsEnabled)
        active_mcp_server_ids TEXT, -- JSON Array
        active_skill_ids TEXT, -- JSON Array
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );
    `);


    // 2. Messages Table (🔑 Phase 4b: 完整字段版本)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY NOT NULL,
        session_id TEXT NOT NULL,
        role TEXT NOT NULL, -- 'user' | 'assistant' | 'system' | 'tool'
        content TEXT NOT NULL,
        model_id TEXT,
        status TEXT, -- 'sending' | 'sent' | 'error' | 'streaming'
        reasoning TEXT, -- Chain of Thought
        thought_signature TEXT, -- Gemini Thinking 签名
        images TEXT, -- JSON: GeneratedImageData[]
        tokens TEXT, -- JSON: TokenUsage
        citations TEXT, -- JSON: Web Search citations
        rag_references TEXT, -- JSON: RagReference[]
        rag_progress TEXT, -- JSON: RagProgress
        rag_metadata TEXT, -- JSON: RagMetadata
        rag_references_loading INTEGER DEFAULT 0,
        execution_steps TEXT, -- JSON: ExecutionStep[]
        tool_calls TEXT, -- JSON: ToolCall[]
        pending_approval_tool_ids TEXT, -- JSON: string[]
        tool_call_id TEXT, -- 工具调用关联 ID (role: tool)
        name TEXT, -- 工具名称 (role: tool)
        planning_task TEXT, -- JSON: TaskState
        is_archived INTEGER DEFAULT 0,
        vectorization_status TEXT, -- 'processing' | 'success' | 'error'
        layout_height REAL,
        tool_results TEXT, -- JSON: ToolResult artifact items
        files TEXT, -- JSON: ChatAttachment[] (文件附件持久化)
        created_at INTEGER NOT NULL,
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );
    `);

    // Index for efficient message queries
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
    `);
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_messages_session_created ON messages(session_id, created_at);
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

    // Migration: ensure messages.tool_results exists
    try {
      await db.execute('ALTER TABLE messages ADD COLUMN tool_results TEXT;');
      await db.execute('ALTER TABLE messages ADD COLUMN is_error INTEGER DEFAULT 0;');
      await db.execute('ALTER TABLE messages ADD COLUMN error_message TEXT;');
    } catch (e) {
      // Column likely exists
    }

    // 🔑 Migration: ensure messages.files exists (文件附件持久化)
    try {
      await db.execute('ALTER TABLE messages ADD COLUMN files TEXT;');
    } catch (e) {
      // Column likely exists
    }

    // 🔑 Migration: ensure messages.is_error and error_message exist (Soft Timeout Persistence)
    try {
      await db.execute('ALTER TABLE messages ADD COLUMN is_error INTEGER DEFAULT 0;');
      await db.execute('ALTER TABLE messages ADD COLUMN error_message TEXT;');
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

    // 11. Vectorization Tasks Queue (Checkpoint Persistence)
    // 🔑 用于持久化向量化任务状态，支持后台中断恢复
    await db.execute(`
      CREATE TABLE IF NOT EXISTS vectorization_tasks (
        id TEXT PRIMARY KEY NOT NULL,
        type TEXT NOT NULL, -- 'document' | 'memory'
        status TEXT NOT NULL, -- 'pending' | 'processing' | 'completed' | 'failed' | 'interrupted'
        -- Document task fields
        doc_id TEXT,
        doc_title TEXT,
        -- Memory task fields
        session_id TEXT,
        user_content TEXT,
        ai_content TEXT,
        user_message_id TEXT,
        assistant_message_id TEXT,
        -- Checkpoint fields
        last_chunk_index INTEGER DEFAULT 0,
        total_chunks INTEGER,
        progress REAL DEFAULT 0,
        error TEXT,
        -- Timestamps
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        -- 🔑 Phase 4b: 现在两个 FK 都有效，因为 sessions 表已在 SQLite 中
        FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );
    `);

    // Index for quick status lookups
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_vectorization_tasks_status ON vectorization_tasks(status);
    `);

    // 12. Audit Logs (Security & Compliance)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS audit_logs (
        id TEXT PRIMARY KEY NOT NULL,
        action TEXT NOT NULL,
        resource_type TEXT NOT NULL,
        resource_path TEXT,
        session_id TEXT,
        agent_id TEXT,
        skill_id TEXT,
        status TEXT NOT NULL,
        error_message TEXT,
        metadata TEXT,
        created_at INTEGER NOT NULL
      );
    `);

    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_audit_logs_session ON audit_logs(session_id);
    `);
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at);
    `);
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
    `);

    // 13. Artifacts (Workspace Integration)
    await db.execute(`
      CREATE TABLE IF NOT EXISTS artifacts (
        id TEXT PRIMARY KEY NOT NULL,
        type TEXT NOT NULL,
        title TEXT NOT NULL,
        content TEXT NOT NULL,
        preview_image TEXT,
        session_id TEXT NOT NULL,
        message_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        tags TEXT,
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );
    `);

    // Indexes for artifacts
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_artifacts_session ON artifacts(session_id);
    `);
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_artifacts_type ON artifacts(type);
    `);
    await db.execute(`
      CREATE INDEX IF NOT EXISTS idx_artifacts_created_at ON artifacts(created_at);
    `);

    console.log('[DB] Tables created successfully');
  } catch (e) {
    console.error('[DB] Error creating tables:', e);
  }
};

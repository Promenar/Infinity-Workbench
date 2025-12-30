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
        FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE SET NULL
      );
    `);

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

    console.log('[DB] Tables created successfully');
  } catch (e) {
    console.error('[DB] Error creating tables:', e);
  }
};

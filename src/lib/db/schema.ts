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

        console.log('[DB] Tables created successfully');
    } catch (e) {
        console.error('[DB] Error creating tables:', e);
    }
};

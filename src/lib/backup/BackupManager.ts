import AsyncStorage from '@react-native-async-storage/async-storage';
import { db } from '../db';
import * as FileSystem from 'expo-file-system/legacy';
import { Buffer } from 'buffer';

export interface BackupData {
  meta: {
    version: number;
    timestamp: number;
    platform: string;
    schemaVersion: number;
  };
  asyncStorage: Record<string, string>;
  sqlite: {
    sessions: any[];
    messages: any[];
    attachments: any[];
    folders: any[];
    documents: any[];
    vectors: any[]; // embeddings will be base64 strings
    context_summaries: any[];
    // New tables
    tags: any[];
    document_tags: any[];
    kg_nodes: any[];
    kg_edges: any[];
  };
  files?: Record<string, string>; // relativePath -> base64
}

const BACKUP_VERSION = 1;
const AUTO_BACKUP_INTERVAL = 24 * 60 * 60 * 1000; // 24 hours

export class BackupManager {
  static async checkAndTriggerAutoBackup() {
    try {
      const configJson = await AsyncStorage.getItem('backup_config');
      if (!configJson) return;

      const config = JSON.parse(configJson);
      if (!config.enabled || !config.autoBackup || !config.url) return;

      const lastBackupStr = await AsyncStorage.getItem('last_auto_backup_time');
      const lastBackup = lastBackupStr ? parseInt(lastBackupStr) : 0;
      const now = Date.now();

      if (now - lastBackup > AUTO_BACKUP_INTERVAL) {
        console.log('[BackupManager] Triggering auto backup...');
        const { WebDavClient } = require('./WebDavClient'); // Lazy import to avoid cycle if any

        const client = new WebDavClient({
          url: config.url,
          username: config.username,
          password: config.password,
        });

        const data = await this.exportData();
        const json = JSON.stringify(data);
        const filename = `nexara_auto_backup_${new Date().toISOString().replace(/[:.]/g, '-')}.json`;

        await client.uploadFile(filename, json);
        await AsyncStorage.setItem('last_auto_backup_time', now.toString());
        console.log('[BackupManager] Auto backup success:', filename);
      }
    } catch (e) {
      console.error('[BackupManager] Auto backup failed:', e);
    }
  }

  /**
   * Export all app data to a JSON object
   */
  static async exportData(): Promise<BackupData> {
    console.log('[BackupManager] Starting export...');

    // 1. Export AsyncStorage
    const keys = [
      'settings-storage-v2',
      'chat-storage',
      'api-storage-v2',
      'agent-storage',
      'spa-storage',
      'token-stats-storage',
      'theme_mode',
      'backup_config',
      'last_auto_backup_time',
    ];
    const stores = await AsyncStorage.multiGet(keys);
    const asyncStorageData: Record<string, string> = {};

    stores.forEach(([key, value]) => {
      if (value) asyncStorageData[key] = value;
    });

    // 2. Export SQLite Tables
    const sessions = await this.safeQuery('SELECT * FROM sessions');
    const messages = await this.safeQuery('SELECT * FROM messages');
    const attachments = await this.safeQuery('SELECT * FROM attachments');
    const folders = await this.safeQuery('SELECT * FROM folders');
    const documents = await this.safeQuery('SELECT * FROM documents');

    const vectorsRaw = await this.safeQuery('SELECT * FROM vectors');
    const vectors = vectorsRaw.map((v: any) => ({
      ...v,
      embedding: v.embedding ? Buffer.from(v.embedding).toString('base64') : null,
    }));

    const context_summaries = await this.safeQuery('SELECT * FROM context_summaries');
    const tags = await this.safeQuery('SELECT * FROM tags');
    const document_tags = await this.safeQuery('SELECT * FROM document_tags');
    const kg_nodes = await this.safeQuery('SELECT * FROM kg_nodes');
    const kg_edges = await this.safeQuery('SELECT * FROM kg_edges');

    // 3. Collect Physical Files and transform paths
    const physicalFiles: Record<string, string> = {};
    const docDir = FileSystem.documentDirectory || '';

    // Transform logic for AsyncStorage (mostly chat-storage images)
    for (const key in asyncStorageData) {
      if (key === 'chat-storage') {
        try {
          const chatData = JSON.parse(asyncStorageData[key]);
          if (chatData.state?.sessions) {
            for (const session of chatData.state.sessions) {
              for (const msg of session.messages) {
                if (msg.images) {
                  for (const img of msg.images) {
                    if (img.original) {
                      const rel = this.getRelativePath(img.original, docDir);
                      if (rel) {
                        const base64 = await this.safeReadFile(img.original);
                        if (base64) physicalFiles[rel] = base64;
                        img.original = `__DOC_DIR__/${rel}`;
                      }
                    }
                    if (img.thumbnail) {
                      const rel = this.getRelativePath(img.thumbnail, docDir);
                      if (rel) {
                        const base64 = await this.safeReadFile(img.thumbnail);
                        if (base64) physicalFiles[rel] = base64;
                        img.thumbnail = `__DOC_DIR__/${rel}`;
                      }
                    }
                  }
                }
              }
            }
          }
          asyncStorageData[key] = JSON.stringify(chatData);
        } catch (e) {
          console.error('[BackupManager] Failed to process chat-storage paths', e);
        }
      }
    }

    // Transform logic for SQLite attachments and documents
    const processedAttachments = [];
    for (const a of attachments) {
      if (a.local_uri) {
        const rel = this.getRelativePath(a.local_uri, docDir);
        if (rel) {
          const b64 = await this.safeReadFile(a.local_uri);
          if (b64) physicalFiles[rel] = b64;
          processedAttachments.push({ ...a, local_uri: `__DOC_DIR__/${rel}` });
          continue;
        }
      }
      processedAttachments.push(a);
    }

    const processedDocuments = [];
    for (const d of documents) {
      if (d.thumbnail_path) {
        const rel = this.getRelativePath(d.thumbnail_path, docDir);
        if (rel) {
          const b64 = await this.safeReadFile(d.thumbnail_path);
          if (b64) physicalFiles[rel] = b64;
          processedDocuments.push({ ...d, thumbnail_path: `__DOC_DIR__/${rel}` });
          continue;
        }
      }
      processedDocuments.push(d);
    }

    return {
      meta: {
        version: BACKUP_VERSION,
        timestamp: Date.now(),
        platform: 'nexara',
        schemaVersion: 1,
      },
      asyncStorage: asyncStorageData,
      sqlite: {
        sessions,
        messages,
        attachments: processedAttachments,
        folders,
        documents: processedDocuments,
        vectors,
        context_summaries,
        tags,
        document_tags,
        kg_nodes,
        kg_edges,
      },
      files: physicalFiles,
    };
  }

  /**
   * Import data from a backup object
   */
  static async importData(backup: BackupData): Promise<void> {
    console.log('[BackupManager] Starting import...');

    if (backup.meta.version > BACKUP_VERSION) {
      throw new Error(`Backup version ${backup.meta.version} is newer than supported version ${BACKUP_VERSION}`);
    }

    const docDir = FileSystem.documentDirectory || '';

    // 1. Restore Physical Files first
    if (backup.files) {
      for (const [relPath, base64] of Object.entries(backup.files)) {
        const fullPath = `${docDir}${relPath}`;
        await this.ensureDir(fullPath);
        await FileSystem.writeAsStringAsync(fullPath, base64, {
          encoding: FileSystem.EncodingType.Base64,
        });
      }
    }

    // 2. Rewrite Paths in memory
    for (const key in backup.asyncStorage) {
      if (key === 'chat-storage') {
        try {
          const chatData = JSON.parse(backup.asyncStorage[key]);
          if (chatData.state?.sessions) {
            for (const session of chatData.state.sessions) {
              for (const msg of session.messages) {
                if (msg.images) {
                  for (const img of msg.images) {
                    if (img.original?.startsWith('__DOC_DIR__/')) {
                      img.original = img.original.replace('__DOC_DIR__/', docDir);
                    }
                    if (img.thumbnail?.startsWith('__DOC_DIR__/')) {
                      img.thumbnail = img.thumbnail.replace('__DOC_DIR__/', docDir);
                    }
                  }
                }
              }
            }
          }
          backup.asyncStorage[key] = JSON.stringify(chatData);
        } catch (e) {
          console.error('[BackupManager] Failed to rewrite chat-storage paths', e);
        }
      }
    }

    const restoredAttachments = backup.sqlite.attachments.map((a: any) => ({
      ...a,
      local_uri: a.local_uri?.startsWith('__DOC_DIR__/')
        ? a.local_uri.replace('__DOC_DIR__/', docDir)
        : a.local_uri,
    }));

    const restoredDocuments = backup.sqlite.documents.map((d: any) => ({
      ...d,
      thumbnail_path: d.thumbnail_path?.startsWith('__DOC_DIR__/')
        ? d.thumbnail_path.replace('__DOC_DIR__/', docDir)
        : d.thumbnail_path,
    }));

    try {
      // 3. Restore AsyncStorage
      const pairs: [string, string][] = Object.entries(backup.asyncStorage);
      if (pairs.length > 0) {
        await AsyncStorage.multiSet(pairs);
      }

      // 4. Restore SQLite
      await db.execute('BEGIN TRANSACTION');

      // Clear tables
      await db.execute('DELETE FROM kg_edges');
      await db.execute('DELETE FROM kg_nodes');
      await db.execute('DELETE FROM document_tags');
      await db.execute('DELETE FROM tags');
      await db.execute('DELETE FROM vectors');
      await db.execute('DELETE FROM attachments');
      await db.execute('DELETE FROM messages');
      await db.execute('DELETE FROM documents');
      await db.execute('DELETE FROM context_summaries');
      await db.execute('DELETE FROM sessions');
      await db.execute('DELETE FROM folders');

      // Insert Data
      await this.bulkInsert('sessions', backup.sqlite.sessions);
      await this.bulkInsert('folders', backup.sqlite.folders);
      await this.bulkInsert('documents', restoredDocuments);
      await this.bulkInsert('messages', backup.sqlite.messages);
      await this.bulkInsert('attachments', restoredAttachments);
      await this.bulkInsert('context_summaries', backup.sqlite.context_summaries);
      await this.bulkInsert('tags', backup.sqlite.tags);
      await this.bulkInsert('document_tags', backup.sqlite.document_tags);
      await this.bulkInsert('kg_nodes', backup.sqlite.kg_nodes);
      await this.bulkInsert('kg_edges', backup.sqlite.kg_edges);

      // Vectors BLOB handling
      if (backup.sqlite.vectors && backup.sqlite.vectors.length > 0) {
        for (const v of backup.sqlite.vectors) {
          const embeddingBlob = v.embedding ? Buffer.from(v.embedding, 'base64') : null;
          await db.execute(
            `INSERT INTO vectors (id, doc_id, session_id, content, embedding, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
            [v.id, v.doc_id, v.session_id, v.content, embeddingBlob, v.metadata, v.created_at],
          );
        }
      }

      await db.execute('COMMIT');
      console.log('[BackupManager] Import completed successfully');
    } catch (e) {
      await db.execute('ROLLBACK');
      console.error('[BackupManager] Import failed, rolled back:', e);
      throw e;
    }
  }

  private static async safeQuery(sql: string): Promise<any[]> {
    const res = await db.execute(sql);
    if (Array.isArray(res.rows)) return res.rows;
    // @ts-ignore
    if (res.rows?._array) return res.rows._array;
    const results = [];
    // @ts-ignore
    const len = res.rows?.length || 0;
    if (len > 0) {
      for (let i = 0; i < len; i++) {
        // @ts-ignore
        results.push((res.rows as any)[i]);
      }
    }
    return results;
  }

  private static async bulkInsert(table: string, rows: any[]) {
    if (!rows || rows.length === 0) return;
    const keys = Object.keys(rows[0]);
    const columns = keys.join(', ');
    const placeholders = keys.map(() => '?').join(', ');
    const sql = `INSERT INTO ${table} (${columns}) VALUES (${placeholders})`;
    for (const row of rows) {
      const values = keys.map((k) => row[k]);
      await db.execute(sql, values);
    }
  }

  static async getBackupSize(backup: BackupData): Promise<string> {
    const json = JSON.stringify(backup);
    const bytes = new TextEncoder().encode(json).length;
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
  }

  // --- Helpers ---
  private static getRelativePath(uri: string, docDir: string): string | null {
    if (!uri || !uri.startsWith('file://')) return null;
    if (uri.startsWith(docDir)) {
      return uri.substring(docDir.length);
    }
    return null;
  }

  private static async safeReadFile(uri: string): Promise<string | null> {
    try {
      const info = await FileSystem.getInfoAsync(uri);
      if (!info.exists) return null;
      return await FileSystem.readAsStringAsync(uri, { encoding: FileSystem.EncodingType.Base64 });
    } catch (e) {
      console.warn(`[BackupManager] Failed to read file: ${uri}`, e);
      return null;
    }
  }

  private static async ensureDir(filePath: string) {
    const dir = filePath.substring(0, filePath.lastIndexOf('/'));
    const info = await FileSystem.getInfoAsync(dir);
    if (!info.exists) {
      await FileSystem.makeDirectoryAsync(dir, { intermediates: true });
    }
  }
}

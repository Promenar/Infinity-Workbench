
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
    };
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
                    password: config.password
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
        const keys = ['settings-storage-v2', 'chat-storage', 'api-storage-v2', 'agent-storage', 'spa-storage', 'theme_mode', 'backup_config', 'last_auto_backup_time'];
        const stores = await AsyncStorage.multiGet(keys);
        const asyncStorageData: Record<string, string> = {};

        stores.forEach(([key, value]) => {
            if (value) asyncStorageData[key] = value;
        });

        // 2. Export SQLite Tables
        // Helper to get all rows
        const getTableData = async (table: string) => {
            const res = await db.execute(`SELECT * FROM ${table}`);
            // @ts-ignore
            return res.rows?._array || [];
        };

        // We need to handle op-sqlite result structure carefully.
        // Assuming typical usage where `rows` is an array-like object or has _array.
        // For safety, let's select and iterate if needed, but select * is fine for moderate size.
        // For vectors, we explicitly need to handle BLOBs.

        const sessions = await this.safeQuery('SELECT * FROM sessions');
        const messages = await this.safeQuery('SELECT * FROM messages');
        const attachments = await this.safeQuery('SELECT * FROM attachments');
        const folders = await this.safeQuery('SELECT * FROM folders');
        const documents = await this.safeQuery('SELECT * FROM documents');

        // For vectors, we need to convert BLOB embeddings to Base64
        const vectorsRaw = await this.safeQuery('SELECT * FROM vectors');
        const vectors = vectorsRaw.map((v: any) => ({
            ...v,
            embedding: v.embedding ? Buffer.from(v.embedding).toString('base64') : null
        }));

        const context_summaries = await this.safeQuery('SELECT * FROM context_summaries');

        return {
            meta: {
                version: BACKUP_VERSION,
                timestamp: Date.now(),
                platform: 'nexara',
                schemaVersion: 1
            },
            asyncStorage: asyncStorageData,
            sqlite: {
                sessions,
                messages,
                attachments,
                folders,
                documents,
                vectors,
                context_summaries
            }
        };
    }

    /**
     * Import data from a backup object
     * WARNING: This overwrites existing data!
     */
    static async importData(backup: BackupData): Promise<void> {
        console.log('[BackupManager] Starting import...');

        if (backup.meta.version > BACKUP_VERSION) {
            throw new Error(`Backup version ${backup.meta.version} is newer than supported version ${BACKUP_VERSION}`);
        }

        try {
            // 1. Restore AsyncStorage
            // We accept whatever keys are in the backup
            const pairs: [string, string][] = Object.entries(backup.asyncStorage);
            if (pairs.length > 0) {
                await AsyncStorage.multiSet(pairs);
            }

            // 2. Restore SQLite
            // Transactional restore
            await db.execute('BEGIN TRANSACTION');

            // Clear existing tables (Order matters for foreign keys)
            // vectors -> documents/sessions
            // attachments -> messages
            // messages -> sessions
            // documents -> folders
            // folders -> folders
            await db.execute('DELETE FROM vectors');
            await db.execute('DELETE FROM attachments');
            await db.execute('DELETE FROM messages');
            await db.execute('DELETE FROM documents');
            await db.execute('DELETE FROM context_summaries');
            await db.execute('DELETE FROM sessions'); // Check circular deps? sessions usually root
            await db.execute('DELETE FROM folders');

            // Insert Data

            // Sessions
            await this.bulkInsert('sessions', backup.sqlite.sessions);

            // Folders
            await this.bulkInsert('folders', backup.sqlite.folders);

            // Documents
            await this.bulkInsert('documents', backup.sqlite.documents);

            // Messages
            await this.bulkInsert('messages', backup.sqlite.messages);

            // Attachments
            await this.bulkInsert('attachments', backup.sqlite.attachments);

            // Context Summaries
            await this.bulkInsert('context_summaries', backup.sqlite.context_summaries);

            // Vectors
            // Convert Base64 back to Blob/Buffer
            if (backup.sqlite.vectors && backup.sqlite.vectors.length > 0) {
                for (const v of backup.sqlite.vectors) {
                    const embeddingBlob = v.embedding ? Buffer.from(v.embedding, 'base64') : null;
                    // We need to construct the INSERT manually or use helper with pre-processed params

                    // Simple helper won't work easily with mixed Blob/Types for bulk insert in generic way
                    // Let's do a tailored loop for vectors to ensure BLOB correctness
                    await db.execute(
                        `INSERT INTO vectors (id, doc_id, session_id, content, embedding, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
                        [v.id, v.doc_id, v.session_id, v.content, embeddingBlob, v.metadata, v.created_at]
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
        // op-sqlite: res.rows is actually an array-like with .length and .item(i), OR _array depending on config
        // New versions expose raw array directly mostly
        if (Array.isArray(res.rows)) return res.rows;
        // @ts-ignore - _array is common in RN sqlite libs
        if (res.rows?._array) return res.rows._array;

        // Fallback iteration
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

        // Note: Building a massive single INSERT statement is risky for limits.
        // We'll do individual inserts for reliability, wrapped in the main transaction.
        // Or chunks.

        const keys = Object.keys(rows[0]);
        const columns = keys.join(', ');
        const placeholders = keys.map(() => '?').join(', ');
        const sql = `INSERT INTO ${table} (${columns}) VALUES (${placeholders})`;

        for (const row of rows) {
            const values = keys.map(k => row[k]);
            await db.execute(sql, values);
        }
    }

    static async getBackupSize(backup: BackupData): Promise<string> {
        const json = JSON.stringify(backup);
        const bytes = new TextEncoder().encode(json).length;
        return (bytes / 1024 / 1024).toFixed(2) + ' MB';
    }
}

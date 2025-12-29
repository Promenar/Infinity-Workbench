import { db } from '../db';
import { generateId } from '../utils/id-generator';

export interface VectorRecord {
    id: string;
    docId?: string;
    sessionId?: string;
    content: string;
    embedding: number[]; // stored as blob, handled as array here
    metadata?: Record<string, any>;
    createdAt: number;
}

export interface SearchResult extends VectorRecord {
    similarity: number;
}

export class VectorStore {
    /**
     * Convert float array to Buffer/Blob for SQLite storage
     */
    private toBlob(embedding: number[]): ArrayBuffer {
        return new Float32Array(embedding).buffer;
    }

    /**
     * Convert Blob back to float array
     */
    private fromBlob(blob: any): number[] {
        // op-sqlite returns blob as ArrayBuffer or keys depending on platform/config
        // Safest is to handle ArrayBuffer
        return Array.from(new Float32Array(blob));
    }

    async addVectors(vectors: Omit<VectorRecord, 'id' | 'createdAt'>[]): Promise<void> {
        try {
            await db.execute('BEGIN TRANSACTION');
            for (const vec of vectors) {
                const id = generateId();
                const createdAt = Date.now();
                const blob = this.toBlob(vec.embedding);
                const metadataStr = vec.metadata ? JSON.stringify(vec.metadata) : null;

                await db.execute(
                    'INSERT INTO vectors (id, doc_id, session_id, content, embedding, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
                    [id, vec.docId || null, vec.sessionId || null, vec.content, blob, metadataStr, createdAt]
                );
            }
            await db.execute('COMMIT');
        } catch (e) {
            await db.execute('ROLLBACK');
            throw e;
        } finally {
            // Optional: finalize statement if library requires it, op-sqlite manages this usually
        }
    }

    /**
     * Brute-force Cosine Similarity Search
     * For <10k-50k vectors, this is surprisingly fast in JS on modern devices.
     * 1. Fetch all candidate vectors (filter by session/doc/type)
     * 2. Calculate similarity in JS loop
     * 3. Sort and slice
     */
    async search(
        queryEmbedding: number[],
        options: {
            limit?: number;
            threshold?: number;
            filter?: { docId?: string; sessionId?: string; type?: string }
        } = {}
    ): Promise<SearchResult[]> {
        const Limit = options.limit || 5;
        const Threshold = options.threshold || 0.7;

        // Build Query
        let sql = 'SELECT * FROM vectors';
        const params: any[] = [];
        const conditions: string[] = [];

        if (options.filter?.docId) {
            conditions.push('doc_id = ?');
            params.push(options.filter.docId);
        }
        if (options.filter?.sessionId) {
            conditions.push('session_id = ?');
            params.push(options.filter.sessionId);
        }
        if (options.filter?.type) {
            // Check metadata json -> this is slower, avoid if possible or add index
            // Simple string check for now
            conditions.push(`json_extract(metadata, '$.type') = ?`);
            params.push(options.filter.type);
        }

        if (conditions.length > 0) {
            sql += ' WHERE ' + conditions.join(' AND ');
        }

        const results = await db.execute(sql, params);
        if (!results.rows) return [];

        // JS Calculation
        const candidates: SearchResult[] = [];
        // Pre-calculate query magnitude for optimization
        const queryMag = Math.sqrt(queryEmbedding.reduce((sum, val) => sum + val * val, 0));

        // @ts-ignore
        for (let i = 0; i < results.rows.length; i++) {
            // @ts-ignore
            const row = results.rows[i];
            const vec = this.fromBlob(row.embedding);

            const similarity = this.cosineSimilarity(queryEmbedding, vec, queryMag);

            if (similarity >= Threshold) {
                candidates.push({
                    id: row.id as string,
                    docId: row.doc_id as string | undefined,
                    sessionId: row.session_id as string | undefined,
                    content: row.content as string,
                    embedding: vec,
                    metadata: row.metadata ? JSON.parse(row.metadata as string) : undefined,
                    createdAt: row.created_at as number,
                    similarity
                });
            }
        }

        // Sort desc
        candidates.sort((a, b) => b.similarity - a.similarity);

        return candidates.slice(0, Limit);
    }

    private cosineSimilarity(vecA: number[], vecB: number[], magA?: number): number {
        if (vecA.length !== vecB.length) return 0;

        let dot = 0;
        let magB = 0;

        for (let i = 0; i < vecA.length; i++) {
            dot += vecA[i] * vecB[i];
            magB += vecB[i] * vecB[i];
        }

        const mA = magA || Math.sqrt(vecA.reduce((sum, v) => sum + v * v, 0));
        magB = Math.sqrt(magB);

        if (mA === 0 || magB === 0) return 0;
        return dot / (mA * magB);
    }

    async deleteDocumentVectors(docId: string) {
        await db.execute('DELETE FROM vectors WHERE doc_id = ?', [docId]);
    }

    async clearSessionMemory(sessionId: string) {
        await db.execute('DELETE FROM vectors WHERE session_id = ?', [sessionId]);
    }

    /**
     * Delete all vector memories that belong to sessions NOT in the active list.
     * This cleans up "ghost" data from previously deleted sessions.
     */
    async pruneOrphanSessions(activeSessionIds: string[]) {
        if (activeSessionIds.length === 0) {
            // If no active sessions, delete ALL memory vectors
            await db.execute("DELETE FROM vectors WHERE session_id IS NOT NULL AND json_extract(metadata, '$.type') = 'memory'");
            return;
        }

        // SQLite limit is usually 999 variables, so we should be careful.
        // If list is large, we do it in chunks or use a temporary table (overkill here).
        // Let's assume < 500 sessions.

        const placeholders = activeSessionIds.map(() => '?').join(',');

        // Delete vectors that HAVE a session_id AND that session_id is NOT in the active list
        await db.execute(
            `DELETE FROM vectors WHERE session_id IS NOT NULL AND session_id NOT IN (${placeholders})`,
            activeSessionIds
        );

        // Also clean up the sessions table (used for stats and foreign keys)
        // Ensure we don't accidentally delete super_assistant if it's not in the list (though it should be)
        await db.execute(
            `DELETE FROM sessions WHERE id IS NOT NULL AND id != 'super_assistant' AND id NOT IN (${placeholders})`,
            activeSessionIds
        );
    }
}

export const vectorStore = new VectorStore();

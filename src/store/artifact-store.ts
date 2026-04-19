/**
 * Artifact Store
 * 管理所有Artifacts的状态和持久化
 */

import { create } from 'zustand';
import { db } from '../lib/db';
import { generateId } from '../lib/utils/id-generator';
import {
    Artifact,
    ArtifactFilter,
    ArtifactSortField,
    ArtifactSortOrder,
    CreateArtifactParams,
    UpdateArtifactParams,
} from '../types/artifact';

interface ArtifactState {
    artifacts: Artifact[];
    filteredArtifacts: Artifact[];
    filter: ArtifactFilter;
    loading: boolean;
    error: string | null;

    // Actions
    loadArtifacts: () => Promise<void>;
    addArtifact: (params: CreateArtifactParams) => Promise<Artifact>;
    updateArtifact: (id: string, updates: UpdateArtifactParams) => Promise<void>;
    removeArtifact: (id: string) => Promise<void>;
    setFilter: (filter: ArtifactFilter) => void;
    clearFilter: () => void;
    getArtifactsBySession: (sessionId: string) => Artifact[];
    getArtifactsByMessage: (messageId: string) => Artifact[];
    searchArtifacts: (query: string) => Promise<Artifact[]>;
    getArtifactsByType: (type: string) => Artifact[];
}

/**
 * 将数据库行转换为Artifact对象
 */
function rowToArtifact(row: any): Artifact {
    let tags: string[] = [];
    if (row.tags) {
        try {
            tags = JSON.parse(row.tags);
        } catch {
            tags = [];
        }
    }

    return {
        id: row.id,
        type: row.type,
        title: row.title,
        content: row.content,
        previewImage: row.preview_image ?? undefined,
        sessionId: row.session_id,
        messageId: row.message_id,
        workspacePath: row.workspace_path || undefined,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        tags,
    };
}

/**
 * 应用筛选条件和排序
 */
function applyFilter(artifacts: Artifact[], filter: ArtifactFilter): Artifact[] {
    let result = [...artifacts];

    if (filter.type) {
        result = result.filter(a => a.type === filter.type);
    }

    if (filter.sessionId) {
        result = result.filter(a => a.sessionId === filter.sessionId);
    }

    if (filter.workspacePath) {
        result = result.filter(a => a.workspacePath === filter.workspacePath);
    }

    if (filter.searchQuery) {
        const query = filter.searchQuery.toLowerCase();
        result = result.filter(
            a =>
                a.title.toLowerCase().includes(query) ||
                a.content.toLowerCase().includes(query) ||
                (a.tags && a.tags.some(t => t.toLowerCase().includes(query)))
        );
    }

    if (filter.dateFrom) {
        result = result.filter(a => a.createdAt >= filter.dateFrom!);
    }

    if (filter.dateTo) {
        result = result.filter(a => a.createdAt <= filter.dateTo!);
    }

    // 标签筛选
    if (filter.tags && filter.tags.length > 0) {
        result = result.filter(a =>
            a.tags && filter.tags!.some(ft => a.tags!.includes(ft))
        );
    }

    // 排序
    const sortBy: ArtifactSortField = filter.sortBy || 'createdAt';
    const sortOrder: ArtifactSortOrder = filter.sortOrder || 'desc';

    result.sort((a, b) => {
        let cmp = 0;
        switch (sortBy) {
            case 'title':
                cmp = a.title.localeCompare(b.title);
                break;
            case 'type':
                cmp = a.type.localeCompare(b.type);
                break;
            case 'updatedAt':
                cmp = a.updatedAt - b.updatedAt;
                break;
            case 'createdAt':
            default:
                cmp = a.createdAt - b.createdAt;
                break;
        }
        return sortOrder === 'desc' ? -cmp : cmp;
    });

    return result;
}

export const useArtifactStore = create<ArtifactState>((set, get) => ({
    artifacts: [],
    filteredArtifacts: [],
    filter: {},
    loading: false,
    error: null,

    loadArtifacts: async () => {
        set({ loading: true, error: null });
        try {
            const result = await db.execute(
                'SELECT * FROM artifacts ORDER BY created_at DESC'
            );

            const artifacts: Artifact[] = [];
            if (result.rows) {
                for (let i = 0; i < result.rows.length; i++) {
                    artifacts.push(rowToArtifact(result.rows[i]));
                }
            }

            const filteredArtifacts = applyFilter(artifacts, get().filter);
            set({ artifacts, filteredArtifacts, loading: false });
        } catch (e: any) {
            console.error('[ArtifactStore] Failed to load artifacts:', e);
            set({ error: e.message || 'Failed to load artifacts', loading: false });
        }
    },

    addArtifact: async (params: CreateArtifactParams): Promise<Artifact> => {
        const id = generateId();
        const now = Date.now();

        const artifact: Artifact = {
            id,
            type: params.type,
            title: params.title || `${params.type}-${now}`,
            content: params.content,
            previewImage: params.previewImage,
            sessionId: params.sessionId,
            messageId: params.messageId,
            workspacePath: params.workspacePath,
            createdAt: now,
            updatedAt: now,
            tags: params.tags,
        };

        try {
            await db.execute(
                `INSERT INTO artifacts (id, type, title, content, preview_image, session_id, message_id, workspace_path, created_at, updated_at, tags)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
                [
                    artifact.id,
                    artifact.type,
                    artifact.title,
                    artifact.content,
                    artifact.previewImage || null,
                    artifact.sessionId,
                    artifact.messageId,
                    artifact.workspacePath || null,
                    artifact.createdAt,
                    artifact.updatedAt,
                    artifact.tags ? JSON.stringify(artifact.tags) : null,
                ]
            );

            set(state => {
                const artifacts = [artifact, ...state.artifacts];
                const filteredArtifacts = applyFilter(artifacts, state.filter);
                return { artifacts, filteredArtifacts };
            });

            return artifact;
        } catch (e: any) {
            console.error('[ArtifactStore] Failed to add artifact:', e);
            throw e;
        }
    },

    updateArtifact: async (id: string, updates: UpdateArtifactParams): Promise<void> => {
        const now = Date.now();

        try {
            const setClauses: string[] = ['updated_at = ?'];
            const values: any[] = [now];

            if (updates.title !== undefined) {
                setClauses.push('title = ?');
                values.push(updates.title);
            }
            if (updates.content !== undefined) {
                setClauses.push('content = ?');
                values.push(updates.content);
            }
            if (updates.previewImage !== undefined) {
                setClauses.push('preview_image = ?');
                values.push(updates.previewImage || null);
            }
            if (updates.tags !== undefined) {
                setClauses.push('tags = ?');
                values.push(JSON.stringify(updates.tags));
            }

            values.push(id);

            await db.execute(
                `UPDATE artifacts SET ${setClauses.join(', ')} WHERE id = ?`,
                values
            );

            set(state => {
                const artifacts = state.artifacts.map(a =>
                    a.id === id
                        ? { ...a, ...updates, updatedAt: now }
                        : a
                );
                const filteredArtifacts = applyFilter(artifacts, state.filter);
                return { artifacts, filteredArtifacts };
            });
        } catch (e: any) {
            console.error('[ArtifactStore] Failed to update artifact:', e);
            throw e;
        }
    },

    removeArtifact: async (id: string): Promise<void> => {
        try {
            await db.execute('DELETE FROM artifacts WHERE id = ?', [id]);

            set(state => {
                const artifacts = state.artifacts.filter(a => a.id !== id);
                const filteredArtifacts = applyFilter(artifacts, state.filter);
                return { artifacts, filteredArtifacts };
            });
        } catch (e: any) {
            console.error('[ArtifactStore] Failed to remove artifact:', e);
            throw e;
        }
    },

    setFilter: (filter: ArtifactFilter) => {
        set(state => ({
            filter,
            filteredArtifacts: applyFilter(state.artifacts, filter),
        }));
    },

    clearFilter: () => {
        set(state => ({
            filter: {},
            filteredArtifacts: state.artifacts,
        }));
    },

    getArtifactsBySession: (sessionId: string): Artifact[] => {
        return get().artifacts.filter(a => a.sessionId === sessionId);
    },

    getArtifactsByMessage: (messageId: string): Artifact[] => {
        return get().artifacts.filter(a => a.messageId === messageId);
    },

    /**
     * FTS5 全文搜索 Artifact（带 LIKE 回退）
     * 支持在标题和内容中搜索
     */
    searchArtifacts: async (query: string): Promise<Artifact[]> => {
        if (!query.trim()) return get().artifacts;

        const searchTerm = query.trim();
        const likeParam = `%${searchTerm}%`;

        try {
            // 尝试 FTS5 搜索（需要 artifacts_fts 虚拟表）
            const ftsResult = await db.execute(
                `SELECT a.* FROM artifacts a
                 JOIN artifacts_fts fts ON a.id = fts.id
                 WHERE artifacts_fts MATCH ?
                 ORDER BY rank
                 LIMIT 100`,
                [searchTerm]
            );

            if (ftsResult.rows && ftsResult.rows.length > 0) {
                return ftsResult.rows.map(rowToArtifact);
            }
        } catch {
            // FTS5 不可用，回退到 LIKE
        }

        // LIKE 回退搜索
        try {
            const result = await db.execute(
                `SELECT * FROM artifacts
                 WHERE title LIKE ? OR content LIKE ?
                 ORDER BY created_at DESC
                 LIMIT 100`,
                [likeParam, likeParam]
            );

            if (result.rows) {
                return result.rows.map(rowToArtifact);
            }
        } catch (e: any) {
            console.warn('[ArtifactStore] Search failed:', e?.message);
        }

        return [];
    },

    getArtifactsByType: (type: string): Artifact[] => {
        return get().artifacts.filter(a => a.type === type);
    },
}));

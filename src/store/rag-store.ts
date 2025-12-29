import { create } from 'zustand';
import { db } from '../lib/db';
import { generateId } from '../lib/utils/id-generator';
import { VectorizationQueue } from '../lib/rag/vectorization-queue';
import { RagDocument, RagFolder, VectorizationTask } from '../types/rag';

// 创建全局队列实例
let queueInstance: VectorizationQueue | null = null;

interface RagState {
    documents: RagDocument[];
    folders: RagFolder[];

    // 向量化队列状态
    vectorizationQueue: VectorizationTask[];
    currentTask: VectorizationTask | null;

    // UI状态
    expandedFolders: Set<string>;
    selectedFolder: string | null;

    isLoading: boolean;

    // 文档操作
    loadDocuments: () => Promise<void>;
    addDocument: (title: string, content: string, fileSize: number, type?: 'text' | 'note', folderId?: string) => Promise<void>;
    deleteDocument: (id: string) => Promise<void>;
    vectorizeDocument: (docId: string) => Promise<void>;

    // 文件夹操作
    loadFolders: () => Promise<void>;
    addFolder: (name: string, parentId?: string) => Promise<void>;
    deleteFolder: (id: string) => Promise<void>;
    renameFolder: (id: string, name: string) => Promise<void>;
    moveFolder: (folderId: string, parentId: string | null) => Promise<void>;
    moveDocument: (docId: string, folderId: string | null) => Promise<void>;
    toggleFolder: (id: string) => void;

    // 批量操作
    vectorizeBatch: (docIds: string[]) => Promise<void>;
    deleteBatch: (docIds: string[]) => Promise<void>;

    // 筛选
    setSelectedFolder: (folderId: string | null) => void;
    getDocumentsByFolder: (folderId: string | null) => RagDocument[];

    // 内部状态更新
    _updateQueueState: (queue: VectorizationTask[], current: VectorizationTask | null) => void;
}

export const useRagStore = create<RagState>((set, get) => {
    // 初始化队列（懒加载）
    const getQueue = () => {
        if (!queueInstance) {
            queueInstance = new VectorizationQueue((queue, current) => {
                get()._updateQueueState(queue, current);
            });
        }
        return queueInstance;
    };

    return {
        documents: [],
        folders: [],
        vectorizationQueue: [],
        currentTask: null,
        expandedFolders: new Set(),
        selectedFolder: null,
        isLoading: false,

        loadDocuments: async () => {
            set({ isLoading: true });
            try {
                const results = await db.execute('SELECT * FROM documents ORDER BY created_at DESC');
                if (!results.rows) {
                    set({ documents: [], isLoading: false });
                    return;
                }

                const docs: RagDocument[] = [];
                for (let i = 0; i < results.rows.length; i++) {
                    const row = results.rows[i];
                    const folderIdValue = row.folder_id as string | null;
                    docs.push({
                        id: row.id as string,
                        title: row.title as string,
                        content: row.content as string,
                        source: (row.source as string) || 'import',
                        type: (row.type as string) || 'text',
                        folderId: folderIdValue === null ? undefined : folderIdValue,
                        vectorized: (row.vectorized as number || 0) as 0 | 1 | 2 | -1,
                        vectorCount: (row.vector_count as number) || 0,
                        fileSize: (row.file_size as number) || 0,
                        createdAt: row.created_at as number,
                        updatedAt: row.updated_at as number | undefined
                    });
                }

                set({ documents: docs, isLoading: false });
            } catch (e) {
                console.error('Failed to load documents:', e);
                set({ isLoading: false });
            }
        },

        addDocument: async (title, content, fileSize, type = 'text', folderId) => {
            const docId = generateId();
            const createdAt = Date.now();

            try {
                // 1. 保存文档到数据库（vectorized = 0, 未处理）
                await db.execute(
                    'INSERT INTO documents (id, title, content, source, type, folder_id, vectorized, file_size, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
                    [docId, title, content, 'import', type, folderId || null, 0, fileSize, createdAt, createdAt]
                );

                const newDoc: RagDocument = {
                    id: docId,
                    title,
                    content,
                    source: 'import',
                    type,
                    folderId,
                    vectorized: 0,
                    vectorCount: 0,
                    fileSize,
                    createdAt
                };

                set(state => ({ documents: [newDoc, ...state.documents] }));

                // 2. 加入向量化队列（后台异步处理）
                const queue = getQueue();
                await queue.enqueue(docId, title, content);

                // 3. 刷新文件夹计数
                get().loadFolders();

            } catch (e) {
                console.error('Failed to add document:', e);
                throw e;
            }
        },

        vectorizeDocument: async (docId) => {
            const doc = get().documents.find(d => d.id === docId);
            if (!doc) return;

            const queue = getQueue();
            await queue.enqueue(docId, doc.title, doc.content);
        },

        deleteDocument: async (id) => {
            try {
                await db.execute('DELETE FROM documents WHERE id = ?', [id]);
                set(state => ({
                    documents: state.documents.filter(d => d.id !== id)
                }));
                get().loadFolders();
            } catch (e) {
                console.error('Failed to delete document:', e);
                throw e;
            }
        },

        loadFolders: async () => {
            try {
                // 1. Load all folders
                const results = await db.execute('SELECT * FROM folders ORDER BY created_at ASC');
                if (!results.rows) {
                    set({ folders: [] });
                    return;
                }

                const rawFolders = results.rows as any[];

                // 2. Load all document counts by folder
                const docCountResults = await db.execute('SELECT folder_id, COUNT(*) as count FROM documents GROUP BY folder_id');
                const docCounts: Record<string, number> = {};
                if (docCountResults.rows) {
                    for (let i = 0; i < docCountResults.rows.length; i++) {
                        const row = docCountResults.rows[i];
                        if (row.folder_id) {
                            docCounts[row.folder_id as string] = row.count as number;
                        }
                    }
                }

                // 3. Build tree map to calculate recursive counts
                const folderMap = new Map<string, { id: string; parentId?: string; directCount: number; totalCount: number; children: string[] }>();

                // Initialize map
                rawFolders.forEach(row => {
                    const id = row.id as string;
                    folderMap.set(id, {
                        id,
                        parentId: row.parent_id as string || undefined,
                        directCount: docCounts[id] || 0,
                        totalCount: 0,
                        children: []
                    });
                });

                // Build hierarchy
                folderMap.forEach(folder => {
                    if (folder.parentId && folderMap.has(folder.parentId)) {
                        folderMap.get(folder.parentId)!.children.push(folder.id);
                    }
                });

                // Recursive function to calculate total counts
                const calculateTotal = (folderId: string): number => {
                    const folder = folderMap.get(folderId);
                    if (!folder) return 0;

                    let sum = folder.directCount;
                    folder.children.forEach(childId => {
                        sum += calculateTotal(childId);
                    });

                    folder.totalCount = sum;
                    return sum;
                };

                // Calculate for all roots (and inherently all nodes)
                folderMap.forEach(folder => {
                    // We can just trigger calculation for everyone, memoization would be optimal but simple recursion is fine for small trees.
                    // Actually, to avoid re-calc, we should start from roots.
                    // Simpler: iterate all, if totalCount is 0 (uncalculated? no, count can be 0), we need a flag.
                    // But since we want to populate ALL, let's just do a post-order traversal logic efficiently?
                    // Or just lazy:
                });

                // Top-down trigger? No, we need bottom-up.
                // Reset totalCounts first (already 0).

                // Let's implement a clean "compute all"
                // Since it's a tree/forest, we can find roots and traverse.
                const roots = Array.from(folderMap.values()).filter(f => !f.parentId);
                const computed = new Set<string>();

                const compute = (folder: { id: string; parentId?: string; directCount: number; totalCount: number; children: string[] }): number => {
                    let sum = folder.directCount;
                    for (const childId of folder.children) {
                        const child = folderMap.get(childId);
                        if (child) {
                            sum += compute(child);
                        }
                    }
                    folder.totalCount = sum;
                    return sum;
                };

                roots.forEach(root => compute(root));

                // 4. Transform to state objects
                const folders: RagFolder[] = rawFolders.map(row => {
                    const id = row.id as string;
                    const node = folderMap.get(id);
                    return {
                        id,
                        name: row.name as string,
                        parentId: row.parent_id as string || undefined,
                        childCount: node ? node.totalCount : 0,
                        createdAt: row.created_at as number
                    };
                });

                set({ folders });
                console.log(`[RagStore] Folders loaded with recursive counts: ${folders.length}`);
            } catch (e) {
                console.error('Failed to load folders:', e);
            }
        },

        addFolder: async (name, parentId) => {
            const folderId = generateId();
            const createdAt = Date.now();

            try {
                await db.execute(
                    'INSERT INTO folders (id, name, parent_id, created_at) VALUES (?, ?, ?, ?)',
                    [folderId, name, parentId || null, createdAt]
                );

                const newFolder: RagFolder = {
                    id: folderId,
                    name,
                    parentId,
                    childCount: 0,
                    createdAt
                };

                set(state => ({ folders: [...state.folders, newFolder] }));
            } catch (e) {
                console.error('Failed to add folder:', e);
                throw e;
            }
        },

        deleteFolder: async (id) => {
            try {
                // 删除文件夹（会级联删除子文件夹，但文档会设置folder_id为NULL）
                await db.execute('DELETE FROM folders WHERE id = ?', [id]);
                set(state => ({
                    folders: state.folders.filter(f => f.id !== id)
                }));

                // 重新加载文档以更新folder_id
                get().loadDocuments();
            } catch (e) {
                console.error('Failed to delete folder:', e);
                throw e;
            }
        },

        renameFolder: async (id, name) => {
            try {
                await db.execute(
                    'UPDATE folders SET name = ? WHERE id = ?',
                    [name, id]
                );
                set(state => ({
                    folders: state.folders.map(f =>
                        f.id === id ? { ...f, name } : f
                    )
                }));
            } catch (e) {
                console.error('Failed to rename folder:', e);
                throw e;
            }
        },

        moveFolder: async (folderId, parentId) => {
            try {
                await db.execute(
                    'UPDATE folders SET parent_id = ? WHERE id = ?',
                    [parentId || null, folderId]
                );
                set(state => ({
                    folders: state.folders.map(f =>
                        f.id === folderId ? { ...f, parentId: parentId || undefined } : f
                    )
                }));
            } catch (e) {
                console.error('Failed to move folder:', e);
                throw e;
            }
        },

        toggleFolder: (id) => {
            set(state => {
                const expanded = new Set(state.expandedFolders);
                if (expanded.has(id)) {
                    expanded.delete(id);
                } else {
                    expanded.add(id);
                }
                return { expandedFolders: expanded };
            });
        },

        moveDocument: async (docId, folderId) => {
            try {
                await db.execute(
                    'UPDATE documents SET folder_id = ? WHERE id = ?',
                    [folderId, docId]
                );

                // 更新本地状态
                set(state => ({
                    documents: state.documents.map(d =>
                        d.id === docId ? { ...d, folderId: folderId || undefined } : d
                    )
                }));
                get().loadFolders();
            } catch (e) {
                console.error('Failed to move document:', e);
                throw e;
            }
        },

        vectorizeBatch: async (docIds) => {
            const queue = getQueue();
            const docs = get().documents.filter(d => docIds.includes(d.id));

            for (const doc of docs) {
                await queue.enqueue(doc.id, doc.title, doc.content);
            }
        },

        deleteBatch: async (docIds) => {
            try {
                for (const id of docIds) {
                    await db.execute('DELETE FROM documents WHERE id = ?', [id]);
                }

                set(state => ({
                    documents: state.documents.filter(d => !docIds.includes(d.id))
                }));
                get().loadFolders();
            } catch (e) {
                console.error('Failed to delete batch:', e);
                throw e;
            }
        },

        setSelectedFolder: (folderId) => {
            set({ selectedFolder: folderId });
        },

        getDocumentsByFolder: (folderId) => {
            const docs = get().documents;
            if (folderId === null) {
                // 返回所有未分类文档
                return docs.filter(d => !d.folderId);
            }
            return docs.filter(d => d.folderId === folderId);
        },

        _updateQueueState: (queue, current) => {
            const prevQueueLength = get().vectorizationQueue.length;
            set({ vectorizationQueue: queue, currentTask: current });

            // 如果队列长度减少（任务完成），重新加载文档列表
            if (queue.length < prevQueueLength || (current === null && prevQueueLength > 0)) {
                setTimeout(() => get().loadDocuments(), 200);
            }
        }
    };
});

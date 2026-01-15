import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { db } from '../lib/db';
import { generateId } from '../lib/utils/id-generator';
import { VectorizationQueue } from '../lib/rag/vectorization-queue';
import { RagDocument, RagFolder, VectorizationTask, RagMemory } from '../types/rag';
import { graphStore } from '../lib/rag/graph-store';
import * as FileSystem from 'expo-file-system/legacy';

// 🛡️ 物理沙箱根路径
const RAW_SANDBOX_ROOT = (FileSystem as any).documentDirectory || (FileSystem as any).cacheDirectory;
const SANDBOX_ROOT = RAW_SANDBOX_ROOT + 'agent_sandbox/';
const WORKSPACE_NAME = 'workspace';
const WORKSPACE_TAG = 'workspace'; // 内部标识符
const LEGACY_WORKSPACE_NAME = '工作区'; // 旧版中文名称（迁移用）

let isEnsuringWorkspace = false;

// 创建全局队列实例
let queueInstance: VectorizationQueue | null = null;

interface RagState {
  documents: RagDocument[];
  folders: RagFolder[];
  memories: RagMemory[];

  // 向量化队列状态
  vectorizationQueue: VectorizationTask[];
  currentTask: VectorizationTask | null;

  // UI状态
  expandedFolders: Set<string>;
  selectedFolder: string | null;

  isLoading: boolean;

  // 文档操作
  loadDocuments: () => Promise<void>;
  addDocument: (
    title: string,
    content: string,
    fileSize: number,
    type?: 'text' | 'note' | 'image',
    folderId?: string,
    thumbnailPath?: string,
  ) => Promise<void>;
  deleteDocument: (id: string) => Promise<void>;
  getDocumentContent: (id: string) => Promise<string>;
  updateDocumentContent: (id: string, content: string) => Promise<void>;
  vectorizeDocument: (docId: string) => Promise<void>;
  extractDocumentGraph: (docId: string, strategy: 'full' | 'summary-first') => Promise<void>;
  toggleDocumentGlobal: (docId: string) => Promise<void>;
  loadMemories: () => Promise<void>;
  deleteMemory: (id: string) => Promise<void>;

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
  extractBatch: (docIds: string[], strategy: 'full' | 'summary-first') => Promise<void>;
  deleteBatch: (docIds: string[]) => Promise<void>;

  // 筛选
  setSelectedFolder: (folderId: string | null) => void;
  getDocumentsByFolder: (folderId: string | null) => RagDocument[];

  // Tag Operations
  availableTags: Array<{ id: string; name: string; color: string }>;
  loadAvailableTags: () => Promise<void>;
  createTag: (name: string, color?: string) => Promise<void>;
  deleteTag: (tagId: string) => Promise<void>;
  addTagToDocument: (docId: string, tagId: string) => Promise<void>;
  removeTagFromDocument: (docId: string, tagId: string) => Promise<void>;

  // 内部状态更新
  _updateQueueState: (queue: VectorizationTask[], current: VectorizationTask | null) => void;

  // RAG 运行中状态管理 (指示器逻辑 - 全局单任务)
  processingState: {
    sessionId?: string;
    activeMessageId?: string;
    status: 'idle' | 'chunking' | 'summarizing' | 'vectorizing' | 'archived' | 'summarized' | 'completed' | 'retrieved' | 'error' | 'retrieving';
    stage?: 'rewriting' | 'embedding' | 'searching' | 'kg_searching' | 'reranking' | 'done';
    subStage?: string; // 原子化步骤: INTENT, API_TX, LOCAL_SCAN, etc.
    progress?: number;
    networkStats?: {
      txBytes?: number;
      rxBytes?: number;
      latency?: number;
    };
    startTime?: number;
    summary?: string;
    chunks: string[];
    pulseActive?: boolean; // 用于全局摘要脉冲
    kgStatus?: 'idle' | 'extracting' | 'completed' | 'error'; // 知识图谱抽取状态
    kgProgress?: number;
  };
  processingHistory: {
    [messageId: string]: {
      type: 'archived' | 'summarized' | 'retrieved';
      summary?: string;
      timestamp: number;
      chunkCount?: number;
      ragMetadata?: any;
    };
  };
  updateProcessingState: (
    state: Partial<RagState['processingState']>,
    messageId?: string,
  ) => void;
  setGlobalPulse: (active: boolean) => void;

  // 队列控制
  cancelVectorization: (docId: string) => void;
  clearVectorizationQueue: () => void;

  // 统计信息
  getVectorStats: () => { totalDocs: number; totalVectors: number; totalSize: number };

  // 🛡️ 内部物理同步支持
  _getPhysicalPath: (folderId?: string | null) => Promise<string>;
  _ensureWorkspace: () => Promise<void>;
}

export const useRagStore = create<RagState>()(
  persist(
    (set, get) => {
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
        memories: [],
        vectorizationQueue: [],
        currentTask: null,
        expandedFolders: new Set(),
        selectedFolder: null,
        isLoading: false,

        // 🛡️ 物理路径助手函数
        _getPhysicalPath: async (folderId?: string | null): Promise<string> => {
          if (!folderId) return SANDBOX_ROOT;

          const pathParts: string[] = [];
          let currentId: string | undefined = folderId || undefined;
          const allFolders = get().folders;

          let safetyCount = 0;
          while (currentId && safetyCount < 20) {
            const folder = allFolders.find(f => f.id === currentId);
            if (folder) {
              pathParts.unshift(folder.name);
              currentId = folder.parentId;
              safetyCount++;
            } else {
              break;
            }
          }

          return SANDBOX_ROOT + pathParts.join('/') + '/';
        },

        // 🛡️ 确保工作区目录存在
        _ensureWorkspace: async () => {
          if (isEnsuringWorkspace) return;
          isEnsuringWorkspace = true;
          try {
            // 1. 检查是否存在旧版中文名称的文件夹记录 (SSOT: 直接查询数据库避免状态滞后)
            const legacyResult = await db.execute('SELECT * FROM folders WHERE name = ? LIMIT 1', [LEGACY_WORKSPACE_NAME]);
            const legacyWorkspace = legacyResult.rows && legacyResult.rows.length > 0 ? (legacyResult.rows[0] as any) : null;

            if (legacyWorkspace) {
              console.log('[RAG] Migrating legacy workspace folder to English...');
              await db.execute('UPDATE folders SET name = ? WHERE id = ?', [WORKSPACE_NAME, legacyWorkspace.id]);

              const oldPath = SANDBOX_ROOT + LEGACY_WORKSPACE_NAME;
              const newPath = SANDBOX_ROOT + WORKSPACE_NAME;
              const oldInfo = await FileSystem.getInfoAsync(oldPath);
              if (oldInfo.exists) {
                await FileSystem.moveAsync({ from: oldPath, to: newPath });
              }
              // 不需要 return，继续检查并确保物理目录存在
            }

            // 2. 检查新版工作区是否存在
            const wsResult = await db.execute('SELECT * FROM folders WHERE name = ? AND parent_id IS NULL LIMIT 1', [WORKSPACE_NAME]);
            let workspace = wsResult.rows && wsResult.rows.length > 0 ? (wsResult.rows[0] as any) : null;

            if (!workspace) {
              console.log('[RAG] Initializing workspace folder...');
              const id = generateId();
              const createdAt = Date.now();
              await db.execute(
                'INSERT INTO folders (id, name, parent_id, created_at) VALUES (?, ?, ?, ?)',
                [id, WORKSPACE_NAME, null, createdAt]
              );
            }

            // 无论如何确保目录存在
            const workspacePath = SANDBOX_ROOT + WORKSPACE_NAME;
            const wsInfo = await FileSystem.getInfoAsync(workspacePath);
            if (!wsInfo.exists) {
              await FileSystem.makeDirectoryAsync(workspacePath, { intermediates: true });
            }
          } catch (e) {
            console.error('[RAG] Workspace check failed:', e);
          } finally {
            isEnsuringWorkspace = false;
          }
        },

        loadDocuments: async () => {
          set({ isLoading: true });
          try {
            // Use JOIN to get tags efficiently. Exclude full 'content' to prevent OOM in Debug mode.
            const results = await db.execute(`
                    SELECT d.id, d.title, d.source, d.type, d.folder_id, d.vectorized, d.vector_count, d.file_size, d.created_at, d.updated_at, d.thumbnail_path, d.is_global,
                           GROUP_CONCAT(t.id || ':' || t.name || ':' || t.color, '|') as tag_list 
                    FROM documents d 
                    LEFT JOIN document_tags dt ON d.id = dt.doc_id 
                    LEFT JOIN tags t ON dt.tag_id = t.id 
                    GROUP BY d.id 
                    ORDER BY d.created_at DESC
                `);

            if (!results.rows) {
              set({ documents: [], isLoading: false });
              return;
            }

            const docs: RagDocument[] = [];
            for (let i = 0; i < results.rows.length; i++) {
              const row = results.rows[i] as any;
              const folderIdValue = row.folder_id as string | null;

              // Parse Tags
              let tags: Array<{ id: string; name: string; color: string }> = [];
              if (row.tag_list) {
                tags = (row.tag_list as string).split('|').map((tagStr) => {
                  const [id, name, color] = tagStr.split(':');
                  return { id, name, color };
                });
              }

              docs.push({
                id: row.id as string,
                title: row.title as string,
                content: '', // Content excluded from list view for performance
                source: (row.source as string) || 'import',
                type: (row.type as string) || 'text',
                folderId: folderIdValue === null ? undefined : folderIdValue,
                vectorized: ((row.vectorized as number) || 0) as 0 | 1 | 2 | -1,
                vectorCount: (row.vector_count as number) || 0,
                fileSize: (row.file_size as number) || 0,
                createdAt: row.created_at as number,
                updatedAt: row.updated_at as number | undefined,
                thumbnailPath: row.thumbnail_path as string | undefined,
                isGlobal: !!row.is_global,
                tags,
              });
            }

            set({ documents: docs, isLoading: false });
          } catch (e) {
            console.error('Failed to load documents:', e);
            set({ isLoading: false });
          }
        },

        addDocument: async (
          title: string,
          content: string,
          fileSize: number,
          type: 'text' | 'note' | 'image' = 'text',
          folderId?: string,
          thumbnailPath?: string,
        ) => {
          // Default to non-global unless specified (UI will implement toggle)
          // For now, if uploaded via Super Assistant, we might want global.
          // We'll leave default as 0 (false).
          const docId = generateId();
          const createdAt = Date.now();

          try {
            // 1. 保存文档到数据库（vectorized = 0, 未处理）
            await db.execute(
              'INSERT INTO documents (id, title, content, source, type, folder_id, vectorized, file_size, created_at, updated_at, thumbnail_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
              [
                docId,
                title,
                content,
                'import',
                type,
                folderId || null,
                0,
                fileSize,
                createdAt,
                createdAt,
                thumbnailPath || null,
              ],
            );

            const newDoc: RagDocument = {
              id: docId,
              title,
              content: '', // Exclude content from state
              source: 'import',
              type,
              folderId,
              vectorized: 0,
              vectorCount: 0,
              fileSize,

              createdAt,
              thumbnailPath,
            };

            set((state: RagState) => ({ documents: [newDoc, ...state.documents] }));

            // 🛡️ 同步物理文件
            const physicalDir = await get()._getPhysicalPath(folderId);
            await FileSystem.makeDirectoryAsync(physicalDir, { intermediates: true });
            await FileSystem.writeAsStringAsync(physicalDir + title, content, {
              encoding: (FileSystem as any).EncodingType.UTF8
            });

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

        getDocumentContent: async (docId: string): Promise<string> => {
          // Fetch content from database for viewing/editing
          const result = await db.execute('SELECT content FROM documents WHERE id = ?', [docId]);
          if (result.rows && result.rows.length > 0) {
            return (result.rows[0] as any).content;
          }
          return '';
        },

        updateDocumentContent: async (docId: string, content: string): Promise<void> => {
          try {
            const doc = get().documents.find(d => d.id === docId);
            if (!doc) throw new Error('Document not found');

            // 1. Update DB
            await db.execute('UPDATE documents SET content = ?, updated_at = ? WHERE id = ?', [content, Date.now(), docId]);

            // 2. Update physical file
            const physicalDir = await get()._getPhysicalPath(doc.folderId);
            await FileSystem.writeAsStringAsync(physicalDir + doc.title, content, {
              encoding: (FileSystem as any).EncodingType.UTF8
            });

            // 3. Re-vectorize if already vectorized or failed (automatic re-queue)
            if (doc.vectorized === 2 || doc.vectorized === -1) {
              await get().vectorizeDocument(docId);
            }
          } catch (e) {
            console.error('Failed to update document content:', e);
            throw e;
          }
        },

        vectorizeDocument: async (docId: string) => {
          const doc = get().documents.find((d) => d.id === docId);
          if (!doc) return;

          const queue = getQueue();
          await queue.enqueue(docId, doc.title, doc.content);
        },

        extractDocumentGraph: async (docId: string, strategy: 'full' | 'summary-first') => {
          const doc = get().documents.find((d) => d.id === docId);
          if (!doc) return;

          const queue = getQueue();
          // Enqueue with specific KG strategy
          await queue.enqueue(docId, doc.title, doc.content, strategy);
        },

        toggleDocumentGlobal: async (docId: string) => {
          try {
            let newIsGlobal: boolean | undefined;

            set((state: RagState) => {
              const doc = state.documents.find((d: RagDocument) => d.id === docId);
              if (!doc) return state;

              newIsGlobal = !doc.isGlobal; // Capture the new value for DB update
              return {
                documents: state.documents.map((d: RagDocument) => (d.id === docId ? { ...d, isGlobal: newIsGlobal } : d)),
              };
            });

            if (newIsGlobal !== undefined) {
              const newVal = newIsGlobal ? 1 : 0;
              await db.execute('UPDATE documents SET is_global = ? WHERE id = ?', [newVal, docId]);
            }

            // Note: Changing scope might require re-extraction if we want to move nodes from Global to Private/None.
            // For now, we assume this flag controls FUTURE extraction or query scope.
            // If we want to reflect changes immediately in KG, we'd need to re-process.
            // Let's just update the flag for now. The user can "Re-vectorize" if needed.
          } catch (e) {
            console.error('Failed to toggle document global status:', e);
            throw e;
          }
        },

        deleteDocument: async (id: string) => {
          try {
            // 删除文档记录
            await db.execute('DELETE FROM documents WHERE id = ?', [id]);
            // 删除对应的向量数据
            await db.execute('DELETE FROM vectors WHERE doc_id = ?', [id]);
            // 删除知识图谱边
            await db.execute('DELETE FROM kg_edges WHERE doc_id = ?', [id]);
            // 清理孤立节点 (没有边连接的节点)
            await db.execute(`
                    DELETE FROM kg_nodes 
                    WHERE id NOT IN (SELECT source_id FROM kg_edges) 
                    AND id NOT IN (SELECT target_id FROM kg_edges)
                `);

            set((state: RagState) => ({
              documents: state.documents.filter((d: RagDocument) => d.id !== id),
            }));

            // 🛡️ 删除物理文件
            const doc = get().documents.find(d => d.id === id); // Use current snapshot
            if (doc) {
              const physicalDir = await get()._getPhysicalPath(doc.folderId);
              await FileSystem.deleteAsync(physicalDir + doc.title, { idempotent: true });
            }

            get().loadFolders();
          } catch (e) {
            console.error('Failed to delete document:', e);
            throw e;
          }
        },

        loadMemories: async () => {
          set({ isLoading: true });
          try {
            const results = await db.execute(`
          SELECT id, content, created_at, 
                 json_extract(metadata, '$.sessionId') as sessionId
          FROM vectors 
          WHERE json_extract(metadata, '$.type') = 'memory'
          ORDER BY created_at DESC
        `);

            if (!results.rows) {
              set({ memories: [], isLoading: false });
              return;
            }

            const memories: RagMemory[] = [];
            for (let i = 0; i < results.rows.length; i++) {
              const row = results.rows[i] as any;
              memories.push({
                id: row.id as string,
                content: row.content as string,
                sessionId: row.sessionId as string,
                createdAt: row.created_at as number,
              });
            }

            set({ memories, isLoading: false });
          } catch (e) {
            console.error('Failed to load memories:', e);
            set({ isLoading: false });
          }
        },

        deleteMemory: async (id: string) => {
          try {
            await db.execute('DELETE FROM vectors WHERE id = ?', [id]);
            set((state: RagState) => ({
              memories: state.memories.filter((m: RagMemory) => m.id !== id),
            }));
          } catch (e) {
            console.error('Failed to delete memory:', e);
            throw e;
          }
        },

        loadFolders: async () => {
          try {
            await get()._ensureWorkspace();
            // 1. Load all folders
            const results = await db.execute('SELECT * FROM folders ORDER BY created_at DESC');
            if (!results.rows) {
              set({ folders: [] });
              return;
            }

            const rawFolders = results.rows as any[];

            // 2. Load all document counts by folder
            const docCountResults = await db.execute(
              'SELECT folder_id, COUNT(*) as count FROM documents GROUP BY folder_id',
            );
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
            const folderMap = new Map<
              string,
              {
                id: string;
                parentId?: string;
                directCount: number;
                totalCount: number;
                children: string[];
              }
            >();

            // Initialize map
            rawFolders.forEach((row) => {
              const id = row.id as string;
              folderMap.set(id, {
                id,
                parentId: (row.parent_id as string) || undefined,
                directCount: docCounts[id] || 0,
                totalCount: 0,
                children: [],
              });
            });

            // Build hierarchy
            folderMap.forEach((folder) => {
              if (folder.parentId && folderMap.has(folder.parentId)) {
                folderMap.get(folder.parentId)!.children.push(folder.id);
              }
            });

            // Recursive function to calculate total counts
            const calculateTotal = (folderId: string): number => {
              const folder = folderMap.get(folderId);
              if (!folder) return 0;

              let sum = folder.directCount;
              folder.children.forEach((childId) => {
                sum += calculateTotal(childId);
              });

              folder.totalCount = sum;
              return sum;
            };

            // Calculate for all roots (and inherently all nodes)
            folderMap.forEach((folder) => {
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
            const roots = Array.from(folderMap.values()).filter((f) => !f.parentId);
            const computed = new Set<string>();

            const compute = (folder: any, depth = 0): number => {
              if (depth > 20) return folder.directCount; // 防止死循环
              let sum = folder.directCount;
              for (const childId of folder.children) {
                const child = folderMap.get(childId);
                if (child) {
                  sum += compute(child, depth + 1);
                }
              }
              folder.totalCount = sum;
              return sum;
            };

            roots.forEach((root) => compute(root));

            // 4. Transform to state objects
            const folders: RagFolder[] = rawFolders.map((row) => {
              const id = row.id as string;
              const node = folderMap.get(id);
              return {
                id,
                name: row.name as string,
                parentId: (row.parent_id as string) || undefined,
                childCount: node ? node.totalCount : 0,
                createdAt: row.created_at as number,
              };
            });

            set({ folders });
            console.log(`[RagStore] Folders loaded with recursive counts: ${folders.length}`);
          } catch (e) {
            console.error('Failed to load folders:', e);
          }
        },

        addFolder: async (name: string, parentId?: string) => {
          const folderId = generateId();
          const createdAt = Date.now();

          try {
            await db.execute(
              'INSERT INTO folders (id, name, parent_id, created_at) VALUES (?, ?, ?, ?)',
              [folderId, name, parentId || null, createdAt],
            );

            const newFolder: RagFolder = {
              id: folderId,
              name,
              parentId,
              childCount: 0,
              createdAt,
            };

            set((state: RagState) => ({ folders: [...state.folders, newFolder] }));

            // 🛡️ 创建物理目录
            const physicalPath = await get()._getPhysicalPath(folderId);
            await FileSystem.makeDirectoryAsync(physicalPath, { intermediates: true });
          } catch (e) {
            console.error('Failed to add folder:', e);
            throw e;
          }
        },

        deleteFolder: async (id: string) => {
          try {
            // 删除文件夹（会级联删除子文件夹，但文档会设置folder_id为NULL）
            await db.execute('DELETE FROM folders WHERE id = ?', [id]);
            set((state: RagState) => ({
              folders: state.folders.filter((f: RagFolder) => f.id !== id),
            }));

            // 🛡️ 删除物理目录
            const folder = get().folders.find(f => f.id === id);
            if (folder) {
              const physicalPath = await get()._getPhysicalPath(id);
              await FileSystem.deleteAsync(physicalPath, { idempotent: true });
            }

            // 重新加载文档以更新folder_id
            get().loadDocuments();
          } catch (e) {
            console.error('Failed to delete folder:', e);
            throw e;
          }
        },

        renameFolder: async (id: string, name: string) => {
          try {
            const oldFolder = get().folders.find(f => f.id === id);
            await db.execute('UPDATE folders SET name = ? WHERE id = ?', [name, id]);

            // 🛡️ 重命名物理目录
            if (oldFolder) {
              const oldPath = await get()._getPhysicalPath(id);
              // Temporarily update state for name resolution in path helper
              set((state: RagState) => ({
                folders: state.folders.map((f: RagFolder) => (f.id === id ? { ...f, name } : f)),
              }));
              const newPath = await get()._getPhysicalPath(id);
              await FileSystem.moveAsync({ from: oldPath, to: newPath });
            } else {
              set((state: RagState) => ({
                folders: state.folders.map((f: RagFolder) => (f.id === id ? { ...f, name } : f)),
              }));
            }
          } catch (e) {
            console.error('Failed to rename folder:', e);
            throw e;
          }
        },

        moveFolder: async (folderId: string, parentId: string | null) => {
          try {
            await db.execute('UPDATE folders SET parent_id = ? WHERE id = ?', [
              parentId || null,
              folderId,
            ]);
            set((state: RagState) => ({
              folders: state.folders.map((f: RagFolder) =>
                f.id === folderId ? { ...f, parentId: parentId || undefined } : f,
              ),
            }));
          } catch (e) {
            console.error('Failed to move folder:', e);
            throw e;
          }
        },

        toggleFolder: (id: string) => {
          set((state: RagState) => {
            const expanded = new Set(state.expandedFolders);
            if (expanded.has(id)) {
              expanded.delete(id);
            } else {
              expanded.add(id);
            }
            return { expandedFolders: expanded };
          });
        },

        moveDocument: async (docId: string, folderId: string | null) => {
          try {
            const doc = get().documents.find(d => d.id === docId);
            const oldFolderId = doc?.folderId;

            await db.execute('UPDATE documents SET folder_id = ? WHERE id = ?', [folderId, docId]);

            // 🛡️ 移动物理文件
            if (doc) {
              const oldPath = await get()._getPhysicalPath(oldFolderId) + doc.title;
              const newPath = await get()._getPhysicalPath(folderId) + doc.title;
              await FileSystem.makeDirectoryAsync(await get()._getPhysicalPath(folderId), { intermediates: true });
              await FileSystem.moveAsync({ from: oldPath, to: newPath });
            }

            // 更新本地状态
            set((state: RagState) => ({
              documents: state.documents.map((d: RagDocument) =>
                d.id === docId ? { ...d, folderId: folderId || undefined } : d,
              ),
            }));
            get().loadFolders();
          } catch (e) {
            console.error('Failed to move document:', e);
            throw e;
          }
        },

        vectorizeBatch: async (docIds: string[]) => {
          const queue = getQueue();
          const docs = get().documents.filter((d: RagDocument) => docIds.includes(d.id));

          for (const doc of docs) {
            await queue.enqueue(doc.id, doc.title, ''); // Content fetched from DB by queue
          }
        },

        extractBatch: async (docIds: string[], strategy: 'full' | 'summary-first') => {
          const queue = getQueue();
          const docs = get().documents.filter((d: RagDocument) => docIds.includes(d.id));

          for (const doc of docs) {
            await queue.enqueue(doc.id, doc.title, '', strategy); // Content fetched from DB
          }
        },

        deleteBatch: async (docIds: string[]) => {
          try {
            // Prepare transaction queries would be better, but loop is safer for now with current db adapter
            for (const id of docIds) {
              await db.execute('DELETE FROM vectors WHERE doc_id = ?', [id]);
              await db.execute('DELETE FROM kg_edges WHERE doc_id = ?', [id]);
              await db.execute('DELETE FROM documents WHERE id = ?', [id]);
            }

            // Cleanup orphaned nodes once for the batch
            await db.execute(`
                        DELETE FROM kg_nodes 
                        WHERE id NOT IN (SELECT source_id FROM kg_edges) 
                        AND id NOT IN (SELECT target_id FROM kg_edges)
                    `);

            set((state: RagState) => ({
              documents: state.documents.filter((d: RagDocument) => !docIds.includes(d.id)),
            }));
            get().loadFolders();
          } catch (e) {
            console.error('Failed to delete batch:', e);
            throw e;
          }
        },

        setSelectedFolder: (folderId: string | null) => {
          set({ selectedFolder: folderId });
        },

        getDocumentsByFolder: (folderId: string | null) => {
          const docs = get().documents;
          if (folderId === null) {
            // 返回所有未分类文档
            return docs.filter((d: RagDocument) => !d.folderId);
          }
          return docs.filter((d: RagDocument) => d.folderId === folderId);
        },

        availableTags: [],

        loadAvailableTags: async () => {
          try {
            const tags = await graphStore.getAllTags();
            set({ availableTags: tags });
          } catch (e) {
            console.error('Failed to load tags:', e);
          }
        },

        createTag: async (name: string, color?: string) => {
          try {
            await graphStore.createTag(name, color);
            await get().loadAvailableTags();
          } catch (e) {
            console.error('Failed to create tag:', e);
            throw e;
          }
        },

        deleteTag: async (tagId: string) => {
          try {
            await graphStore.deleteTag(tagId);
            await get().loadAvailableTags();
            // We should also refresh documents to remove the tag from UI instantly, or filter it locally
            // Ideally refresh docs:
            get().loadDocuments();
          } catch (e) {
            console.error('Failed to delete tag:', e);
            throw e;
          }
        },

        addTagToDocument: async (docId: string, tagId: string) => {
          try {
            await graphStore.attachTagToDoc(docId, tagId);
            // Refresh docs to show new tag
            await get().loadDocuments();
          } catch (e) {
            console.error('Failed to add tag to doc:', e);
            throw e;
          }
        },

        removeTagFromDocument: async (docId: string, tagId: string) => {
          try {
            await graphStore.removeTagFromDoc(docId, tagId);
            // Refresh docs
            await get().loadDocuments();
          } catch (e) {
            console.error('Failed to remove tag from doc:', e);
            throw e;
          }
        },

        _updateQueueState: (queue: VectorizationTask[], current: VectorizationTask | null) => {
          const prevQueueLength = get().vectorizationQueue.length;
          set({ vectorizationQueue: queue, currentTask: current });

          // ✅ 深度集成：将队列状态同步到 RAG 指示器
          if (current) {
            get().updateProcessingState({
              status: current.status as any,
              progress: current.progress,
              subStage: current.status === 'chunking' ? 'CHUNKING' :
                current.status === 'vectorizing' ? 'EMBEDDING' :
                  current.status === 'saving' ? 'SAVING' : undefined,
              pulseActive: true
            }, current.docId); // 这里 docId 通常对应消息 ID (如果是背景归档任务)
          }

          // 如果队列长度减少（任务完成），重新加载文档列表
          if (queue.length < prevQueueLength || (current === null && prevQueueLength > 0)) {
            setTimeout(() => get().loadDocuments(), 200);
          }
        },

        processingState: { status: 'idle', chunks: [], pulseActive: false },
        processingHistory: {},
        updateProcessingState: (stateUpdate: Partial<RagState['processingState']>, messageId?: string) => {
          set((state: RagState) => {
            const newState = { ...state };
            const combinedStatus = stateUpdate.status || state.processingState.status;

            // 1. Update active state
            newState.processingState = {
              ...state.processingState,
              ...stateUpdate,
              activeMessageId: messageId || stateUpdate.activeMessageId || state.processingState.activeMessageId,
              chunks: stateUpdate.chunks || state.processingState.chunks || [],
            };

            // 2. If it's a permanent result, save to history
            const isKgCompleted = stateUpdate.kgStatus === 'completed' || state.processingState.kgStatus === 'completed';
            if (
              messageId &&
              (combinedStatus === 'archived' || combinedStatus === 'summarized' || combinedStatus === 'completed' || combinedStatus === 'retrieved' || isKgCompleted)
            ) {
              // 确定历史类型
              let type: 'archived' | 'summarized' | 'retrieved';
              if (combinedStatus === 'retrieved' || isKgCompleted) {
                type = 'retrieved';
              } else if (combinedStatus === 'completed') {
                type = 'retrieved';
              } else {
                type = combinedStatus as any;
              }

              const chunkCount = stateUpdate.chunks?.length || state.processingState.chunks?.length || 0;

              newState.processingHistory = {
                ...state.processingHistory,
                [messageId]: {
                  type,
                  summary: stateUpdate.summary || state.processingState.summary,
                  chunkCount: chunkCount || undefined,
                  timestamp: Date.now(),
                },
              };

              // Reset active status to idle for terminal states
              if (combinedStatus === 'completed' || combinedStatus === 'archived' || combinedStatus === 'summarized' || combinedStatus === 'retrieved') {
                newState.processingState = {
                  ...newState.processingState,
                  status: 'idle',
                  subStage: undefined,
                  networkStats: undefined,
                };
              }

              // 3. 容量控制：保留最近 100 条消息的检索历史，防止 AsyncStorage 膨胀
              const historyKeys = Object.keys(newState.processingHistory);
              if (historyKeys.length > 100) {
                const sortedKeys = historyKeys.sort((a, b) =>
                  newState.processingHistory[b].timestamp - newState.processingHistory[a].timestamp
                );
                const keysToRemove = sortedKeys.slice(100);
                keysToRemove.forEach(key => delete newState.processingHistory[key]);
              }
            }

            return newState;
          });
        },

        setGlobalPulse: (active: boolean) => {
          set((state: RagState) => ({
            processingState: { ...state.processingState, pulseActive: active }
          }));
        },

        cancelVectorization: (docId: string) => {
          const queue = getQueue();
          queue.cancel(docId);
        },

        clearVectorizationQueue: () => {
          const queue = getQueue();
          queue.clear();
          // 强制刷新状态防止UI卡死
          set({
            vectorizationQueue: [],
            currentTask: null,
            processingState: { status: 'idle', chunks: [] } // Reset global indicator too if needed
          });
        },

        getVectorStats: () => {
          const docs = get().documents;
          const totalDocs = docs.length;
          const totalVectors = docs.reduce((acc: number, doc: RagDocument) => acc + (doc.vectorCount || 0), 0);
          const totalSize = docs.reduce((acc: number, doc: RagDocument) => acc + (doc.fileSize || 0), 0);
          return { totalDocs, totalVectors, totalSize };
        },
      };
    },
    {
      name: 'nexara-rag-storage',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state: RagState) => ({
        processingHistory: state.processingHistory,
        expandedFolders: Array.from(state.expandedFolders),
        selectedFolder: state.selectedFolder,
      }),
      onRehydrateStorage: () => (state: any) => {
        if (state && state.expandedFolders) {
          // 将 Array 恢复为 Set
          state.expandedFolders = new Set(state.expandedFolders);
        }
      },
    }
  )
);

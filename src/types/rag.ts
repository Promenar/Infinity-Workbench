// RAG文库系统类型定义

export interface RagFolder {
  id: string;
  name: string;
  parentId?: string;
  childCount: number;
  createdAt: number;
}

export interface RagDocument {
  id: string;
  title: string;
  content: string;
  source: string;
  type: string;
  folderId?: string;
  vectorized: 0 | 1 | 2 | -1; // 未处理|处理中|已完成|失败
  vectorCount: number;
  fileSize: number;
  createdAt: number;
  updatedAt?: number;
  tags?: Array<{ id: string; name: string; color: string }>;
  thumbnailPath?: string;
}

export interface VectorizationTask {
  id: string;
  docId: string;
  docTitle: string;
  status: 'pending' | 'reader' | 'chunking' | 'vectorizing' | 'saving' | 'completed' | 'failed';
  progress: number; // 0-100
  error?: string;
  createdAt: number;
}

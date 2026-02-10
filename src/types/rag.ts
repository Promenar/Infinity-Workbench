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
  isGlobal?: boolean;
  contentHash?: string;
}

export interface VectorizationTask {
  id: string;
  // 🔑 任务类型：文档 或 记忆(消息) 或 会话KG批量抽取
  type: 'document' | 'memory' | 'session-kg';
  // 文档任务专用字段
  docId?: string;
  docTitle?: string;
  // 记忆任务专用字段
  sessionId?: string;
  userContent?: string;
  aiContent?: string;
  userMessageId?: string;
  assistantMessageId?: string;
  // 🔑 会话 KG 批量任务专用字段
  kgBatchContent?: string[];  // 累积的消息内容
  kgMessageIds?: string[];    // 关联的消息 ID
  // 通用字段
  status: 'pending' | 'reader' | 'chunking' | 'vectorizing' | 'saving' | 'extracting' | 'completed' | 'warning' | 'failed';
  progress: number; // 0-100
  subStatus?: string; // 🔑 动态子状态描述 (中文)
  error?: string;
  createdAt: number;
  updatedAt?: number; // 🔑 心跳时间戳
  kgStrategy?: 'full' | 'summary-first' | 'on-demand';
  skipVectorization?: boolean; // 🔑 新增：仅执行 KG 抽取，跳过向量化
  // 🔑 检查点字段 (Checkpoint)
  lastChunkIndex?: number;
  totalChunks?: number;
}


export interface RagMemory {
  id: string; // vector_id in database
  content: string;
  sessionId: string;
  createdAt: number;
}

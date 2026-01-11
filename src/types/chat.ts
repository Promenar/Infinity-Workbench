export type AgentId = string;
export type SessionId = string;

import { ExecutionStep } from './skills';

export interface InferenceParams {
  temperature?: number; // 0.0 - 2.0
  topP?: number; // 0.0 - 1.0
  maxTokens?: number; // 1 - Context Limit
  frequencyPenalty?: number; // -2.0 - 2.0
  presencePenalty?: number; // -2.0 - 2.0
}

export interface Agent {
  id: string;
  name: string;
  description: string;
  avatar: string; // lucide icon name or image url
  color: string;

  // AI Configuration
  systemPrompt: string;
  defaultModel: string;

  // Advanced Parameters
  params?: InferenceParams; // Default params for this agent

  // RAG Configuration (助手级配置，未设置则使用全局)
  ragConfig?: RagConfiguration;

  isPreset?: boolean;
  isPinned?: boolean;
  created: number;
}

// 基础 Token 计量单位
export interface TokenMetric {
  count: number;
  isEstimated: boolean; // 🚨 核心字段：标记是否为降级估算值
}

// 计费详情结构
export interface BillingUsage {
  chatInput: TokenMetric; // 用户提问 + 历史上下文
  chatOutput: TokenMetric; // 模型生成回复
  ragSystem: TokenMetric; // RAG 开销：重写 + 摘要 + Embedding
  total: number; // 总计费 Token
  costUSD?: number; // 估算成本 (用于显示)
}

export interface TokenUsage {
  input: number;
  output: number;
  total: number;
}

/**
 * 生成图片数据（支持缩略图）
 */
export interface GeneratedImageData {
  thumbnail: string; // 缩略图 URI (file://)
  original: string; // 原图 URI (file://)
  mime: string; // MIME 类型
}

export interface RagReference {
  id: string; // 引用 ID
  content: string; // 片段内容
  source: string; // 来源（文档名或会话标题）
  type: 'doc' | 'memory'; // 类型
  docId?: string; // 文档 ID（用于跳转）
  similarity?: number; // 相似度分数
}
// 检索进度
export interface RagProgress {
  stage: 'rewriting' | 'embedding' | 'searching' | 'reranking' | 'done';
  percentage: number; // 0-100
  message?: string;
}

// 检索元数据
export interface RagMetadata {
  queryVariants?: string[];
  searchTimeMs: number;
  rerankTimeMs?: number;
  totalTimeMs?: number;
  recallCount: number;
  finalCount: number;
  maxSimilarity?: number;
  sourceDistribution?: {
    memory: number;
    documents: number;
  };
}

export interface Message {
  id: string; // uuid
  role: 'user' | 'assistant' | 'system';
  content: string; // markdown content
  createdAt: number;
  modelId?: string; // ✅ 恢复：模型ID
  status?: 'sending' | 'sent' | 'error' | 'streaming';
  references?: RagReference[]; // RAG 引用来源
  ragProgress?: RagProgress; // ✅ 新增：检索进度
  ragMetadata?: RagMetadata; // ✅ 新增：检索元数据
  reasoning?: string; // Chain of Thought reasoning content
  citations?: { title: string; url: string; source?: string }[]; // Grounding/Web Search citations
  ragReferences?: RagReference[]; // RAG knowledge base references
  ragReferencesLoading?: boolean; // New flag for RAG search state
  tokens?: TokenUsage;
  images?: GeneratedImageData[]; // 图片数据（新格式，支持缩略图）
  isArchived?: boolean; // ✅ 新增：归档状态
  vectorizationStatus?: 'processing' | 'success' | 'error'; // ✅ 新增：向量化状态
  layoutHeight?: number; // ✅ 新增：缓存布局高度，优化滚动性能
  executionSteps?: ExecutionStep[]; // ✅ 新增：Agentic Loop 执行步骤

}

export interface Session {
  id: SessionId;
  agentId: AgentId;
  title: string;
  lastMessage: string;
  time: string;
  unread: number;
  messages: Message[];
  modelId?: string; // Override agent's default model for this session
  customPrompt?: string; // Additional prompt specific to this session (appended to agent's systemPrompt)
  isPinned?: boolean;
  stats?: {
    totalTokens: number; // 兼容现有字段 (保留用于向后兼容)
    billing?: BillingUsage; // ✅ 新增：真实计费数据详情
  };
  inferenceParams?: InferenceParams;
  options?: {
    webSearch?: boolean;
    reasoning?: boolean;
  };
  ragOptions?: {
    enableMemory?: boolean; // 启用长期记忆
    enableDocs?: boolean; // 启用知识库检索
    activeDocIds?: string[]; // 指定文档ID（undefined=全部）
    activeFolderIds?: string[]; // 指定文件夹ID
    isGlobal?: boolean; // 是否全局搜索（忽略 activeDocIds）
    enableKnowledgeGraph?: boolean; // ✅ Session-level KG toggle
  };
  scrollOffset?: number; // 记录滚动位置
  draft?: string; // 未发送的草稿内容
}

// RAG配置（会话级或全局）
export interface RagConfiguration {
  // 切块配置
  docChunkSize: number; // 知识库文档切块大小
  memoryChunkSize: number; // 对话记忆切块大小
  chunkOverlap: number; // 重叠字符数

  // 上下文管理
  contextWindow: number; // 保留的活跃消息数
  summaryThreshold: number; // 触发摘要的最小批次
  summaryPrompt: string; // 摘要Prompt模板
  summaryModel?: string; // 摘要专用模型
  autoCleanup: boolean; // 摘要后自动清理旧向量

  // 检索配置
  memoryLimit: number; // 检索记忆向量数量
  memoryThreshold: number; // 记忆相似度阈值
  docLimit: number; // 检索文档向量数量
  docThreshold: number; // 文档相似度阈值

  // 功能开关
  enableMemory: boolean; // 启用长期记忆
  enableDocs: boolean; // 启用知识库检索

  // 调试选项（可选）
  debugMode?: boolean; // 开发者模式
  showStats?: boolean; // 显示向量库统计

  // ===== 高级检索功能（Phase 3新增） =====

  // Rerank配置（3个）
  enableRerank?: boolean; // 启用Rerank二次精排
  rerankTopK?: number; // Rerank前召回数量（建议20-50）
  rerankFinalK?: number; // Rerank后返回数量（建议5-10）

  // 查询重写配置（4个）
  enableQueryRewrite?: boolean; // 启用查询重写
  queryRewriteStrategy?: 'hyde' | 'multi-query' | 'expansion'; // 重写策略
  queryRewriteCount?: number; // 生成查询变体数量（2-5个）
  queryRewriteModel?: string; // 查询重写使用的模型UUID（默认使用summary model）

  // 混合检索配置（3个）
  enableHybridSearch?: boolean; // 启用混合检索（向量+BM25）
  hybridAlpha?: number; // 向量检索权重（0-1, 0.5为均衡）
  hybridBM25Boost?: number; // BM25权重增益（默认1.0）

  // 可观测性配置（3个）
  showRetrievalProgress?: boolean; // 显示检索进度
  showRetrievalDetails?: boolean; // 显示检索详情面板
  trackRetrievalMetrics?: boolean; // 记录检索指标

  // ===== Phase 8: 知识图谱 & 降本增效 =====

  // 知识图谱配置
  enableKnowledgeGraph?: boolean; // 启用知识图谱
  kgExtractionModel?: string; // 抽取实体使用的模型ID (默认gpt-3.5)
  kgExtractionPrompt?: string; // 自定义抽取提示词
  kgMaxDepth?: number; // 遍历深度 (默认2)
  kgEntityTypes?: string[]; // 关注的实体类型 (Person, Org, etc.)

  // 降本策略
  costStrategy?: 'summary-first' | 'on-demand' | 'full'; // 抽取策略
  enableIncrementalHash?: boolean; // 启用增量Hash校验 (默认true)
  enableLocalPreprocess?: boolean; // 启用本地规则预处理 (默认true)

  // Scope Configuration (Added for Global RAG Context)
  activeDocIds?: string[]; // 全局默认授权文档
  activeFolderIds?: string[]; // 全局默认授权文件夹
  isGlobal?: boolean; // 全局搜索开关 (true=ignore ids, false=restrict to ids)
}

/**
 * 模型规格数据库
 *
 * 包含常见 LLM 模型的上下文长度等规格信息
 * 用于在 API 未返回该信息时自动填充
 */

interface ModelSpec {
  /** 模型名称或 ID 的匹配模式（支持部分匹配） */
  pattern: string | RegExp;
  /** 上下文长度（tokens） */
  contextLength: number;
  /** 模型类型 */
  type?: 'chat' | 'reasoning' | 'image' | 'embedding' | 'rerank';
  /** 模型能力标签 */
  capabilities?: {
    vision?: boolean;
    internet?: boolean;
    reasoning?: boolean;
  };
  /** 是否强制启用推理（无法通过 API 参数关闭） */
  forcedReasoning?: boolean;
  /** 模型图标标识符（用于图标映射） */
  icon?: string;
  /** 备注信息 */
  note?: string;
}

/**
 * 模型规格数据库
 * 按照：国际主流 → 中国主流 → 开源模型 的顺序组织
 */
export const MODEL_SPECS: ModelSpec[] = [
  // ==================== OpenAI ====================
  {
    pattern: 'gpt-4o',
    contextLength: 128000,
    type: 'chat',
    capabilities: { vision: true },
    icon: 'openai',
    note: 'GPT-4o series',
  },
  {
    pattern: 'gpt-4-turbo',
    contextLength: 128000,
    type: 'chat',
    icon: 'openai',
    note: 'GPT-4 Turbo',
  },
  { pattern: 'gpt-4', contextLength: 128000, type: 'chat', icon: 'openai', note: 'GPT-4 Generic' },
  { pattern: 'gpt-3.5', contextLength: 16385, type: 'chat', icon: 'openai', note: 'GPT-3.5' },
  { pattern: 'openai', contextLength: 4096, icon: 'openai', note: 'OpenAI Generic' },

  // O1 系列（推理模型，无法关闭推理）
  {
    pattern: 'o1-preview',
    contextLength: 128000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    forcedReasoning: true,
    icon: 'openai',
    note: 'O1 Preview',
  },
  {
    pattern: 'o1-mini',
    contextLength: 128000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    forcedReasoning: true,
    icon: 'openai',
    note: 'O1 Mini',
  },
  {
    pattern: 'o1',
    contextLength: 200000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    forcedReasoning: true,
    icon: 'openai',
    note: 'O1',
  },

  // ==================== Anthropic ====================
  {
    pattern: 'claude-3-5-sonnet',
    contextLength: 200000,
    icon: 'claude',
    note: 'Claude 3.5 Sonnet',
  },
  { pattern: 'claude-3-5', contextLength: 200000, icon: 'claude', note: 'Claude 3.5' },
  { pattern: 'claude-3', contextLength: 200000, icon: 'claude', note: 'Claude 3' },
  { pattern: 'claude', contextLength: 100000, icon: 'claude', note: 'Claude Generic' },
  { pattern: 'anthropic', contextLength: 100000, icon: 'anthropic', note: 'Anthropic Generic' },

  // ==================== Google Gemini ====================
  {
    pattern: 'gemini-2.0-flash-thinking',
    contextLength: 1000000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    icon: 'gemini',
    note: 'Gemini 2.0 Flash Thinking',
  },
  {
    pattern: 'gemini-2.0',
    contextLength: 1000000,
    type: 'chat',
    capabilities: { vision: true, reasoning: true },
    icon: 'gemini',
    note: 'Gemini 2.0',
  },
  {
    pattern: 'gemini-1.5-pro',
    contextLength: 2000000,
    type: 'chat',
    capabilities: { vision: true, reasoning: true },
    icon: 'gemini',
    note: 'Gemini 1.5 Pro',
  },
  {
    pattern: 'gemini-1.5-flash',
    contextLength: 1000000,
    type: 'chat',
    capabilities: { vision: true, reasoning: true },
    icon: 'gemini',
    note: 'Gemini 1.5 Flash',
  },
  {
    pattern: 'gemini-1.5',
    contextLength: 1000000,
    type: 'chat',
    capabilities: { reasoning: true },
    icon: 'gemini',
    note: 'Gemini 1.5',
  },
  { pattern: 'gemini', contextLength: 1000000, type: 'chat', icon: 'gemini', note: 'Gemini' },
  { pattern: 'google', contextLength: 32768, icon: 'google', note: 'Google Generic' },

  // ==================== DeepSeek (深度求索) ====================
  {
    pattern: 'deepseek-reasoner',
    contextLength: 64000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    forcedReasoning: true,
    icon: 'deepseek',
    note: 'DeepSeek R1 (Native Reasoning)',
  },
  {
    pattern: 'deepseek-r1',
    contextLength: 64000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    forcedReasoning: true,
    icon: 'deepseek',
    note: 'DeepSeek R1',
  },
  {
    pattern: 'deepseek-v3',
    contextLength: 64000,
    type: 'chat',
    icon: 'deepseek',
    note: 'DeepSeek V3',
  },
  { pattern: 'deepseek', contextLength: 64000, icon: 'deepseek', note: 'DeepSeek' },

  // ==================== 智谱 AI (GLM) ====================
  {
    pattern: /glm-?4\.7/i,
    contextLength: 128000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    icon: 'zhipu',
    note: 'GLM-4.7 (Reasoning)',
  },
  {
    pattern: /glm-?4\.6.*v/i,
    contextLength: 128000,
    type: 'chat',
    capabilities: { vision: true },
    icon: 'zhipu',
    note: 'GLM-4.6V (Vision)',
  },
  {
    pattern: /glm-?4\.5/i,
    contextLength: 128000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    icon: 'zhipu',
    note: 'GLM-4.5 (Reasoning)',
  },
  {
    pattern: /glm.*v(?:ision)?$/i,
    contextLength: 128000,
    type: 'chat',
    capabilities: { vision: true },
    icon: 'zhipu',
    note: 'GLM Vision Series',
  },
  { pattern: 'glm-4-plus', contextLength: 128000, type: 'chat', icon: 'zhipu', note: 'GLM-4 Plus' },
  { pattern: 'glm-4', contextLength: 128000, type: 'chat', icon: 'zhipu', note: 'GLM-4' },
  { pattern: 'glm-3', contextLength: 128000, icon: 'zhipu' },
  { pattern: 'zhipu', contextLength: 128000, icon: 'zhipu' },

  // ==================== Moonshot (Kimi) ====================
  {
    pattern: 'thinking',
    contextLength: 128000,
    type: 'reasoning',
    capabilities: { reasoning: true },
    icon: 'kimi',
  },
  { pattern: 'kimi', contextLength: 128000, icon: 'kimi' },
  { pattern: 'moonshot', contextLength: 128000, icon: 'moonshot' },

  // ==================== 百川智能 (Baichuan) ====================
  { pattern: /baichuan-4/i, contextLength: 32768, icon: 'baichuan', note: 'Baichuan 4' },
  { pattern: /baichuan-3-turbo/i, contextLength: 32768, icon: 'baichuan', note: 'Baichuan 3 Turbo' },
  { pattern: /baichuan-2-turbo/i, contextLength: 32768, icon: 'baichuan', note: 'Baichuan 2 Turbo' },
  { pattern: /baichuan/i, contextLength: 32768, icon: 'baichuan', note: 'Baichuan' },

  // ==================== 阿里通义千问 (Qwen) ====================
  { pattern: /qwen-max/i, contextLength: 8000, icon: 'qwen', note: 'Qwen Max' },
  { pattern: /qwen-plus/i, contextLength: 32768, icon: 'qwen', note: 'Qwen Plus' },
  { pattern: /qwen-turbo/i, contextLength: 8000, icon: 'qwen', note: 'Qwen Turbo' },
  { pattern: /qwen2\.5-72b/i, contextLength: 131072, icon: 'qwen', note: 'Qwen2.5 72B' },
  { pattern: /qwen2\.5-32b/i, contextLength: 131072, icon: 'qwen', note: 'Qwen2.5 32B' },
  { pattern: /qwen2\.5-14b/i, contextLength: 131072, icon: 'qwen', note: 'Qwen2.5 14B' },
  { pattern: /qwen2\.5-7b/i, contextLength: 131072, icon: 'qwen', note: 'Qwen2.5 7B' },
  { pattern: /qwen2-72b/i, contextLength: 32768, icon: 'qwen', note: 'Qwen2 72B' },
  { pattern: /qwen/i, contextLength: 8000, icon: 'qwen', note: 'Qwen' },

  // ==================== 百度文心一言 (ERNIE) ====================
  { pattern: /ernie-4\.0/i, contextLength: 8192, icon: 'wenxin', note: 'ERNIE 4.0' },
  { pattern: /ernie-3\.5/i, contextLength: 8192, icon: 'wenxin', note: 'ERNIE 3.5' },
  { pattern: /ernie-turbo/i, contextLength: 8192, icon: 'wenxin', note: 'ERNIE Turbo' },
  { pattern: /ernie-speed/i, contextLength: 8192, icon: 'wenxin', note: 'ERNIE Speed' },
  { pattern: /ernie/i, contextLength: 8192, icon: 'wenxin', note: 'ERNIE' },

  // ==================== 字节豆包 (Doubao) ====================
  { pattern: /doubao-pro-32k/i, contextLength: 32768, icon: 'doubao', note: 'Doubao Pro 32K' },
  { pattern: /doubao-pro-4k/i, contextLength: 4096, icon: 'doubao', note: 'Doubao Pro 4K' },
  { pattern: /doubao-lite-32k/i, contextLength: 32768, icon: 'doubao', note: 'Doubao Lite 32K' },
  { pattern: /doubao/i, contextLength: 32768, icon: 'doubao', note: 'Doubao' },

  // ==================== 零一万物 (Yi) ====================
  { pattern: /yi-large/i, contextLength: 32768, icon: 'yi', note: 'Yi Large' },
  { pattern: /yi-medium/i, contextLength: 16384, icon: 'yi', note: 'Yi Medium' },
  { pattern: /yi-34b-chat/i, contextLength: 200000, icon: 'yi', note: 'Yi 34B Chat 200K' },
  { pattern: /yi-6b/i, contextLength: 4096, icon: 'yi', note: 'Yi 6B' },
  { pattern: /yi-/i, contextLength: 4096, icon: 'yi', note: 'Yi series' },

  // ==================== MiniMax ====================
  { pattern: /abab6\.5/i, contextLength: 245760, icon: 'minimax', note: 'ABAB 6.5 (245K)' },
  { pattern: /abab6/i, contextLength: 8192, icon: 'minimax', note: 'ABAB 6' },
  { pattern: /abab5\.5/i, contextLength: 8192, icon: 'minimax', note: 'ABAB 5.5' },

  // ==================== 其他开源模型 ====================
  { pattern: /llama-3\.1-405b/i, contextLength: 128000, icon: 'meta', note: 'Llama 3.1 405B' },
  { pattern: /llama-3\.1-70b/i, contextLength: 128000, icon: 'meta', note: 'Llama 3.1 70B' },
  { pattern: /llama-3\.1/i, contextLength: 128000, icon: 'meta', note: 'Llama 3.1' },
  { pattern: /llama-3-70b/i, contextLength: 8192, icon: 'meta', note: 'Llama 3 70B' },
  { pattern: /llama-3/i, contextLength: 8192, icon: 'meta', note: 'Llama 3' },
  { pattern: /llama-2-70b/i, contextLength: 4096, icon: 'meta', note: 'Llama 2 70B' },
  { pattern: /llama-2/i, contextLength: 4096, icon: 'meta', note: 'Llama 2' },
  { pattern: /mistral-large/i, contextLength: 128000, icon: 'mistral', note: 'Mistral Large' },
  { pattern: /mistral-medium/i, contextLength: 32000, icon: 'mistral', note: 'Mistral Medium' },
  { pattern: /mistral-small/i, contextLength: 32000, icon: 'mistral', note: 'Mistral Small' },
  { pattern: /mixtral-8x7b/i, contextLength: 32000, icon: 'mistral', note: 'Mixtral 8x7B' },

  // ==================== Rerank Models (重排序) ====================
  {
    pattern: /bge-reranker/i,
    contextLength: 4096,
    type: 'rerank',
    icon: 'rerank',
    note: 'BGE Reranker',
  },
  {
    pattern: /jina-reranker/i,
    contextLength: 8192,
    type: 'rerank',
    icon: 'rerank',
    note: 'Jina Reranker',
  },
  {
    pattern: /cohere-rerank/i,
    contextLength: 4096,
    type: 'rerank',
    icon: 'rerank',
    note: 'Cohere Rerank',
  },
  {
    pattern: /rerank/i,
    contextLength: 4096,
    type: 'rerank',
    icon: 'rerank',
    note: 'Generic Rerank Model',
  },
];

/**
 * 根据模型 ID 或名称查找上下文长度
 * @param modelId 模型 ID 或名称
 * @returns 上下文长度（tokens），未找到返回 undefined
 */
export function findContextLength(modelId: string): number | undefined {
  const normalizedId = modelId.toLowerCase();

  for (const spec of MODEL_SPECS) {
    if (typeof spec.pattern === 'string') {
      if (normalizedId.includes(spec.pattern.toLowerCase())) {
        return spec.contextLength;
      }
    } else {
      if (spec.pattern.test(modelId)) {
        return spec.contextLength;
      }
    }
  }

  return undefined;
}

/**
 * 从模型名称中提取数字表示的上下文长度（如 "128k", "2m"）
 * @param text 模型名称或描述
 * @returns 上下文长度（tokens），未找到返回 undefined
 */
export function extractContextLengthFromName(text: string): number | undefined {
  const normalized = text.toLowerCase();

  // 匹配 "128k", "2m" 等格式
  const kMatch = normalized.match(/(\d+)k\b/);
  const mMatch = normalized.match(/(\d+)m\b/);

  if (kMatch) {
    return parseInt(kMatch[1]) * 1000;
  }

  if (mMatch) {
    return parseInt(mMatch[1]) * 1000000;
  }

  return undefined;
}

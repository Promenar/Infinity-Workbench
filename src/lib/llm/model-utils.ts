import { MODEL_SPECS } from './model-specs';
import { useApiStore, ModelConfig } from '../../store/api-store';

/**
 * 辅助函数：从 Store 中查找模型配置
 */
function findStoreConfig(modelId: string): ModelConfig | undefined {
  try {
    const providers = useApiStore.getState().providers;
    for (const provider of providers) {
      const model = provider.models.find(m => m.uuid === modelId || m.id === modelId);
      if (model) return model;
    }
  } catch (e) {
    console.warn('[ModelUtils] Failed to access ApiStore:', e);
  }
  return undefined;
}

/**
 * 根据模型 ID 查找完整的模型规格信息
 * @param modelId 模型 ID 或名称
 * @returns 模型规格对象，未找到返回 undefined
 */
export function findModelSpec(modelId: string) {
  const normalizedId = modelId.toLowerCase();

  // 1. 获取 Store 配置（但不立即返回，以便后续与 Spec 合并）
  const storeModel = findStoreConfig(modelId);

  // 2. 回退到静态规格库
  for (const spec of MODEL_SPECS) {
    const isMatched = typeof spec.pattern === 'string'
      ? normalizedId.includes(spec.pattern.toLowerCase())
      : spec.pattern.test(modelId);

    if (isMatched) {
      // 如果 Store 中没有完整能力，可以尝试从 Spec 中继承
      if (storeModel) {
        return {
          ...spec,
          pattern: storeModel.id,
          contextLength: storeModel.contextLength || spec.contextLength || 4096,
          type: storeModel.type || spec.type || 'chat',
          capabilities: {
            ...spec.capabilities,
            ...storeModel.capabilities,
          },
          icon: storeModel.icon || spec.icon || 'api',
        };
      }
      return spec;
    }
  }

  // 3. 如果没匹配到，返回 Store 配置（如果存在）
  if (storeModel) {
    return {
      pattern: storeModel.id,
      contextLength: storeModel.contextLength || 4096,
      type: storeModel.type || 'chat',
      capabilities: storeModel.capabilities || {},
      icon: storeModel.icon || 'api',
    };
  }

  return undefined;
}

/**
 * 根据模型 ID 判断是否为强制推理模型（无法通过 API 参数关闭推理）
 * @param modelId 模型 ID
 * @returns 是否强制推理
 */
export function isForcedReasoningModel(modelId: string): boolean {
  const spec = findModelSpec(modelId);
  return spec?.forcedReasoning === true;
}

/**
 * 根据模型 ID 判断是否支持可选的 Thinking Config (如 Gemini Flash Thinking)
 * @param modelId 模型 ID
 */
export function supportsThinkingConfig(modelId: string): boolean {
  const lowerId = modelId.toLowerCase();

  // 1. Explicitly check for Flash Thinking or any Gemini 1.5/2.0+ models
  // Gemini series often have implicit reasoning or dedicated thinking modes
  const isGemini = lowerId.includes('flash-thinking') || lowerId.includes('thinking') || lowerId.includes('gemini-1.5') || lowerId.includes('gemini-2.0') || lowerId.includes('gemini-3');

  if (isGemini) return true;

  // 2. Robust Check: Try to resolve the actual Model ID if input is a UUID
  const spec = findModelSpec(modelId);
  if (spec) {
    const slug = (typeof spec.pattern === 'string' ? spec.pattern : spec.pattern.toString()).toLowerCase();
    if (slug.includes('gemini') || spec.capabilities?.reasoning) {
      return true;
    }
  }

  // 3. Check capabilities directly
  const caps = getModelCapabilities(modelId);
  if (caps.reasoning) return true;

  return false;
}

/**
 * 根据模型 ID 获取模型类型
 * @param modelId 模型 ID
 * @returns 模型类型，未找到返回 'chat' 作为默认值
 */
export function getModelType(
  modelId: string,
): 'chat' | 'reasoning' | 'image' | 'embedding' | 'rerank' {
  const spec = findModelSpec(modelId);
  return spec?.type || 'chat';
}

/**
 * 根据模型 ID 获取模型能力标签
 * @param modelId 模型 ID
 * @returns 能力对象
 */
export function getModelCapabilities(modelId: string) {
  const spec = findModelSpec(modelId);
  return spec?.capabilities || {};
}

/**
 * 根据模型 ID 获取图标标识符
 * @param modelId 模型 ID
 * @returns 图标标识符
 */
export function getModelIcon(modelId: string): string | undefined {
  const spec = findModelSpec(modelId);
  return spec?.icon;
}

/**
 * 统一模型名称解析器 (Standardized Model Name Resolver)
 * 解决 ChatBubble 与 ChatInput/PageTitle 显示不一致的核心逻辑
 *
 * 优先级策略：
 * 1. 优先匹配 UUID (唯一且稳定)
 * 2. 其次匹配 API ID (可能在不同 Provider 间重复，取第一个找到的)
 * 3. 搜索范围覆盖所有 Provider (包括已禁用的)，以确保历史消息能解析出名称
 * 4. 兜底返回原 ID
 *
 * @param modelId 需要解析的模型标识 (可能为 uuid 或 api-id)
 * @returns 模型的显示名称 (name)，或原 ID
 */
export function resolveModelIdToName(modelId: string): string {
  if (!modelId) return '';
  // Avoid re-fetching store if not needed, but ensure fresh state
  const providers = useApiStore.getState().providers;

  // 1. Priority: Exact UUID match (Unique)
  for (const p of providers) {
    const found = p.models.find(m => m.uuid === modelId);
    if (found) return found.name;
  }

  // 2. Priority: API ID match (Ambiguous - takes first matching provider)
  for (const p of providers) {
    const found = p.models.find(m => m.id === modelId);
    if (found) return found.name;
  }

  // 3. Fallback: Return original ID
  return modelId;
}

/**
 * 高参数模型关键词列表
 * 这些模型通常具有较强的工具调用能力，默认开启智能技能
 */
const HIGH_CAPABILITY_KEYWORDS = [
  'gpt-4', 'gpt-4o', 'o1', 'o3',
  'claude-3-5', 'claude-3.5', 'claude-4',
  'gemini-2', 'gemini-1.5-pro', 'gemini-1.5-flash',
  'deepseek-v3', 'deepseek-r1', 'deepseek-reasoner',
  'glm-4.5', 'glm-4.6', 'glm-4.7', 'glm-4-plus',
  'qwen-max', 'qwen-plus', 'qwen2.5-72b', 'qwen2.5-32b',
  'kimi', 'moonshot',
  'llama-3.1-405b', 'llama-3.1-70b', 'llama-3-70b',
  'mistral-large',
];

/**
 * 中低参数模型关键词列表
 * 这些模型工具调用能力较弱或未知，默认关闭智能技能
 */
const LOW_CAPABILITY_KEYWORDS = [
  'gpt-3.5', 'gpt-3',
  'qwen-turbo', 'qwen2.5-7b', 'qwen2.5-14b',
  'doubao-lite', 'doubao-pro-4k',
  'glm-3', 'glm-4-flash',
  'yi-6b', 'yi-medium',
  'baichuan-2', 'baichuan-3',
  'ernie-speed', 'ernie-turbo',
  'llama-2-7b', 'llama-3-8b',
  'mistral-small', 'mistral-7b',
];

/**
 * 判断模型是否为高参数模型（适合开启智能技能）
 * 
 * 判断逻辑：
 * 1. 推理模型（reasoning）默认为高参数
 * 2. 匹配高参数关键词列表
 * 3. 排除中低参数关键词列表
 * 4. 未知模型默认返回 false（保守策略）
 * 
 * @param modelId 模型 ID
 * @returns 是否为高参数模型
 */
export function isHighCapabilityModel(modelId: string): boolean {
  if (!modelId) return false;
  
  const lowerId = modelId.toLowerCase();
  
  // 1. 推理模型默认为高参数
  const spec = findModelSpec(modelId);
  if (spec?.type === 'reasoning' || spec?.capabilities?.reasoning) {
    return true;
  }
  
  // 2. 检查高参数关键词
  for (const keyword of HIGH_CAPABILITY_KEYWORDS) {
    if (lowerId.includes(keyword.toLowerCase())) {
      // 但需要排除中低参数关键词（如 qwen-turbo 包含 qwen）
      for (const lowKeyword of LOW_CAPABILITY_KEYWORDS) {
        if (lowerId.includes(lowKeyword.toLowerCase())) {
          return false;
        }
      }
      return true;
    }
  }
  
  // 3. 检查中低参数关键词
  for (const keyword of LOW_CAPABILITY_KEYWORDS) {
    if (lowerId.includes(keyword.toLowerCase())) {
      return false;
    }
  }
  
  // 4. 未知模型默认关闭（保守策略）
  return false;
}

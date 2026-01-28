import { MODEL_SPECS } from './model-specs';
import { useApiStore } from '../../store/api-store';

/**
 * 辅助函数：从 Store 中查找模型配置
 */
function findStoreConfig(modelId: string) {
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

  // 1. 优先尝试从 Store 中获取（SSOT: 尊重用户在“模型管理”中的手动设置）
  const storeModel = findStoreConfig(modelId);
  if (storeModel) {
    return {
      pattern: storeModel.id,
      contextLength: storeModel.contextLength || 4096,
      type: storeModel.type,
      capabilities: storeModel.capabilities,
      icon: 'api', // Default icon for dynamic models
    };
  }

  // 2. 回退到静态规格库
  for (const spec of MODEL_SPECS) {
    if (typeof spec.pattern === 'string') {
      if (normalizedId.includes(spec.pattern.toLowerCase())) {
        return spec;
      }
    } else {
      if (spec.pattern.test(modelId)) {
        return spec;
      }
    }
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
  if (lowerId.includes('flash-thinking') || lowerId.includes('thinking') || lowerId.includes('gemini-1.5') || lowerId.includes('gemini-2.0') || lowerId.includes('gemini-3')) {
    return true;
  }

  // 2. Check if the model spec has reasoning capability
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

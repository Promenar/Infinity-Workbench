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

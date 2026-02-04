/**
 * 服务商配置解析与管理工具类
 * 遵循项目准则：简体中文注释，严谨的技术实现。
 */

import { ApiProviderType, ModelConfig } from '../store/api-store';
import { findModelSpec } from './llm/model-utils';

console.log('[provider-parser.ts] MODULE LOADED - If you see this, code IS reloaded!');
console.log('[provider-parser.ts] Current timestamp:', new Date().toISOString());

/**
 * 解析 VertexAI (Google Cloud) 服务账号 JSON
 * @param jsonString 原始 JSON 字符串
 */
export const parseVertexAIConfig = (jsonString: string) => {
  try {
    const config = JSON.parse(jsonString);
    // 校验关键字段
    if (!config.project_id || !config.private_key || !config.client_email) {
      throw new Error('Invalid Google Cloud Service Account JSON');
    }
    return {
      projectId: config.project_id,
      privateKey: config.private_key,
      clientEmail: config.client_email,
      location: 'us-central1', // 默认区域
    };
  } catch (error) {
    console.error('VertexAI parsing failed:', error);
    throw error;
  }
};

/**
 * 模型服务类：处理各服务商模型拉取逻辑
 */
export class ModelService {
  /**
   * 根据服务商类型拉取模型列表
   * @param type 服务商类型
   * @param apiKey API 密钥
   * @param baseUrl 自定义 Base URL
   */
  static async fetchModels(
    type: ApiProviderType,
    apiKey: string,
    baseUrl?: string,
  ): Promise<ModelConfig[]> {
    console.log('[ModelService] ===== fetchModels called =====');
    console.log('[ModelService] Type:', type);
    console.log('[ModelService] BaseUrl:', baseUrl);

    // 对于 Google (VertexAI) 等特殊服务商，目前暂返回预设
    if (type === 'google' || type === 'github-copilot' || type === 'local') {
      console.log('[ModelService] Using preset models for type:', type);
      return this.getPresetModels(type);
    }

    let url = baseUrl || this.getDefaultBaseUrl(type);

    // Heuristic: If it's a generic/custom URL and doesn't end with /v1, 
    // Aggregate providers (OneAPI/NewAPI) usually strictly require it.
    if (url && (type === 'openai' || type === 'openai-compatible') && baseUrl) {
      const trimmedUrl = url.trim().replace(/\/+$/, '');
      if (!trimmedUrl.endsWith('/v1') &&
        !trimmedUrl.includes('api.openai.com') &&
        !trimmedUrl.includes('api.deepseek.com') &&
        !trimmedUrl.includes('api.moonshot.cn')) {
        console.log(`[ModelService] Custom URL detected without /v1, attempting to append: ${trimmedUrl}`);
        url = `${trimmedUrl}/v1`;
      }
    }

    const endpoint = `${url?.replace(/\/+$/, '')}/models`;

    try {
      console.log(`[ModelService] Fetching from: ${endpoint}`);

      // Special handling for Gemini: Use x-goog-api-key header or query param
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };

      if (type === 'gemini') {
        headers['x-goog-api-key'] = apiKey;
      } else {
        headers['Authorization'] = `Bearer ${apiKey}`;
      }

      const response = await fetch(endpoint, {
        method: 'GET',
        headers,
      });

      if (!response.ok) {
        // 特殊处理 Cloudflare 等可能返回 404 或 403 的情况
        throw new Error(`HTTP Error: ${response.status}`);
      }

      // Rule 8.4: Capture HTML error pages
      const contentType = response.headers.get('Content-Type') || '';
      if (!contentType.includes('application/json')) {
        const text = await response.text();
        if (text.trim().startsWith('<')) {
          throw new Error(`Received HTML error page instead of JSON for models list.`);
        }
        throw new Error(`Unexpected Content-Type: ${contentType}`);
      }

      const data = await response.json();
      let rawModelIds: string[] = [];

      // 解析 OpenAI 兼容格式
      if (data.data && Array.isArray(data.data)) {
        rawModelIds = data.data.map((m: any) => {
          const id = m.id;
          console.log('[ModelService] Raw model ID:', id);
          // GitHub Models 返回 azureml:// URL 格式，需要提取实际模型名
          // 例如：azureml://registries/azure-openai/models/gpt-4o/versions/2 → gpt-4o
          if (typeof id === 'string' && id.startsWith('azureml://')) {
            const match = id.match(/\/models\/([^\/]+)/);
            const extracted = match ? match[1] : id;
            console.log('[ModelService] Extracted model name:', extracted);
            return extracted;
          }
          return id;
        });
      } else if (Array.isArray(data)) {
        // 某些非标接口直接返回数组
        rawModelIds = data.map((m: any) => {
          const id = typeof m === 'string' ? m : m.id;
          console.log('[ModelService] Raw model ID (array):', id);
          // 同样处理 azureml:// URL
          if (typeof id === 'string' && id.startsWith('azureml://')) {
            const match = id.match(/\/models\/([^\/]+)/);
            const extracted = match ? match[1] : id;
            console.log('[ModelService] Extracted model name (array):', extracted);
            return extracted;
          }
          return id;
        });
      }

      if (rawModelIds.length === 0) {
        return this.getPresetModels(type);
      }

      // 转换为 ModelConfig 并补充元数据
      return rawModelIds.map((id) => this.enrichModelData(id));
    } catch (error) {
      console.error(`Failed to fetch models for ${type}:`, error);
      // 发生错误时降级返回预设，但也要丰富预设模型的元数据
      return this.getPresetModels(type);
    }
  }

  /**
   * 将模型 ID 转换为包含完整元数据的 ModelConfig 对象
   */
  private static enrichModelData(modelId: string): ModelConfig {
    const spec = findModelSpec(modelId);

    return {
      uuid: `${modelId}-${Math.random().toString(36).substr(2, 9)}`, // 确保 UUID 全局唯一，避免不同服务商同名模型冲突
      id: modelId,
      name: modelId, // 暂时使用 ID 作为名称，UI层可能会进一步格式化
      type: spec?.type || 'chat',
      contextLength: spec?.contextLength || 4096,
      capabilities: {
        vision: spec?.capabilities?.vision || false,
        internet: spec?.capabilities?.internet || false,
        reasoning: spec?.capabilities?.reasoning || false,
      },
      enabled: true,
      isAutoFetched: true,
    };
  }

  private static getDefaultBaseUrl(type: ApiProviderType): string {
    const mapping: Record<string, string> = {
      openai: 'https://api.openai.com/v1',
      anthropic: 'https://api.anthropic.com/v1',
      deepseek: 'https://api.deepseek.com',
      moonshot: 'https://api.moonshot.cn/v1',
      zhipu: 'https://open.bigmodel.cn/api/paas/v4',
      siliconflow: 'https://api.siliconflow.cn/v1',
      github: 'https://models.inference.ai.azure.com',
      gemini: 'https://generativelanguage.googleapis.com/v1beta',
      'openai-compatible': '', // Left to user input
    };
    return mapping[type] || '';
  }

  private static getPresetModels(type: ApiProviderType): ModelConfig[] {
    // 根据类型返回一些通用的预设模型列表
    const presets: Record<string, string[]> = {
      openai: ['gpt-4o', 'gpt-4o-mini', 'o1-preview', 'o1-mini'],
      deepseek: ['deepseek-chat', 'deepseek-reasoner'],
      google: ['gemini-2.0-flash-thinking-exp', 'gemini-1.5-pro', 'gemini-1.5-flash'],
      gemini: ['gemini-2.0-flash-thinking-exp', 'gemini-2.0-flash', 'gemini-1.5-pro', 'gemini-1.5-flash'],
      moonshot: ['moonshot-v1-8k', 'moonshot-v1-32k'],
      zhipu: ['glm-4', 'glm-4-flash', 'glm-4-plus', 'glm-4.7'],
      siliconflow: [
        'deepseek-ai/DeepSeek-V3',
        'Qwen/Qwen2.5-72B-Instruct',
        'BAAI/bge-reranker-v2-m3',
      ],
      github: ['gpt-4o', 'claude-3-5-sonnet'],
    };

    const ids = presets[type] || [];
    return ids.map((id) => this.enrichModelData(id));
  }
}

/**
 * Token 计数工具类 (简易实现，后续可集成 tiktoken)
 */
export class TokenCounter {
  /**
   * 粗略估算字符串的 Token 量
   * @param text 文本内容
   */
  static estimateTokens(text: string): number {
    if (!text) return 0;
    // 简易逻辑：中文字符数 * 2 + 单词数
    const chineseCount = (text.match(/[\u4e00-\u9fa5]/g) || []).length;
    const words = text.split(/\s+/).length;
    return chineseCount * 2 + words;
  }
}

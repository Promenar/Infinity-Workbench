import { GeneratedImageData, TokenUsage } from '../../types/chat';
import { MessagePreprocessor } from './message-preprocessor';

/**
 * 引用/来源
 */
export interface Citation {
  title: string;
  url: string;
  source?: string;
}

/**
 * 标准化后的响应块
 */
export interface NormalizedChunk {
  content: string;
  reasoning?: string;
  citations?: Citation[];
  images?: GeneratedImageData[];
  tokens?: TokenUsage;
}

/**
 * 支持的 Provider 类型
 */
export type ProviderType =
  | 'vertex'
  | 'openai'
  | 'gemini'
  | 'google'  // VertexAI uses 'google' as provider type
  | 'local'   // Llama.rn local inference
  | 'siliconflow'
  | 'zhipu'
  | 'moonshot'
  | 'deepseek'
  | 'github';

/**
 * 响应标准化器
 *
 * 职责：将各 Provider 的原始响应格式统一为标准格式
 * 支持的 Provider: Vertex AI, OpenAI, Gemini, SiliconFlow, Zhipu, Kimi, DeepSeek, GitHub
 */
export class ResponseNormalizer {
  /**
   * 统一各 Provider 的响应格式
   *
   * @param rawResponse Provider 原始响应
   * @param providerType Provider 类型
   * @returns 标准化后的响应块
   */
  static async normalize(rawResponse: any, providerType: ProviderType): Promise<NormalizedChunk> {
    switch (providerType) {
      case 'vertex':
      case 'gemini':
      case 'google':
        return this.normalizeVertex(rawResponse);
      case 'openai':
      case 'siliconflow':
      case 'github':
        return this.normalizeOpenAI(rawResponse);
      case 'deepseek':
        return this.normalizeDeepSeek(rawResponse);
      case 'zhipu':
        return this.normalizeZhipu(rawResponse);
      case 'moonshot':
        return this.normalizeMoonshot(rawResponse);
      default:
        return this.normalizeGeneric(rawResponse);
    }
  }

  /**
   * 标准化 Vertex AI / Gemini 响应
   */
  private static async normalizeVertex(raw: any): Promise<NormalizedChunk> {
    const candidate = raw.candidates?.[0];
    if (!candidate) {
      return { content: '' };
    }

    let content = '';
    let reasoning = '';
    const images: GeneratedImageData[] = [];

    // 解析 parts
    if (candidate.content?.parts) {
      for (const part of candidate.content.parts) {
        // 1. Thinking/Reasoning
        const isThoughtPart = part.thought === true || typeof part.thought === 'string';
        if (isThoughtPart) {
          if (typeof part.thought === 'string') {
            reasoning += part.thought;
          }
          if (part.text) {
            reasoning += part.text;
          }
        }
        // 2. Text Content
        else if (part.text) {
          // 如果整个 chunk 包含 thought，文本也算 reasoning
          const chunkHasThought = candidate.content.parts.some((p: any) => p.thought === true);
          if (chunkHasThought) {
            reasoning += part.text;
          } else {
            content += part.text;
          }
        }
        // 3. Images
        else if (part.inline_data || part.inlineData) {
          const inlineData = part.inline_data || part.inlineData;
          try {
            const imgData = await MessagePreprocessor.processAIGeneratedImage(
              inlineData.data,
              inlineData.mime_type || inlineData.mimeType || 'image/png',
            );
            images.push(imgData);
          } catch (error) {
            console.error('[ResponseNormalizer] Failed to process AI image:', error);
          }
        }
      }
    }

    // 提取 Citations
    const citations = this.extractVertexCitations(candidate, raw);

    // 提取 Token 使用情况
    const tokens = this.extractVertexTokens(raw);

    return {
      content,
      reasoning: reasoning || undefined,
      citations,
      images: images.length > 0 ? images : undefined,
      tokens,
    };
  }

  /**
   * 标准化纯 OpenAI 响应
   * 适用于：OpenAI、SiliconFlow、GitHub Models
   */
  private static async normalizeOpenAI(raw: any): Promise<NormalizedChunk> {
    const delta = raw.choices?.[0]?.delta;
    if (!delta) {
      return { content: '' };
    }

    return {
      content: delta.content || '',
      // 注意：原生OpenAI不支持reasoning_content回传（仅o1系列输出）
      reasoning: delta.reasoning_content || undefined,
    };
  }

  /**
   * 标准化 DeepSeek 响应
   * 特性：支持 reasoning_content、<think> 标签
   */
  private static async normalizeDeepSeek(raw: any): Promise<NormalizedChunk> {
    const delta = raw.choices?.[0]?.delta;
    if (!delta) {
      return { content: '' };
    }

    // DeepSeek 支持 reasoning_content 输出
    // 注意：<think> 标签的处理在 openai.ts 的网络层已完成
    return {
      content: delta.content || '',
      reasoning: delta.reasoning_content || undefined,
    };
  }

  /**
   * 标准化月之暗面(KIMI) 响应
   * 特性：基本兼容OpenAI，部分模型支持思维链
   */
  private static async normalizeMoonshot(raw: any): Promise<NormalizedChunk> {
    const delta = raw.choices?.[0]?.delta;
    if (!delta) {
      return { content: '' };
    }

    return {
      content: delta.content || '',
      // KIMI部分模型支持reasoning输出
      reasoning: delta.reasoning_content || undefined,
    };
  }

  /**
   * 标准化智谱AI(GLM) 响应
   * 特性：支持 XML 工具调用、兼容 OpenAI 流式格式
   */
  private static async normalizeZhipu(raw: any): Promise<NormalizedChunk> {
    const delta = raw.choices?.[0]?.delta;
    if (!delta) {
      return { content: '' };
    }

    // GLM 基本兼容 OpenAI，但可能在content中混入XML工具调用
    // XML清理由 StreamParser 负责
    return {
      content: delta.content || '',
      reasoning: delta.reasoning_content || undefined,
    };
  }

  /**
   * 通用降级处理
   */
  private static async normalizeGeneric(raw: any): Promise<NormalizedChunk> {
    return {
      content: raw.content || raw.text || raw.message || '',
    };
  }

  /**
   * 提取 Vertex AI Citations
   */
  private static extractVertexCitations(candidate: any, response: any): Citation[] | undefined {
    const metadata = candidate.groundingMetadata || response.groundingMetadata;
    if (!metadata?.groundingChunks) {
      return undefined;
    }

    const citations = metadata.groundingChunks
      .map((chunk: any) => {
        if (chunk.web) {
          return {
            title: chunk.web.title || 'Web Source',
            url: chunk.web.uri,
            source: 'Google',
          };
        }
        return null;
      })
      .filter(Boolean);

    return citations.length > 0 ? citations : undefined;
  }

  /**
   * 提取 Vertex AI Token Usage
   */
  private static extractVertexTokens(response: any): TokenUsage | undefined {
    const usage = response.usageMetadata;
    if (!usage) {
      return undefined;
    }

    return {
      input: usage.promptTokenCount || 0,
      output: usage.candidatesTokenCount || 0,
      total: usage.totalTokenCount || 0,
    };
  }
}

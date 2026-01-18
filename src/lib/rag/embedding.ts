import { ProviderConfig } from '../../store/api-store';
import { OpenAiClient } from '../llm/providers/openai';
import { VertexAiClient } from '../llm/providers/vertexai';
import { useLocalModelStore } from '../local-inference/LocalModelServer';
/**
 * Embedding 客户端 - 复用现有 Provider 实现
 *
 * 直接调用 OpenAiClient 和 VertexAiClient 的底层方法。
 * 优势：
 * - 代码复用，避免重复实现
 * - 认证逻辑统一（Token 获取、刷新等）
 * - 维护成本低
 *
 * @example
 * ```typescript
 * const client = new EmbeddingClient(provider, 'gemini-embedding-001');
 * const result = await client.embedDocuments(['hello', 'world']);
 * ```
 */
export class EmbeddingClient {
  private provider: ProviderConfig;
  private model: string;
  private isVertexAI: boolean;
  private vertexClient?: VertexAiClient;
  private openaiClient?: OpenAiClient;

  constructor(provider: ProviderConfig, model?: string) {
    this.provider = provider;
    this.model = model || 'text-embedding-3-small';
    this.isVertexAI = !!(provider.vertexProject && provider.vertexLocation);

    // 初始化对应的 provider 客户端
    if (this.isVertexAI) {
      this.vertexClient = new VertexAiClient({
        apiKey: provider.apiKey,
        model: this.model,
        temperature: 0,
        baseUrl: provider.baseUrl || '',
        project: provider.vertexProject,
        location: provider.vertexLocation,
        keyJson: provider.vertexKeyJson,
      });
    } else if (provider.type === 'google' || provider.type === 'gemini') {
      // Gemini API 使用 fetch 直接调用（简单场景）
      // 不需要初始化客户端
    } else {
      // OpenAI 兼容 provider
      this.openaiClient = new OpenAiClient(
        provider.apiKey,
        this.model,
        0, // temperature 对 embedding 无意义
        provider.baseUrl || 'https://api.openai.com/v1',
        { isEmbedding: true },
      );
    }
  }

  /**
   * 批量 Embedding 入口
   */
  async embedDocuments(
    texts: string[],
  ): Promise<{ embeddings: number[][]; usage?: { total_tokens: number } }> {
    if (!texts || texts.length === 0) {
      throw new Error('No texts provided for embedding');
    }

    // Local Provider
    if (this.provider.type === 'local') {
      return this.embedLocal(texts);
    }

    // Vertex AI
    if (this.isVertexAI && this.vertexClient) {
      return this.embedVertexAI(texts);
    }

    // Gemini API
    if (this.provider.type === 'google' || this.provider.type === 'gemini') {
      return this.embedGemini(texts);
    }

    // OpenAI 兼容
    if (this.openaiClient) {
      return this.embedOpenAI(texts);
    }

    throw new Error(`Unsupported provider: ${this.provider.type}`);
  }

  /**
   * 单条 Embedding（便捷方法）
   */
  async embedQuery(
    text: string,
  ): Promise<{ embedding: number[]; usage?: { total_tokens: number } }> {
    const result = await this.embedDocuments([text]);
    return {
      embedding: result.embeddings[0],
      usage: result.usage,
    };
  }

  /**
   * Vertex AI Embedding（批量支持）
   */
  private async embedVertexAI(
    texts: string[],
  ): Promise<{ embeddings: number[][]; usage?: { total_tokens: number } }> {
    const project = this.provider.vertexProject!;
    const location = this.provider.vertexLocation || 'us-central1';

    // 获取认证 token（复用 VertexAiClient 的认证逻辑）
    const token = await (this.vertexClient as any).getAccessToken();

    // 构建端点
    const region = location;
    const host =
      region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;
    const endpoint = `https://${host}/v1beta1/projects/${project}/locations/${region}/publishers/google/models/${this.model}:predict`;

    // 批量处理（Vertex AI 最多 250 条）
    const batchSize = 250;
    const allEmbeddings: number[][] = [];

    for (let i = 0; i < texts.length; i += batchSize) {
      const batch = texts.slice(i, i + batchSize);

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          instances: batch.map((text) => ({ content: text })),
        }),
      });

      if (!response.ok) {
        const contentType = response.headers.get('Content-Type') || '';
        const errorText = await response.text();
        if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
          throw new Error(`Vertex AI Embedding failed: HTTP ${response.status} (Received HTML/Non-JSON response)`);
        }
        throw new Error(
          `Vertex AI Embedding failed: ${response.status} ${errorText.substring(0, 200)}`,
        );
      }

      const contentType = response.headers.get('Content-Type') || '';
      if (!contentType.includes('application/json')) {
        const text = await response.text();
        throw new Error(`Vertex AI Embedding failed: Expected JSON but got ${contentType}. Content: ${text.substring(0, 100)}`);
      }

      const data = await response.json();

      if (!data.predictions || !Array.isArray(data.predictions)) {
        throw new Error('Invalid Vertex AI response: missing predictions array');
      }

      const embeddings = data.predictions.map((pred: any) => {
        if (!pred.embeddings?.values) {
          throw new Error('Invalid Vertex AI response: missing embeddings.values');
        }
        return pred.embeddings.values;
      });

      allEmbeddings.push(...embeddings);
    }

    return {
      embeddings: allEmbeddings,
      usage: undefined, // Vertex AI 不返回 token usage
    };
  }

  /**
   * Gemini API Embedding（逐条调用）
   */
  private async embedGemini(
    texts: string[],
  ): Promise<{ embeddings: number[][]; usage?: { total_tokens: number } }> {
    const baseUrl = this.provider.baseUrl || 'https://generativelanguage.googleapis.com';
    const cleanBase = baseUrl.replace(/\/v1\/?$/, '').replace(/\/$/, '');
    const endpoint = `${cleanBase}/v1beta/models/${this.model}:embedContent`;

    const embeddings: number[][] = [];

    // Gemini API 不支持批量，必须逐条调用
    for (const text of texts) {
      const response = await fetch(`${endpoint}?key=${this.provider.apiKey}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          content: {
            parts: [{ text }],
          },
        }),
      });

      if (!response.ok) {
        const contentType = response.headers.get('Content-Type') || '';
        const errorText = await response.text();
        if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
          throw new Error(`Gemini Embedding failed: HTTP ${response.status} (Received HTML/Non-JSON response)`);
        }
        throw new Error(
          `Gemini Embedding failed: ${response.status} ${errorText.substring(0, 200)}`,
        );
      }

      const contentType = response.headers.get('Content-Type') || '';
      if (!contentType.includes('application/json')) {
        const text = await response.text();
        throw new Error(`Gemini Embedding failed: Expected JSON but got ${contentType}. Content: ${text.substring(0, 100)}`);
      }

      const data = await response.json();

      if (!data.embedding?.values) {
        throw new Error('Invalid Gemini response: missing embedding.values');
      }

      embeddings.push(data.embedding.values);
    }

    return {
      embeddings,
      usage: undefined, // Gemini API 不返回 token usage
    };
  }

  /**
   * OpenAI 兼容 Embedding（批量支持）
   */
  private async embedOpenAI(
    texts: string[],
  ): Promise<{ embeddings: number[][]; usage?: { total_tokens: number } }> {
    if (!this.openaiClient) {
      throw new Error('OpenAI client not initialized');
    }

    // OpenAI 批量限制处理：OpenAiClient.embeddings 会处理输入数组，
    // 但这里我们保留批量切片逻辑，以保持对 API 代理（如 SiliconFlow）的保守兼容性。
    const batchSize = 50;
    const allEmbeddings: number[][] = [];
    let totalTokens = 0;

    for (let i = 0; i < texts.length; i += batchSize) {
      const batch = texts.slice(i, i + batchSize);
      const result = await this.openaiClient.embeddings(batch);

      allEmbeddings.push(...result.embeddings);
      totalTokens += result.usage.total_tokens;
    }

    return {
      embeddings: allEmbeddings,
      usage: totalTokens > 0 ? { total_tokens: totalTokens } : undefined,
    };
  }


  /**
   * Local Embedding (via llama.rn)
   */
  private async embedLocal(
    texts: string[],
  ): Promise<{ embeddings: number[][]; usage?: { total_tokens: number } }> {
    const store = useLocalModelStore.getState();
    const isAvailable = store.embedding.isLoaded || store.main.isLoaded;
    if (!isAvailable) {
      throw new Error('Local embedding model not loaded (check Embedding slot or Main slot)');
    }

    const embeddings: number[][] = [];
    // llama.rn currently processes one by one (or check update)
    // We loop for now.
    for (const text of texts) {
      const emb = await store.generateEmbedding(text);
      embeddings.push(emb);
    }

    return {
      embeddings,
      // No token usage from local yet
    };
  }
}

import { ProviderConfig } from '../../store/api-store';
import { SearchResult } from './vector-store';
import { useLocalModelStore } from '../local-inference/LocalModelServer';

export interface RerankResult {
  index: number;
  relevance_score: number;
  document: {
    text: string;
  };
}

export class RerankClient {
  constructor(
    private provider: ProviderConfig,
    private modelId?: string,
  ) { }

  /**
   * Rerank a list of documents based on the query.
   * Compatible with Jina, Cohere, and SiliconFlow (BGE) formats.
   */
  async rerank(
    query: string,
    documents: SearchResult[],
    topK: number,
    onProgress?: (stats: { txBytes?: number; rxBytes?: number; latency?: number }) => void,
  ): Promise<SearchResult[]> {
    if (!this.provider || !documents.length) {
      return documents;
    }

    // Local Rerank
    if (this.provider.type === 'local') {
      const store = useLocalModelStore.getState();
      const isAvailable = store.rerank.isLoaded || store.main.isLoaded;
      if (isAvailable) {
        try {
          // Rerank using local model
          // Note: local model rerank support depends on model type
          // But llama.rn exposes it if supported.
          const results = await store.performRerank(query, documents.map(d => d.content), topK);
          console.log(`[RerankClient] RAW:`, results);

          const reordered: SearchResult[] = [];
          const sorted = (results as any[]).sort((a, b) => {
            const sA = a.score ?? a.relevance_score ?? 0;
            const sB = b.score ?? b.relevance_score ?? 0;
            return sB - sA;
          });

          for (const res of sorted) {
            const score = res.score ?? res.relevance_score ?? 0;
            if (res.index >= 0 && res.index < documents.length) {
              const original = documents[res.index];
              reordered.push({
                ...original,
                similarity: score,
                originalSimilarity: original.originalSimilarity ?? original.similarity
              });
            }
          }

          // If topK specified, slice
          const finalResult = reordered.slice(0, topK);

          // CRITICAL: If rerank returned nothing (but we had input), fallback to original order
          // to avoid wiping out retrieval results.
          if (finalResult.length === 0 && documents.length > 0) {
            console.warn('[RerankClient] Local rerank returned 0 results, falling back to original order');
            return documents;
          }

          return finalResult;

        } catch (e) {
          console.warn('[RerankClient] Local rerank failed', e);
          return documents;
        }
      }
    }

    try {
      // 1. Construct Rerank API request
      const requestBody = {
        model: this.modelId,
        query: query,
        documents: documents.map((d) => d.content),
        top_n: topK,
      };

      // 2. Send request to /v1/rerank
      // Ensure no double slash if baseUrl ends with /
      let baseUrl = this.provider.baseUrl?.replace(/\/+$/, '') || '';
      const suffix = baseUrl.endsWith('/v1') ? '/rerank' : '/v1/rerank';
      const endpoint = `${baseUrl}${suffix}`;

      console.log(
        `[RerankClient] Reranking ${documents.length} docs with model ${this.modelId} at ${endpoint}`,
      );

      const bodyString = JSON.stringify(requestBody);
      const startTime = Date.now();

      // Report TX (Upload)
      onProgress?.({ txBytes: bodyString.length });

      // 进入等待阶段 (解耦 TX 与 WAIT)
      onProgress?.({});

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.provider.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: bodyString,
      }).catch(err => {
        // 🛡️ 核心修复：捕获 fetch 级别的原始错误 (TypeError: Network request failed)
        // 防止其作为未捕获异常触发 React Native 红屏
        console.warn('[RerankClient] Fetch failed (silently falling back):', err.message);
        return null;
      });

      if (!response) return documents;

      // 🛡️ 准则 8.4: 校验内容类型并处理非 JSON 响应
      const contentType = response.headers.get('Content-Type') || '';
      if (!response.ok || !contentType.includes('application/json')) {
        const errorText = await response.text();
        console.error(
          `[RerankClient] API Error or Non-JSON Response: ${response.status} (${contentType}) - ${errorText.substring(0, 200)}`,
        );
        // Fallback: return original results
        return documents;
      }

      // 3. Parse result and reorder
      const rawText = await response.text();
      const latency = Date.now() - startTime;

      // Report RX (Download)
      onProgress?.({ rxBytes: rawText.length, latency });

      const data = JSON.parse(rawText);

      // Validate response format
      if (!data.results || !Array.isArray(data.results)) {
        console.error('[RerankClient] Invalid response format:', data);
        return documents;
      }

      const rerankResults = data.results as RerankResult[];

      // 4. Map back to SearchResult and sort
      // The API returns indices, we need to map them back to original documents
      const reorderedResults: SearchResult[] = [];

      for (const res of rerankResults) {
        const originalDoc = documents[res.index];
        if (originalDoc) {
          reorderedResults.push({
            ...originalDoc,
            similarity: res.relevance_score, // Update similarity with rerank score
          });
        }
      }

      // If we got fewer results than expected from API, we trust the API's top_n logic.
      // But if we want to ensure we return something even if API returns partial,
      // the above loop only adds what API returned.

      // If API returned nothing or empty, we fallback to original?
      // Usually API returns valid results if 200 OK.
      if (reorderedResults.length === 0 && documents.length > 0) {
        // If for some reason reordered is empty but we had docs, return original (fallback)
        return documents.slice(0, topK);
      }

      return reorderedResults;
    } catch (error) {
      console.error('[RerankClient] Network or parsing error:', error);
      // Fallback to original order on error
      return documents; // Return original documents, they are already somewhat sorted by vector
    }
  }
}

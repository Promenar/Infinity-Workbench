import { useApiStore } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';
import { createLlmClient, ExtendedModelConfig } from '../llm/factory';
import { graphStore } from './graph-store';
import { getDefaultKgPrompt, getKgFreeModePrompt, getKgDomainAutoPrompt } from './defaults';
import { db } from '../db';
import { SearchResult } from './vector-store';

export interface MicroGraphResult {
  nodes: Array<{ name: string; type: string; metadata?: any }>;
  edges: Array<{ source: string; target: string; relation: string; weight?: number }>;
  context: string;
  sourceChunkIds: string[];
  query: string;
  extractedAt: number;
}

export class MicroGraphExtractor {
  /**
   * 基于召回文本动态提取微型图谱
   */
  async extract(
    topKResults: SearchResult[],
    query: string,
    sessionId: string,
    options?: {
      timeout?: number;      // 默认 5000ms
      maxChars?: number;     // 总输入截断 (默认 6000)
      onProgress?: (subStage: string) => void;
    }
  ): Promise<MicroGraphResult | null> {
    const timeout = options?.timeout || 5000;
    const maxChars = options?.maxChars || 6000;

    // 1. Cache Check
    const cacheKey = this.generateCacheKey(query, topKResults);
    const cached = await this.getFromCache(cacheKey);
    if (cached) {
      console.log('[MicroGraph] Cache HIT for key:', cacheKey.substring(0, 8));
      return cached;
    }

    try {
      // 2. Prepare Payload
      const textToAnalyze = this.prepareInputText(topKResults, maxChars);
      if (!textToAnalyze) return null;

      // 3. Extraction with Timeout
      options?.onProgress?.('KG_JIT');
      const extractionPromise = this.performExtraction(textToAnalyze, query, options?.onProgress);
      const timeoutPromise = new Promise<null>((resolve) => 
        setTimeout(() => resolve(null), timeout)
      );

      const result = await Promise.race([extractionPromise, timeoutPromise]);

      if (result) {
        const fullResult: MicroGraphResult = {
          ...result,
          sourceChunkIds: topKResults.map(r => r.id),
          query,
          extractedAt: Date.now()
        };
        // 4. Save to cache
        await this.saveToCache(cacheKey, fullResult);
        // 5. Background merge (Don't await)
        this.backgroundMerge(fullResult, sessionId);
        
        return fullResult;
      }

      console.warn('[MicroGraph] Extraction TIMEOUT or FAILED');
      return null;
    } catch (e) {
      console.error('[MicroGraph] Critical error during extraction:', e);
      return null;
    }
  }

  private generateCacheKey(query: string, results: SearchResult[]): string {
    const ids = results.map(r => r.id).sort().join(',');
    return `${query}:${ids}`;
  }

  private async getFromCache(key: string): Promise<MicroGraphResult | null> {
    try {
      // Opportunistic cleanup: delete expired entries on cache read
      await this.cleanupExpiredCache();

      const res = await db.execute(
        'SELECT result_json FROM kg_jit_cache WHERE cache_key = ? AND expires_at > ?',
        [key, Date.now()]
      );
      if (res.rows && res.rows.length > 0) {
        return JSON.parse((res.rows as any)[0].result_json);
      }
    } catch (e) {
      console.warn('[MicroGraph] Cache read failed:', e);
    }
    return null;
  }

  private async saveToCache(key: string, result: MicroGraphResult): Promise<void> {
    try {
      const settings = useSettingsStore.getState();
      const ttl = (settings.globalRagConfig.jitCacheTTL || 3600) * 1000;
      const expiresAt = Date.now() + ttl;

      await db.execute(
        'INSERT OR REPLACE INTO kg_jit_cache (cache_key, query_hash, chunk_ids_hash, result_json, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)',
        [key, result.query, result.sourceChunkIds.join(','), JSON.stringify(result), Date.now(), expiresAt]
      );
    } catch (e) {
      console.warn('[MicroGraph] Cache save failed:', e);
    }
  }

  /**
   * 清理过期的 JIT 缓存条目
   */
  private async cleanupExpiredCache(): Promise<void> {
    try {
      await db.execute(
        'DELETE FROM kg_jit_cache WHERE expires_at <= ?',
        [Date.now()]
      );
    } catch (e) {
      // Silently ignore — cleanup is opportunistic
    }
  }

  private prepareInputText(results: SearchResult[], maxChars: number): string {
    let totalText = '';
    for (const res of results) {
      const remaining = maxChars - totalText.length;
      if (remaining <= 0) break;
      totalText += res.content.substring(0, remaining) + '\n---\n';
    }
    return totalText.trim();
  }

  /**
   * 提取 LLM 响应中的 JSON（三层 fallback，与 graph-extractor.ts 保持一致）
   */
  private extractJSONFromContent(content: string): any | null {
    let jsonString = content.trim();

    // Layer 1: ```json ... ```
    const jsonBlockMatch = jsonString.match(/```json\s*([\s\S]*?)\s*```/i);
    if (jsonBlockMatch) {
      jsonString = jsonBlockMatch[1].trim();
    } else {
      // Layer 2: generic code block that starts with {
      const genericMatch = jsonString.match(/```\s*([\s\S]*?)\s*```/);
      if (genericMatch) {
        const potential = genericMatch[1].trim();
        if (potential.startsWith('{')) {
          jsonString = potential;
        }
      } else {
        // Layer 3: outermost { ... }
        const first = jsonString.indexOf('{');
        const last = jsonString.lastIndexOf('}');
        if (first !== -1 && last !== -1 && last > first) {
          jsonString = jsonString.substring(first, last + 1);
        }
      }
    }

    try {
      return JSON.parse(jsonString);
    } catch (e) {
      console.warn('[MicroGraph] JSON parse failed:', e);
      console.log('[MicroGraph] Raw output preview:', content.slice(0, 200) + '...');
      return null;
    }
  }

  /**
   * 组装 JIT 专用 system prompt（复用 graph-extractor 的策略）
   */
  private buildJitPrompt(): string {
    const settingsState = useSettingsStore.getState();
    const config = settingsState.globalRagConfig;
    const customPrompt = config.kgExtractionPrompt;
    const entityTypes = config.kgEntityTypes || [];

    // 1. Base prompt
    let basePrompt = '';
    const isFreeMode = config.kgFreeMode || !entityTypes || entityTypes.length === 0;

    if (customPrompt && customPrompt.trim().length > 0) {
      basePrompt = customPrompt;
      if (basePrompt.includes('{entityTypes}')) {
        basePrompt = basePrompt.replace('{entityTypes}', entityTypes.length > 0 ? entityTypes.join(', ') : 'any meaningful types');
      }
    } else {
      basePrompt = isFreeMode ? getKgFreeModePrompt() : getDefaultKgPrompt();
      if (!isFreeMode && basePrompt.includes('{entityTypes}')) {
        basePrompt = basePrompt.replace('{entityTypes}', entityTypes.join(', '));
      }
    }

    // 2. Domain instruction (unified: kgDomainAuto OR kgDomainHint==='auto')
    if (config.kgDomainHint === 'auto' || config.kgDomainAuto) {
      basePrompt += '\n\n' + getKgDomainAutoPrompt();
    } else if (config.kgDomainHint) {
      basePrompt += `\n\nFocus on the context of: ${config.kgDomainHint}`;
    }

    return basePrompt;
  }

  private async performExtraction(text: string, query: string, onProgress?: (s: string) => void): Promise<any> {
    try {
      const settingsState = useSettingsStore.getState();
      const apiState = useApiStore.getState();

      onProgress?.('KG_JIT_PARSE');

      // Model Resolution
      const modelId = settingsState.globalRagConfig.kgExtractionModel || settingsState.defaultSummaryModel;
      let provider = apiState.providers.find(p => p.enabled && p.models.some(m => m.id === modelId || m.uuid === modelId));

      if (!provider) {
        provider = apiState.providers.find(p => p.enabled && p.models.length > 0);
      }

      if (!provider || !modelId) {
        console.warn('[MicroGraph] No model available for JIT KG extraction');
        return null;
      }

      const model = provider.models.find(m => m.id === modelId || m.uuid === modelId) || provider.models[0];
      const client = createLlmClient({
        ...model,
        provider: provider.type,
        apiKey: provider.apiKey,
        baseUrl: provider.baseUrl,
        vertexProject: provider.vertexProject,
        vertexLocation: provider.vertexLocation,
        vertexKeyJson: provider.vertexKeyJson,
      });

      // Build system prompt using unified strategy
      const systemPrompt = this.buildJitPrompt();

      const response = await client.chatCompletion([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: `Query Context: "${query}"\n\nAnalyze the following text fragments and build a micro-graph of related entities:\n\n${text}` }
      ]);

      const content = response.content;
      if (!content) return null;

      // Three-layer JSON extraction (consistent with graph-extractor.ts)
      const result = this.extractJSONFromContent(content);
      if (!result || !result.nodes || !result.edges) {
        console.warn('[MicroGraph] Failed to extract valid JSON with nodes/edges from LLM response');
        return null;
      }

      // Build context string from edges
      const context = result.edges?.map((e: any) => `- ${e.source} --[${e.relation}]--> ${e.target}`).join('\n') || '';

      return { ...result, context };
    } catch (e) {
      console.error('[MicroGraph] performExtraction error:', e);
      return null;
    }
  }

  private async backgroundMerge(result: MicroGraphResult, sessionId: string): Promise<void> {
    try {
      console.log(`[MicroGraph] Background merging ${result.nodes.length} nodes to Global Graph...`);
      const nameToIdMap = new Map<string, string>();

      // Transactional merge
      await db.execute('BEGIN TRANSACTION');
      
      for (const node of result.nodes) {
        const id = await graphStore.upsertNode(node.name, node.type, node.metadata, { sessionId }, 'jit');
        nameToIdMap.set(node.name, id);
      }

      for (const edge of result.edges) {
        const sourceId = nameToIdMap.get(edge.source);
        const targetId = nameToIdMap.get(edge.target);
        if (sourceId && targetId) {
          await graphStore.createEdge(sourceId, targetId, edge.relation, undefined, edge.weight || 1.0, { sessionId }, 'jit');
        }
      }

      await db.execute('COMMIT');
      console.log('[MicroGraph] Background merge SUCCESS');
    } catch (e) {
      await db.execute('ROLLBACK');
      console.warn('[MicroGraph] Background merge failed:', e);
    }
  }
}

export const microGraphExtractor = new MicroGraphExtractor();

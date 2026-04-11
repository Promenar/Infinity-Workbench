import { useApiStore } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';
import { createLlmClient, ExtendedModelConfig } from '../llm/factory';
import { graphStore } from './graph-store';
import { getDefaultKgPrompt, getKgFreeModePrompt, getKgDomainAutoPrompt } from './defaults';
import { getPrompts, getPromptLang } from '../llm/prompts/i18n';
import { useRagStore } from '../../store/rag-store';

interface ExtractionResult {
  nodes: Array<{
    name: string;
    type: string;
    metadata?: any;
  }>;
  edges: Array<{
    source: string;
    target: string;
    relation: string;
    weight?: number;
  }>;
  /** 🔑 非空时表示提取过程遇到错误（但未抛出异常） */
  error?: string;
}

export class GraphExtractor {
  private getClient(modelId?: string) {
    const apiState = useApiStore.getState();
    const settingsState = useSettingsStore.getState();

    // 1. Determine Model ID
    let targetId =
      modelId ||
      settingsState.globalRagConfig.kgExtractionModel ||
      settingsState.defaultSummaryModel;

    if (!targetId) {
      // Fallback: Find first enabled chat model
      for (const p of apiState.providers) {
        if (p.enabled && p.models.length > 0) {
          targetId = p.models[0].id; // Just grab the first one
          break;
        }
      }
    }

    if (!targetId) {
      throw new Error('No available model for Knowledge Graph extraction');
    }

    // 2. Find all matching providers
    const candidates: Array<{ provider: any; model: any }> = [];

    for (const provider of apiState.providers) {
      if (!provider.enabled) continue;
      const modelConfig = provider.models.find((m) => m.id === targetId || m.uuid === targetId);
      if (modelConfig) {
        candidates.push({ provider, model: modelConfig });
      }
    }

    if (candidates.length === 0) {
      console.error(`[GraphExtractor] Model resolution failed for ID: ${targetId}`);
      const availableProviders = apiState.providers
        .filter((p) => p.enabled)
        .map((p) => `${p.name} (${p.models.length} models)`);
      console.warn('[GraphExtractor] Available enabled providers:', availableProviders);
      throw new Error(`Model '${targetId}' not found in any enabled provider`);
    }

    // 3. Select Best Provider (Heuristic Priority)
    // If multiple providers support the same model ID, prioritize the "Native" or "Official" one.
    let selected = candidates[0];

    if (candidates.length > 1) {
      console.log(`[GraphExtractor] Found ${candidates.length} candidates for ${targetId}. Applying heuristics...`);
      const lowerId = targetId.toLowerCase();

      const priorityMap: Record<string, string[]> = {
        'gemini': ['google', 'gemini', 'vertex'], // Gemini models prefer Google/Vertex
        'gpt': ['openai', 'github', 'azure'],     // GPT models prefer OpenAI/GitHub
        'claude': ['anthropic'],                  // Claude models prefer Anthropic
        'deepseek': ['deepseek'],                 // DeepSeek models prefer DeepSeek
      };

      // Find the key that matches the model ID start
      const matchedKey = Object.keys(priorityMap).find(key => lowerId.includes(key));

      if (matchedKey) {
        const preferredTypes = priorityMap[matchedKey];
        // Sort candidates so preferred ones come first
        const bestCandidate = candidates.find(c => preferredTypes.includes(c.provider.type));
        if (bestCandidate) {
          selected = bestCandidate;
          console.log(`[GraphExtractor] Priority Heuristic: Selected ${selected.provider.name} for ${targetId}`);
        }
      }
    }

    const { provider, model } = selected;
    console.log(`[GraphExtractor] Using Provider: ${provider.name} (${provider.type}) for Model: ${targetId}`);

    const extendedConfig: ExtendedModelConfig = {
      ...model,
      provider: provider.type,
      apiKey: provider.apiKey,
      baseUrl: provider.baseUrl,
      vertexProject: provider.vertexProject,
      vertexLocation: provider.vertexLocation,
      vertexKeyJson: provider.vertexKeyJson,
    };
    return createLlmClient(extendedConfig);
  }

  private getSystemPrompt(): string {
    const settingsState = useSettingsStore.getState();
    const config = settingsState.globalRagConfig;
    const customPrompt = config.kgExtractionPrompt;
    
    // 1. Determine base prompt
    let basePrompt = '';
    const isFreeMode = config.kgFreeMode || !config.kgEntityTypes || config.kgEntityTypes.length === 0;
    const entityTypes = config.kgEntityTypes || [];

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

    // 2. Append Domain Auto-Detection instruction if enabled
    // Unified: kgDomainAuto flag OR kgDomainHint==='auto' both trigger auto-detection
    if (config.kgDomainAuto || config.kgDomainHint === 'auto') {
      basePrompt += '\n\n' + getKgDomainAutoPrompt();
    } else if (config.kgDomainHint) {
      basePrompt += `\n\nFocus on the context of: ${config.kgDomainHint}`;
    }

    return basePrompt;
  }

  /**
   * Extract entities and relationships from a text chunk
   */
  async extractAndSave(
    text: string,
    docId?: string,
    scope?: { sessionId?: string; agentId?: string; messageId?: string },
    onStatusUpdate?: (status: string) => void,
  ): Promise<ExtractionResult> {
    const targetId = scope?.messageId || scope?.sessionId; // 🔑 优先使用消息 ID 以关联 UI 指示器
    try {
      console.log(
        '[GraphExtractor] Starting extraction:',
        docId ? `Doc:${docId}` : `Session:${scope?.sessionId}`,
        'Length:',
        text.length,
      );

      // 上报状态到 RagStore
      const ragStore = useRagStore.getState();

      ragStore.updateProcessingState({
        kgStatus: 'extracting',
        kgProgress: 10,
        subStage: 'ENTITY_PARSE'
      }, targetId);

      // 1. Prepare Client
      onStatusUpdate?.('正在预处理源文本...');
      const client = this.getClient();
      const systemPrompt = this.getSystemPrompt();

      // 2. Call LLM
      onStatusUpdate?.('正在请求图谱模型 (发送中)...');
      const response = await client.chatCompletion([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: text },
      ]);

      const content = response.content;
      if (!content) {
        console.warn('[GraphExtractor] Empty response from LLM');
        useRagStore.getState().updateProcessingState({ kgStatus: 'error' }, targetId);
        return { nodes: [], edges: [], error: '模型返回空响应' };
      }

      // 3. Parse JSON
      onStatusUpdate?.('正在接收模型响应...');
      let jsonString = content.trim();

      // Better JSON extraction logic
      const jsonBlockRegex = /```json\s*([\s\S]*?)\s*```/i;
      const genericBlockRegex = /```\s*([\s\S]*?)\s*```/;

      const jsonMatch = jsonString.match(jsonBlockRegex);
      const genericMatch = jsonString.match(genericBlockRegex);

      if (jsonMatch) {
        jsonString = jsonMatch[1].trim();
      } else if (genericMatch) {
        // Attempt to parse generic block if it looks like JSON starts with {
        const potentialJson = genericMatch[1].trim();
        if (potentialJson.startsWith('{')) {
          jsonString = potentialJson;
        }
      } else {
        // Fallback: Try to find the outermost JSON object
        const firstBrace = jsonString.indexOf('{');
        const lastBrace = jsonString.lastIndexOf('}');
        if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
          jsonString = jsonString.substring(firstBrace, lastBrace + 1);
        }
      }

      let result: ExtractionResult;
      try {
        onStatusUpdate?.('正在解析图谱结构...');
        result = JSON.parse(jsonString);
      } catch (parseError) {
        useRagStore.getState().updateProcessingState({ kgStatus: 'error' }, targetId);
        console.warn('[GraphExtractor] JSON Parse Error:', parseError);
        console.log('Raw output preview:', content.slice(0, 200) + '...');
        return { nodes: [], edges: [], error: '模型输出非合法 JSON' };
      }

      useRagStore.getState().updateProcessingState({
        kgProgress: 60,
        subStage: 'GRAPH_WALK'
      }, targetId);

      if (!result.nodes || !result.edges) {
        console.warn('[GraphExtractor] Invalid JSON structure');
        useRagStore.getState().updateProcessingState({ kgStatus: 'error' }, targetId);
        return { nodes: [], edges: [], error: '模型输出缺少 nodes/edges 字段' };
      }

      // 4. Save to GraphStore
      console.log(
        `[GraphExtractor] Extracted ${result.nodes.length} nodes and ${result.edges.length} edges`,
      );

      // Save Nodes first
      onStatusUpdate?.('正在写入图数据库 (节点)...');
      const nameToIdMap = new Map<string, string>();

      for (const node of result.nodes) {
        try {
          const id = await graphStore.upsertNode(node.name, node.type, node.metadata, scope);
          nameToIdMap.set(node.name, id);
          // Yield to UI thread every node to prevent freeze
          if (result.nodes.length > 5) await new Promise((resolve) => setTimeout(resolve, 2));
        } catch (e) {
          console.error(`[GraphExtractor] Failed to save node ${node.name}`, e);
        }
      }

      // Save Edges
      onStatusUpdate?.('正在写入图数据库 (边)...');
      for (const edge of result.edges) {
        const sourceId = nameToIdMap.get(edge.source);
        const targetIdEdge = nameToIdMap.get(edge.target);

        if (sourceId && targetIdEdge) {
          try {
            await graphStore.createEdge(
              sourceId,
              targetIdEdge,
              edge.relation,
              docId,
              edge.weight || 1.0,
              scope,
            );
            // Yield to UI thread every edge
            if (result.edges.length > 5) await new Promise((resolve) => setTimeout(resolve, 2));
          } catch (e) {
            console.error(`[GraphExtractor] Failed to save edge ${edge.source}->${edge.target}`, e);
          }
        } else {
          console.warn(
            `[GraphExtractor] Skipping edge: missing node ID for ${edge.source} or ${edge.target}`,
          );
        }
      }

      useRagStore.getState().updateProcessingState({
        kgStatus: 'completed',
        kgProgress: 100
      }, targetId);

      // 10秒后重置状态
      setTimeout(() => {
        useRagStore.getState().updateProcessingState({ kgStatus: 'idle' }, targetId);
      }, 10000);

      return result;
    } catch (error) {
      useRagStore.getState().updateProcessingState({ kgStatus: 'error' }, targetId);
      const errMsg = error instanceof Error ? error.message : String(error);
      console.warn('[GraphExtractor] Extraction failed (Silenced):', errMsg);
      // 🔥 CRITICAL: 绝不在 RN 后台任务中抛出异常，否则触发红屏崩溃。
      // 通过 error 字段向调用方传递失败信号。
      return { nodes: [], edges: [], error: `图谱提取失败: ${errMsg.substring(0, 80)}` };
    }
  }
}

export const graphExtractor = new GraphExtractor();

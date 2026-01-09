import { useApiStore } from '../../store/api-store';
import { useSettingsStore } from '../../store/settings-store';
import { createLlmClient, ExtendedModelConfig } from '../llm/factory';
import { graphStore } from './graph-store';
import { DEFAULT_KG_PROMPT } from './defaults';

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

    // 2. Find Provider & Config
    for (const provider of apiState.providers) {
      if (!provider.enabled) continue;

      // Check if model exists in this provider (match by ID or UUID)
      const modelConfig = provider.models.find((m) => m.id === targetId || m.uuid === targetId);

      if (modelConfig) {
        const extendedConfig: ExtendedModelConfig = {
          ...modelConfig,
          provider: provider.type,
          apiKey: provider.apiKey,
          baseUrl: provider.baseUrl,
          vertexProject: provider.vertexProject,
          vertexLocation: provider.vertexLocation,
          vertexKeyJson: provider.vertexKeyJson,
        };
        return createLlmClient(extendedConfig);
      }
    }

    throw new Error(`Model '${targetId}' not found in any enabled provider`);
  }

  private getSystemPrompt(): string {
    const settingsState = useSettingsStore.getState();
    const customPrompt = settingsState.globalRagConfig.kgExtractionPrompt;
    const entityTypes = settingsState.globalRagConfig.kgEntityTypes || [
      'Concept',
      'Person',
      'Organization',
      'Location',
      'Event',
      'Product',
    ];

    // If custom prompt is present, use it (injecting entity types if placeholder exists)
    if (customPrompt && customPrompt.trim().length > 0) {
      if (customPrompt.includes('{entityTypes}')) {
        return customPrompt.replace('{entityTypes}', entityTypes.join(', '));
      } else {
        // Fallback: Append expectation if user removed placeholder
        return `${customPrompt}\n\nTarget Entity Types: ${entityTypes.join(', ')}\nEnsure output is valid JSON.`;
      }
    }

    // Use default prompt from defaults.ts
    // 确保默认 Prompt 也能正确替换
    if (DEFAULT_KG_PROMPT && DEFAULT_KG_PROMPT.includes('{entityTypes}')) {
      return DEFAULT_KG_PROMPT.replace('{entityTypes}', entityTypes.join(', '));
    }

    return DEFAULT_KG_PROMPT;
  }

  /**
   * Extract entities and relationships from a text chunk
   */
  async extractAndSave(
    text: string,
    docId?: string,
    scope?: { sessionId?: string; agentId?: string },
  ): Promise<ExtractionResult> {
    try {
      console.log(
        '[GraphExtractor] Starting extraction:',
        docId ? `Doc:${docId}` : `Session:${scope?.sessionId}`,
        'Length:',
        text.length,
      );

      // 1. Prepare Client
      const client = this.getClient();
      const systemPrompt = this.getSystemPrompt();

      // 2. Call LLM
      const response = await client.chatCompletion([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: text },
      ]);

      const content = response.content;
      if (!content) {
        console.warn('[GraphExtractor] Empty response from LLM');
        return { nodes: [], edges: [] };
      }

      // 3. Parse JSON
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
        result = JSON.parse(jsonString);
      } catch (parseError) {
        // Use warn instead of error to prevent RedBox
        console.warn('[GraphExtractor] JSON Parse Error:', parseError);
        console.log('Raw output preview:', content.slice(0, 200) + '...');
        return { nodes: [], edges: [] };
      }

      if (!result.nodes || !result.edges) {
        console.warn('[GraphExtractor] Invalid JSON structure');
        return { nodes: [], edges: [] };
      }

      // 4. Save to GraphStore
      console.log(
        `[GraphExtractor] Extracted ${result.nodes.length} nodes and ${result.edges.length} edges`,
      );

      // Save Nodes first
      const nameToIdMap = new Map<string, string>();

      for (const node of result.nodes) {
        try {
          const id = await graphStore.upsertNode(node.name, node.type, node.metadata, scope);
          nameToIdMap.set(node.name, id);
        } catch (e) {
          console.error(`[GraphExtractor] Failed to save node ${node.name}`, e);
        }
      }

      // Save Edges
      for (const edge of result.edges) {
        const sourceId = nameToIdMap.get(edge.source);
        const targetId = nameToIdMap.get(edge.target);

        if (sourceId && targetId) {
          try {
            await graphStore.createEdge(
              sourceId,
              targetId,
              edge.relation,
              docId,
              edge.weight || 1.0,
              scope,
            );
          } catch (e) {
            console.error(`[GraphExtractor] Failed to save edge ${edge.source}->${edge.target}`, e);
          }
        } else {
          console.warn(
            `[GraphExtractor] Skipping edge: missing node ID for ${edge.source} or ${edge.target}`,
          );
        }
      }

      return result;
    } catch (error) {
      console.error('[GraphExtractor] Extraction failed:', error);
      throw error;
    }
  }
}

export const graphExtractor = new GraphExtractor();

import { getPrompts, getPromptLang } from '../llm/prompts/i18n';

/**
 * 获取当前语言的 KG 默认 Prompt
 * 🌐 I18N (2026-02-11): 替代原硬编码英文默认值
 */
export function getDefaultKgPrompt(): string {
  return getPrompts(getPromptLang()).rag.kgDefaultPrompt;
}

/**
 * 保留原始英文版本用于向后兼容（已有自定义 Prompt 的用户不受影响）
 * @deprecated 请使用 getDefaultKgPrompt() 获取本地化版本
 */
export const DEFAULT_KG_PROMPT = `
You are an expert Knowledge Graph extractor.
Extract meaningful entities and relationships from the user provided text.

Target Entity Types: {entityTypes}

Return a valid JSON object with the following structure:
{
  "nodes": [
    { "name": "Exact Name", "type": "EntityType", "metadata": { "description": "short desc" } }
  ],
  "edges": [
    { "source": "SourceNodeName", "target": "TargetNodeName", "relation": "relationship_verb", "weight": 1.0 }
  ]
}

Rules:
1. "name" must be the unique identifier.
2. "source" and "target" in edges must match a "name" in nodes.
3. Keep descriptions concise.
4. "weight" is 0.0 to 1.0, indicating confidence or importance.
5. JSON ONLY. No markdown formatted blocks.
`;

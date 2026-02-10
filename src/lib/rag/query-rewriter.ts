import { LlmClient } from '../llm/types';
import { getPrompts, getPromptLang } from '../llm/prompts/i18n';

export type RewriteStrategy = 'hyde' | 'multi-query' | 'expansion';

/**
 * 查询重写器
 * 负责根据不同的策略生成查询变体，以提高检索召回率。
 * 🌐 I18N (2026-02-11): 所有 Prompt 通过 i18n 字典动态选择语言。
 */
export class QueryRewriter {
  constructor(
    private client: LlmClient,
    private strategy: RewriteStrategy = 'multi-query',
    private modelId?: string,
  ) { }

  /**
   * 执行查询重写
   * @param query 原始查询
   * @param count 生成变体数量
   * @returns 重写后的查询列表与Token消耗
   */
  async rewrite(
    query: string,
    count: number = 3,
  ): Promise<{ variants: string[]; usage?: { input: number; output: number; total: number } }> {
    // 始终包含原始查询
    const queries = new Set<string>([query]);
    let totalUsage: { input: number; output: number; total: number } | undefined;

    try {
      // 🌐 获取当前语言的 Prompt
      const rewriterPrompts = getPrompts(getPromptLang()).rag.queryRewriter;
      let prompt = '';

      switch (this.strategy) {
        case 'hyde':
          // Hypothetical Document Embeddings: 生成假设性回复
          prompt = rewriterPrompts.hyde(query);
          break;
        case 'multi-query':
          // Multi-Query: 生成不同角度的查询
          prompt = rewriterPrompts.multiQuery(query, count);
          break;
        case 'expansion':
          // Expansion: 关键词扩展
          prompt = rewriterPrompts.expansion(query);
          break;
        default:
          return { variants: [query] };
      }

      // 调用 LLM 生成 (使用统一的非流式接口)
      const result = await this.client.chatCompletion([{ role: 'user', content: prompt }], {
        temperature: 0.7,
      });

      const content = result.content;
      totalUsage = result.usage;

      // 解析结果
      if (content) {
        if (this.strategy === 'multi-query') {
          // 按行分割
          content.split('\n').forEach((line) => {
            const clean = line.replace(/^\d+[\.、\)]\s*/, '').trim();
            if (clean) queries.add(clean);
          });
        } else if (this.strategy === 'expansion') {
          // 将扩展后的关键词串作为一个新的查询变体
          queries.add(`${query} ${content}`);
        } else if (this.strategy === 'hyde') {
          // HyDE 生成的是文档片段，用于 embedding
          queries.add(content.trim());
        }
      }
    } catch (error) {
      console.warn('[QueryRewriter] Rewrite failed:', error);
    }

    return {
      variants: Array.from(queries).slice(0, count + 1),
      usage: totalUsage,
    };
  }
}

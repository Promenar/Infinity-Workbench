import { LlmClient } from '../llm/types';

export type RewriteStrategy = 'hyde' | 'multi-query' | 'expansion';

/**
 * 查询重写器
 * 负责根据不同的策略生成查询变体，以提高检索召回率。
 */
export class QueryRewriter {
    constructor(
        private client: LlmClient,
        private strategy: RewriteStrategy = 'multi-query',
        private modelId?: string
    ) { }

    /**
     * 执行查询重写
     * @param query 原始查询
     * @param count 生成变体数量
     * @returns 重写后的查询列表（包含原始查询）
     */
    async rewrite(query: string, count: number = 3): Promise<string[]> {
        // 始终包含原始查询
        const queries = new Set<string>([query]);

        try {
            let prompt = '';

            switch (this.strategy) {
                case 'hyde':
                    // Hypothetical Document Embeddings: 生成假设性回复
                    prompt = `请为以下问题生成一个假设性的、可能的回答段落。不需要通过网络搜索，只需基于常识生成一个相关的回答用于检索匹配。\n\n问题: ${query}\n\n回答:`;
                    break;
                case 'multi-query':
                    // Multi-Query: 生成不同角度的查询
                    prompt = `你是一个AI搜索助手。请生成 ${count} 个这一原始问题的不同版本，通过从不同角度提问来帮助从向量数据库中检索相关文档。只需提供问题列表，每行一个，不要包含任何编号或通过其他文字。\n\n原始问题: ${query}`;
                    break;
                case 'expansion':
                    // Expansion: 关键词扩展
                    prompt = `请提取并扩展以下查询中的关键概念和关键词，包括同义词和相关术语，以便进行更广泛的搜索。只需用逗号分隔列出关键词，不要包含其他文字。\n\n查询: ${query}`;
                    break;
                default:
                    return [query];
            }

            // 调用 LLM 生成 (使用统一的非流式接口)
            const result = await this.client.chatCompletion(
                [{ role: 'user', content: prompt }],
                { temperature: 0.7 }
            );

            // 解析结果
            if (result) {
                if (this.strategy === 'multi-query') {
                    // 按行分割
                    result.split('\n').forEach(line => {
                        const clean = line.replace(/^\d+[\.\、\)]\s*/, '').trim();
                        if (clean) queries.add(clean);
                    });
                } else if (this.strategy === 'expansion') {
                    // 逗号分割并作为独立查询？或者合并为一个长查询？
                    // 通常 Expansion 是为了增加关键词，这里我们将其视为单个增强查询，或者多个关键词查询
                    // 简单起见，我们将扩展后的关键词串作为一个新的查询变体
                    queries.add(`${query} ${result}`);
                } else if (this.strategy === 'hyde') {
                    // HyDE 生成的是文档片段，用于 embedding
                    // 我们可以直接将其作为查询（依靠 embedding 相似度）
                    queries.add(result.trim());
                }
            }

        } catch (error) {
            console.warn('[QueryRewriter] Rewrite failed:', error);
        }

        return Array.from(queries).slice(0, count + 1); // 限制总数
    }
}

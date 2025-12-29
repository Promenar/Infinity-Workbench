import { Message } from '../../../types/chat';
import { createLlmClient } from '../../../lib/llm/factory';
import { estimateTokens } from './token-counter';
import { db } from '../../../lib/db';
import { vectorStore } from '../../../lib/rag/vector-store';
import { useApiStore } from '../../../store/api-store';
import { EmbeddingClient } from '../../../lib/rag/embedding';

export interface ContextConfig {
    maxMessages: number; // Max messages in active window (e.g., 20)
    summarizeThreshold: number; // Trigger summary when unsummarized messages exceed this (e.g., 30)
}

export interface ContextSummary {
    id: string;
    sessionId: string;
    startMessageId: string;
    endMessageId: string;
    summaryContent: string;
    createdAt: number;
    tokenUsage?: number;
}

export class ContextManager {
    static async checkAndSummarize(sessionId: string, messages: Message[], config: ContextConfig = { maxMessages: 20, summarizeThreshold: 30 }): Promise<void> {
        // 1. Identify unsummarized messages
        try {
            // Fetch existing summaries to determine the "summarized frontier"
            const result = await db.execute(
                'SELECT end_message_id FROM context_summaries WHERE session_id = ? ORDER BY created_at DESC LIMIT 1',
                [sessionId]
            );

            let lastSummarizedMsgId = null;
            if (result.rows) {
                // Safe access for op-sqlite
                const row = (result.rows as any).item ? (result.rows as any).item(0) : (result.rows as any)._array?.[0];
                if (row) lastSummarizedMsgId = row.end_message_id;
            }

            // Filter out system messages and find the index of the last summarized message
            const contentMessages = messages.filter(m => m.role !== 'system');

            let startIndex = 0;
            if (lastSummarizedMsgId) {
                const idx = contentMessages.findIndex(m => m.id === lastSummarizedMsgId);
                if (idx !== -1) {
                    startIndex = idx + 1;
                }
            }

            // Calculate how many unsummarized messages we have pending
            // We want to keep the last 'maxMessages' (e.g., 20) as active context
            // So we summarize everything before `total - maxMessages`
            const activeWindowSize = config.maxMessages;
            const pendingCount = contentMessages.length - startIndex;

            // e.g. 50 messages total, start=0. pending=50. max=20.
            // We can summarize 50 - 20 = 30 messages.
            const messagesToSummarizeCount = pendingCount - activeWindowSize;

            if (messagesToSummarizeCount < 10) {
                // Not enough messages to bother summarizing yet (batch it)
                return;
            }

            // Select the chunk to summarize
            const chunk = contentMessages.slice(startIndex, startIndex + messagesToSummarizeCount);
            if (chunk.length === 0) return;

            console.log(`[ContextManager] Summarizing ${chunk.length} messages for session ${sessionId}...`);

            // 2. Generate Summary using LLM
            const summary = await this.generateSummary(chunk);
            if (!summary) return;

            // 3. Store in DB
            const id = `summary_${Date.now()}`;
            await db.execute(
                `INSERT INTO context_summaries (id, session_id, start_message_id, end_message_id, summary_content, created_at, token_usage) VALUES (?, ?, ?, ?, ?, ?, ?)`,
                [
                    id,
                    sessionId,
                    chunk[0].id,
                    chunk[chunk.length - 1].id,
                    summary,
                    Date.now(),
                    0 // TODO: track actual usage
                ]
            );

            // 4. Vectorize Summary for RAG
            await this.vectorizeSummary(sessionId, summary);

            console.log('[ContextManager] Summary generated and stored successfully.');

        } catch (e) {
            console.error('[ContextManager] Failed to summarize:', e);
        }
    }

    private static async generateSummary(messages: Message[]): Promise<string | null> {
        const apiStore = useApiStore.getState();
        // Use a lightweight model if possible, or the current active provider
        const provider = apiStore.providers.find(p => p.enabled);
        if (!provider) {
            throw new Error('No enabled AI provider found.');
        }

        const transcript = messages.map(m => `${m.role}: ${m.content}`).join('\n');
        const prompt = `Summarize the following conversation segment concisely, capturing key facts, decisions, and context. Do not lose important details.\n\n${transcript}`;

        try {
            // Find a suitable model: Prefer 'chat' capable models
            const summaryModel = provider.models.find(m => m.enabled && (!m.type || m.type === 'chat')) || provider.models[0];

            // Critical Fix: Use `model.id` (API string) not `model.uuid` (Internal React Key)
            const summaryModelName = summaryModel?.id || 'gpt-3.5-turbo';

            const config = {
                ...provider,
                provider: provider.type,
                id: summaryModelName, // CORRECT: override provider.id with model.id for the factory
                modelName: summaryModelName,
                apiKey: provider.apiKey,
                baseUrl: provider.baseUrl,
            };

            const client = createLlmClient(config as any);
            let fullResponse = '';

            await new Promise<void>((resolve, reject) => {
                client.streamChat(
                    [{ role: 'user', content: prompt }],
                    (chunk) => {
                        if (chunk.content) fullResponse += chunk.content;
                    },
                    (err) => reject(err)
                ).then(() => resolve()).catch(reject);
            });

            return fullResponse;
        } catch (e) {
            console.error('[ContextManager] LLM call failed:', e);
            throw e; // Re-throw to let UI handle it
        }
    }

    private static async vectorizeSummary(sessionId: string, summary: string) {
        const apiStore = useApiStore.getState();
        let provider = apiStore.providers.find(p => p.enabled && p.type === 'openai' && p.models.some(m => m.enabled && m.type === 'embedding'));

        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (
                p.type === 'siliconflow' || p.type === 'deepseek' || p.type === 'moonshot' || p.type === 'zhipu'
            ) && p.models.some(m => m.enabled && m.type === 'embedding'));
        }

        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'gemini' || p.type === 'google'));
        }

        if (!provider) return;

        const embeddingModelConfig = provider.models.find(m => m.enabled && m.type === 'embedding');
        const modelId = embeddingModelConfig?.id || (provider.type === 'openai' ? 'text-embedding-3-small' : undefined);

        try {
            const embeddingClient = new EmbeddingClient(provider, modelId);
            const embedding = await embeddingClient.embedQuery(summary);

            await vectorStore.addVectors([{
                sessionId,
                content: summary,
                embedding: embedding,
                metadata: { type: 'summary' }
            }]);
        } catch (e) {
            console.error('[ContextManager] Vectorization failed:', e);
        }
    }

    static async deleteSummary(id: string): Promise<void> {
        try {
            await db.execute('DELETE FROM context_summaries WHERE id = ?', [id]);
            // Attempt to remove related vector data (rough cleanup)
            await db.execute("DELETE FROM vectors WHERE metadata LIKE ?", [`%"type":"summary"%${id}%`]);
            console.log(`[ContextManager] Deleted summary ${id}`);
        } catch (e) {
            console.error('[ContextManager] Failed to delete summary:', e);
            throw e;
        }
    }

    static async getRelevantContext(sessionId: string): Promise<string[]> {
        // Retrieve relevant summaries
        try {
            const result = await db.execute(
                'SELECT summary_content FROM context_summaries WHERE session_id = ? ORDER BY created_at DESC LIMIT 1',
                [sessionId]
            );

            if (result.rows) {
                // Safe access
                const row = (result.rows as any).item ? (result.rows as any).item(0) : (result.rows as any)._array?.[0];
                if (row) return [row.summary_content];
            }
        } catch (e) {
            console.error('[ContextManager] Failed to get context:', e);
        }
        return [];
    }
}

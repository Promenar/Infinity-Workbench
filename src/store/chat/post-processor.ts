/**
 * 后处理模块
 * 负责 RAG 归档、KG 提取、上下文摘要、统计更新
 * Phase 4b: 从 chat-store.ts generateMessage 中提取
 */

import { db } from '../../lib/db';
import { useRagStore } from '../rag-store';
import { useSettingsStore } from '../settings-store';
import { MemoryManager } from '../../lib/rag/memory-manager';
import { graphExtractor } from '../../lib/rag/graph-extractor';
import { ContextManager } from '../../features/chat/utils/ContextManager';
import { estimateTokens } from '../../features/chat/utils/token-counter';
import type { Message, Session } from '../../types/chat';

export interface PostProcessorParams {
    sessionId: string;
    assistantMsgId: string;
    userMsgId: string;
    userContent: string;
    assistantContent: string;
    agent: any;
    session: Session;
    ragEnabled: boolean;
    ragUsage?: { ragSystem: number; isEstimated: boolean };
    accumulatedUsage?: { input: number; output: number; total: number };
    totalContextTokens: number;
    modelId: string;
    getSession: (id: string) => Session | undefined;
    updateSession: (id: string, updates: Partial<Session>) => void;
    updateMessageProgress: (sessionId: string, messageId: string, progress: any) => void;
    setVectorizationStatus: (sessionId: string, messageIds: string[], status: 'processing' | 'success' | 'error') => void;
    setKGExtractionStatus: (sessionId: string, isExtracting: boolean) => void;
    updateSessionTitle: (sessionId: string, title: string) => void;
}

/**
 * 执行 RAG 归档
 */
export async function archiveToRag(params: PostProcessorParams): Promise<void> {
    const {
        sessionId, assistantMsgId, userMsgId,
        userContent, assistantContent,
        setVectorizationStatus
    } = params;

    setVectorizationStatus(sessionId, [userMsgId, assistantMsgId], 'processing');

    try {
        const archiveStartTime = Date.now();
        const { updateProcessingState } = useRagStore.getState();

        updateProcessingState({
            sessionId,
            status: 'chunking',
            subStage: 'EMBEDDING',
            progress: 50,
            pulseActive: true,
            startTime: archiveStartTime,
            chunks: []
        }, assistantMsgId);

        await new Promise((resolve) => setTimeout(resolve, 0));
        await MemoryManager.addTurnToMemory(sessionId, userContent, assistantContent, userMsgId, assistantMsgId);

        const elapsed = Date.now() - archiveStartTime;
        if (elapsed < 800) await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));

        updateProcessingState({
            sessionId,
            status: 'archived',
            subStage: undefined,
            progress: 100,
            pulseActive: false,
            chunks: []
        }, assistantMsgId);

        setVectorizationStatus(sessionId, [userMsgId, assistantMsgId], 'success');
    } catch (e) {
        console.error('[PostProcessor] Archive failed:', e);
        setVectorizationStatus(sessionId, [userMsgId, assistantMsgId], 'error');
        useRagStore.getState().updateProcessingState({ sessionId, status: 'error' }, assistantMsgId);
    }
}

/**
 * 执行 KG 提取 (🔑 改为批量累积模式)
 * 不再直接抽取，而是累积到 rag-store，达到阈值后由统一队列处理
 */
export async function extractKnowledgeGraph(params: PostProcessorParams): Promise<void> {
    const {
        sessionId, assistantMsgId,
        userContent, assistantContent,
        agent, session,
    } = params;

    if (!assistantContent.trim()) return;

    // 延迟执行，确保 UI 稳定
    setTimeout(async () => {
        try {
            const globalConfig = useSettingsStore.getState().globalRagConfig;
            const isSuperAssistant = sessionId === 'super_assistant';
            const sessionKgOption = session.ragOptions?.enableKnowledgeGraph;
            const isKgEnabled = sessionKgOption !== undefined ? sessionKgOption : globalConfig.enableKnowledgeGraph;

            if (!isKgEnabled) return;

            const costStrategy = agent?.ragConfig?.costStrategy || globalConfig.costStrategy || 'summary-first';
            if (costStrategy === 'on-demand' && !isSuperAssistant) return;

            // 🔑 改为累积模式：不再直接抽取，而是入列统一队列
            const combinedText = `User: ${userContent}\nAssistant: ${assistantContent}`;
            useRagStore.getState().accumulateForKG(sessionId, combinedText, assistantMsgId);

            console.log(`[PostProcessor] KG content accumulated for session ${sessionId}`);
        } catch (e) {
            console.warn('[PostProcessor] KG accumulation failed:', e);
        }
    }, 500);
}

/**
 * 执行上下文摘要检查
 */
export async function checkContextSummarization(params: PostProcessorParams): Promise<void> {
    const { sessionId, assistantMsgId, agent, getSession } = params;

    try {
        const currentMessages = getSession(sessionId)?.messages || [];
        const contentMessages = currentMessages.filter((m: Message) => m.role !== 'system');

        const summariesResult = await db.execute(
            'SELECT start_message_id, end_message_id FROM context_summaries WHERE session_id = ?',
            [sessionId]
        );

        const summarizedMessageIds = new Set<string>();
        if (summariesResult.rows) {
            const rows = (summariesResult.rows as any)._array || (summariesResult.rows as any) || [];
            for (const row of rows) {
                if (row.start_message_id && row.end_message_id) {
                    const startIdx = contentMessages.findIndex((m: Message) => m.id === row.start_message_id);
                    const endIdx = contentMessages.findIndex((m: Message) => m.id === row.end_message_id);
                    if (startIdx !== -1 && endIdx !== -1) {
                        for (let i = startIdx; i <= endIdx; i++) summarizedMessageIds.add(contentMessages[i].id);
                    }
                }
            }
        }

        const activeWindowSize = agent.ragConfig?.contextWindow || 10;
        const newMessagesCount = contentMessages.filter((m: Message) => !summarizedMessageIds.has(m.id)).length;
        const summaryThreshold = agent.ragConfig?.summaryThreshold || 20;

        if (newMessagesCount > activeWindowSize + summaryThreshold) {
            const summaryStartTime = Date.now();
            const { updateProcessingState } = useRagStore.getState();
            updateProcessingState({ sessionId, status: 'summarizing', startTime: summaryStartTime }, assistantMsgId);
            await new Promise((resolve) => setTimeout(resolve, 0));

            await ContextManager.checkAndSummarize(sessionId, currentMessages, agent);

            const elapsed = Date.now() - summaryStartTime;
            if (elapsed < 800) await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));

            updateProcessingState({ sessionId, status: 'summarized', summary: '' }, assistantMsgId);
        }
    } catch (e) {
        console.error('[PostProcessor] Summarization failed', e);
    }
}

/**
 * 更新统计信息
 */
export function updateStats(params: PostProcessorParams): void {
    const {
        sessionId, accumulatedUsage, ragUsage, totalContextTokens,
        assistantContent, modelId, session,
        updateSession, agent, userContent,
        updateSessionTitle
    } = params;

    const finalUsage = accumulatedUsage || { input: totalContextTokens, output: estimateTokens(assistantContent), total: 0 };
    finalUsage.total = finalUsage.input + finalUsage.output;

    const billingUsage = {
        chatInput: { count: finalUsage.input, isEstimated: !accumulatedUsage },
        chatOutput: { count: finalUsage.output, isEstimated: !accumulatedUsage },
        ragSystem: ragUsage ? { count: ragUsage.ragSystem, isEstimated: ragUsage.isEstimated } : { count: 0, isEstimated: false },
        total: finalUsage.total + (ragUsage?.ragSystem || 0)
    };

    updateSession(sessionId, { stats: { totalTokens: billingUsage.total, billing: billingUsage } });

    // 异步更新 Token 统计
    import('../token-stats-store').then(({ useTokenStatsStore }) => {
        useTokenStatsStore.getState().trackUsage({ modelId, usage: billingUsage });
    }).catch(() => { });

    // 自动生成标题
    if (session.messages.length <= 1 || session.title === agent.name || session.title === 'New Conversation') {
        updateSessionTitle(sessionId, userContent.substring(0, 30) + (userContent.length > 30 ? '...' : ''));
    }
}

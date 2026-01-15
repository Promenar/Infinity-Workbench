/**
 * 上下文构建模块
 * 负责 RAG 检索、Web 搜索、系统提示词注入
 * Phase 4b: 从 chat-store.ts generateMessage 中提取
 */

import { useApiStore } from '../api-store';
import { useSettingsStore } from '../settings-store';
import { useRagStore } from '../rag-store';
import { performWebSearch } from '../../features/chat/utils/web-search';
import { MemoryManager } from '../../lib/rag/memory-manager';
import { skillRegistry } from '../../lib/skills/registry';
import type { Session, Message, RagReference, GeneratedImageData } from '../../types/chat';

export interface ContextBuilderParams {
    sessionId: string;
    content: string;
    images?: GeneratedImageData[];
    assistantMsgId: string;
    session: Session;
    agent: any;
    provider: any;
    webSearchEnabled?: boolean;
    ragOptions?: {
        enableMemory?: boolean;
        enableDocs?: boolean;
        activeDocIds?: string[];
        activeFolderIds?: string[];
        isGlobal?: boolean;
    };
    onRagProgress?: (stage: string, percentage: number, subStage?: string, networkStats?: any) => void;
    updateMessageContent: (
        sessionId: string,
        messageId: string,
        content: string,
        usage?: any,
        reasoning?: string,
        citations?: any[],
        ragReferences?: RagReference[],
        ragReferencesLoading?: boolean,
        ragMetadata?: any
    ) => void;
}

export interface ContextBuilderResult {
    searchContext: string;
    ragContext: string;
    citations: { title: string; url: string; source?: string }[];
    ragReferences: RagReference[];
    ragUsage?: { ragSystem: number; isEstimated: boolean };
    finalSystemPrompt: string;
}

/**
 * 执行 Web 搜索（非原生搜索模型）
 */
export async function performClientSideSearch(
    query: string,
    providerType: string
): Promise<{ context: string; sources: { title: string; url: string; source?: string }[] }> {
    // 原生搜索模型（Gemini/Vertex）跳过客户端搜索
    const isNativeSearch = providerType === 'gemini' || providerType === 'google';
    if (isNativeSearch) {
        return { context: '', sources: [] };
    }

    try {
        const searchConfig = useApiStore.getState().googleSearchConfig;
        const { context, sources } = await performWebSearch(
            query,
            searchConfig?.apiKey,
            searchConfig?.cx
        );
        console.log('[ContextBuilder] Client-side search completed', {
            sources: sources.length,
            hasContext: !!context
        });
        return { context, sources };
    } catch (err) {
        console.error('[ContextBuilder] Client-side search failed', err);
        return { context: '', sources: [] };
    }
}

/**
 * 执行 RAG 检索
 */
export async function performRagRetrieval(
    params: ContextBuilderParams
): Promise<{
    context: string;
    references: RagReference[];
    usage?: { ragSystem: number; isEstimated: boolean };
}> {
    const { sessionId, content, session, agent, assistantMsgId, ragOptions, onRagProgress, updateMessageContent } = params;

    // 合并配置：session 持久化配置 + 临时覆盖
    const sessionRagOptions = session.ragOptions || {};
    const tempRagOptions = ragOptions || {};

    const finalRagOptions = {
        enableMemory: tempRagOptions.enableMemory ?? sessionRagOptions.enableMemory ?? true,
        enableDocs: tempRagOptions.enableDocs ?? sessionRagOptions.enableDocs ?? false,
        activeDocIds: tempRagOptions.activeDocIds ?? sessionRagOptions.activeDocIds ?? [],
        activeFolderIds: tempRagOptions.activeFolderIds ?? sessionRagOptions.activeFolderIds ?? [],
        isGlobal: tempRagOptions.isGlobal ?? sessionRagOptions.isGlobal ?? false,
    };

    const isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs;
    if (!isRagEnabled) {
        console.log('[ContextBuilder] RAG已禁用，跳过检索');
        return { context: '', references: [] };
    }

    try {
        console.log('[ContextBuilder] 开始RAG检索:', {
            sessionId,
            enableMemory: finalRagOptions.enableMemory,
            enableDocs: finalRagOptions.enableDocs,
            docCount: finalRagOptions.activeDocIds?.length || 0,
        });

        // 预触发：意图分析子阶段
        useRagStore.getState().updateProcessingState({
            sessionId,
            status: 'retrieving',
            stage: 'rewriting',
            subStage: 'INTENT',
            progress: 2
        }, assistantMsgId);

        // Super Assistant 强制全局搜索
        const isSuperAssistant = sessionId === 'super_assistant';
        const effectiveRagOptions = {
            ...finalRagOptions,
            isGlobal: isSuperAssistant ? true : finalRagOptions.isGlobal,
            enableDocs: isSuperAssistant ? true : finalRagOptions.enableDocs,
            enableMemory: isSuperAssistant ? true : finalRagOptions.enableMemory,
            ragConfig: agent.ragConfig,
            onProgress: onRagProgress,
        };

        const {
            context: retrievedContext,
            references,
            metadata,
            billingUsage,
        } = await MemoryManager.retrieveContext(content, sessionId, effectiveRagOptions);

        console.log('[ContextBuilder] RAG检索完成:', {
            contextLength: retrievedContext.length,
            referencesCount: references.length,
        });

        // 更新消息内容（带 RAG 引用）
        updateMessageContent(
            sessionId,
            assistantMsgId,
            '',
            undefined,
            undefined,
            undefined,
            references,
            false,
            metadata
        );

        // 同步处理状态
        useRagStore.getState().updateProcessingState({
            status: 'retrieved',
            chunks: references.map(r => r.content || '')
        }, assistantMsgId);

        return {
            context: retrievedContext,
            references,
            usage: billingUsage
        };
    } catch (e) {
        console.error('[ContextBuilder] RAG Retrieval failed:', e);
        updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, [], false);
        useRagStore.getState().updateProcessingState({ status: 'idle' }, assistantMsgId);
        return { context: '', references: [] };
    }
}

/**
 * 构建系统提示词（含工具描述和任务状态注入）
 */
export function buildSystemPrompt(
    agent: any,
    session: Session,
    ragContext: string,
    searchContext: string
): string {
    let finalSystemPrompt = agent.systemPrompt + (session.customPrompt ? `\n\n${session.customPrompt}` : '');

    // 注入工具描述
    const availableSkills = skillRegistry.getEnabledSkills();
    if (availableSkills.length > 0) {
        const toolsDesc = availableSkills.map(s => {
            let argsDesc = 'No arguments';
            if (s.schema && (s.schema as any).shape) {
                argsDesc = Object.entries((s.schema as any).shape).map(([key, val]: [string, any]) => {
                    const isOptional = val._def?.typeName === 'ZodOptional' || (val.isOptional && typeof val.isOptional === 'function' && val.isOptional());
                    const desc = val.description || (val._def?.description) || '';
                    let extraGuidance = '';
                    if (s.id === 'query_vector_db' && key === 'scope') {
                        extraGuidance = ' (CRITICAL: Use "global" ONLY when user explicitly asks for all documents)';
                    }
                    return `  - ${key}${isOptional ? ' (optional)' : ' (REQUIRED)'}: ${desc}${extraGuidance}`;
                }).join('\n');
            }
            return `### ${s.name} (ID: ${s.id})\n${s.description}\nArguments:\n${argsDesc}`;
        }).join('\n\n');

        const toolInstruction = `\n\n[AVAILABLE TOOLS]
You have access to the following skills:

${toolsDesc}

[PLANNING & TASK MANAGEMENT]
If the user's request is complex, multi-step, or requires maintaining state, you MUST use the \`manage_task\` tool.
- CREATE a plan BEFORE execution: \`manage_task({ action: 'create', title: '...', steps: [...] })\`
- UPDATE steps as you finish them: \`manage_task({ action: 'update', steps: [{ id: '...', status: 'completed' }] })\`
- COMPLETE the task when fully done: \`manage_task({ action: 'complete' })\`

[EXECUTION RULES]
1. NATIVE TOOL CALLS ONLY. Use the JSON schema provided.
2. 🚫 NO PARAMETER WRAPPING: DO NOT wrap arguments in a "parameters" key.
3. 🚫 NO INTRODUCTORY TEXT: Response must ONLY contain tool calls.
4. PROVIDE ALL REQUIRED PARAMETERS.
5. Trigger tools immediately.`;

        finalSystemPrompt += toolInstruction;

        // 任务状态注入
        if (session.activeTask) {
            const task = session.activeTask;
            const formattedSteps = task.steps.map((s, idx) =>
                `${idx + 1}. [${s.status.toUpperCase()}] ${s.title}${s.description ? ` (${s.description})` : ''}`
            ).join('\n');

            const taskContext = `\n\n[CURRENT TASK STATUS]
Title: ${task.title}
Status: ${task.status}
Progress: ${task.progress}%
Steps:
${formattedSteps}
 
IMPORTANT: You are currently working on this task. Use 'manage_task' to update the status of steps as you complete them.`;
            finalSystemPrompt += taskContext;
        }
    }

    // 注入 RAG 上下文
    if (ragContext) {
        finalSystemPrompt += `\n\n${ragContext}`;
    }

    return finalSystemPrompt;
}

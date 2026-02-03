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
import { inferModelFamily, getModelSpecificEnhancements } from '../../lib/llm/model-prompts'; // 🆕 模型特定提示词
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
        const searchConfig = useApiStore.getState().searchConfig;
        const { context, sources } = await performWebSearch(
            query,
            searchConfig
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
    searchContext: string,
    provider: any, // 🆕 通过参数注入
    isNativeWebSearchProvider: boolean, // 🆕 通过参数注入
    availableSkills?: any[] // 🆕 允许外部传入已筛选的工具列表
): string {
    // 🔑 Phase 1: Context Anchoring - 优先状态注入
    // 在系统提示词的最顶部建立“锚点”，防止模型迷失
    let prioritizedState = '';

    // 🕒 Time Injection (Default: ON)
    const enableTimeInjection = session.options?.enableTimeInjection ?? true;
    if (enableTimeInjection) {
        const now = new Date();
        const timeString = now.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            weekday: 'long',
            hour12: false
        });

        prioritizedState += `[SYSTEM METADATA]\nCurrent System Time: ${timeString}\n\n`;
    }

    if (session.activeTask && session.activeTask.status === 'in-progress') {
        const task = session.activeTask;
        // 🧠 Logic Update: Since 'in-progress' status is removed for steps, 
        // the current step is the FIRST 'pending' step.
        const currentStepIndex = task.steps.findIndex(s => s.status === 'pending');
        const currentStep = task.steps[currentStepIndex];
        const totalSteps = task.steps.length;

        // 分析 Last Action (简单启发式：找最近的 tool 或者是 assistant 的 tool_calls)
        let lastAction = 'None';
        const msgs = session.messages;
        if (msgs.length > 0) {
            for (let i = msgs.length - 1; i >= 0; i--) {
                const m = msgs[i];
                if (m.role === 'tool') {
                    lastAction = `✅ Tool Execution ('${m.name}') COMPLETED -> Result is in History`;
                    break;
                }
                if (m.role === 'assistant' && m.tool_calls && m.tool_calls.length > 0) {
                    const names = m.tool_calls.map((t: any) => t.name).join(', ');
                    lastAction = `⏳ Tool Request ('${names}') -> Waiting for Output`;
                    break;
                }
                if (m.role === 'user') {
                    lastAction = `👤 User Input`;
                    break;
                }
            }
        }

        prioritizedState += `### [PRIORITIZED STATE - READ THIS FIRST]
- **Current Task**: "${task.title}" (Step ${currentStepIndex !== -1 ? currentStepIndex + 1 : 'All Completed'}/${totalSteps}: ${currentStep ? 'Pending' : 'Done'})
- **Last Action**: ${lastAction}
- **Immediate Goal**: ${currentStep ? (currentStep.description || currentStep.title) : 'Review and Complete Task'}
\n**CRITICAL INSTRUCTION**: If Last Action indicates a tool completed, **DO NOT REPEAT IT**. Use the result in history to advance the task (update status or proceed to next step).\n\n`;
    }

    let finalSystemPrompt = prioritizedState + agent.systemPrompt + (session.customPrompt ? `\n\n${session.customPrompt}` : '');

    // 注入工具描述
    // 🔑 增强过滤逻辑：统一使用 SkillRegistry 的会话感知过滤
    const skillsToUse = availableSkills ?? skillRegistry.getEnabledSkillsForSession(session, {
        nativeWebSearch: isNativeWebSearchProvider
    });

    if (skillsToUse.length > 0) {
        const toolsDesc = skillsToUse.map(s => {
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

        // 🆕 使用新架构：获取系统级增强 (Protocol, Capability, Renderer)
        const systemEnhancements = getModelSpecificEnhancements(
            provider.type,
            session.modelId,
            {
                hasTools: true,
                hasNativeSearch: isNativeWebSearchProvider
            }
        );

        const toolInstruction = `\n\n[AVAILABLE TOOLS]
You have access to the following skills:

${toolsDesc}

${systemEnhancements}

[EXECUTION RULES]
1. NATIVE TOOL CALLS ONLY. Use the JSON schema provided.
2. 🚫 NO PARAMETER WRAPPING: DO NOT wrap arguments in a "parameters" key.
3. 🚫 NO INTRODUCTORY TEXT before tool calls.
4. PROVIDE ALL REQUIRED PARAMETERS.
5. Trigger tools immediately.
`;


        finalSystemPrompt += toolInstruction;

        // 🔑 Phase 2: 任务状态注入优化
        // 仅当任务处于活跃状态（非完成/失败）时注入
        // 避免已完成任务的状态污染后续消息
        if (session.activeTask && session.activeTask.status === 'in-progress') {
            const task = session.activeTask;
            const formattedSteps = task.steps.map((s, idx) =>
                `${idx + 1}. [${s.status.toUpperCase()}] ${s.title}${s.description ? ` (${s.description})` : ''}`
            ).join('\n');

            const taskContext = `\n\n[CURRENT TASK STATUS]
Task ID: ${task.id || 'N/A'}
Title: ${task.title}
Status: ${task.status}
Progress: ${task.progress}%
Steps:
${formattedSteps}
 
IMPORTANT: You are currently working on this task. Use 'manage_task' with the correct taskId to update steps.`;
            finalSystemPrompt += taskContext;
        }
    } else {
        // 🆕 Case: No tools enabled, but we still need Output Guidance (Thinking tags)
        // 🛡️ Explicitly warn model that tools are disabled to prevent hallucinations
        finalSystemPrompt += `\n\n[TOOL USAGE: DISABLED]
TOOLS ARE DISABLED. You cannot use any tools. Do not output tool calls.
If you see tool calls in the history, do not repeat them.
Answer the user's request directly using your internal knowledge.`;

        // 🆕 使用新架构：获取基础协议 (Protocol - Thinking, Formatting)
        const systemEnhancements = getModelSpecificEnhancements(
            provider.type,
            session.modelId,
            {
                hasTools: false,
                hasNativeSearch: false
            }
        );
        finalSystemPrompt += `\n\n${systemEnhancements}`;
    }

    // 注入 RAG 上下文
    if (ragContext) {
        finalSystemPrompt += `\n\n${ragContext}`;
    }

    return finalSystemPrompt;
}

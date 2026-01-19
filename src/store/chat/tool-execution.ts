/**
 * 工具执行模块
 * 负责执行工具调用并更新执行步骤
 * Phase 3: 从 chat-store.ts 迁移完整逻辑
 */

import type { ManagerContext, ToolExecutor } from './types';
import type { ToolCall, ToolResult, ExecutionStep } from '../../types/skills';
import type { TaskState } from '../../types/chat';
import { skillRegistry } from '../../lib/skills/registry';
import { useAgentStore } from '../agent-store';
import { SessionRepository } from '../../lib/db/session-repository';

export const createToolExecutor = (context: ManagerContext): ToolExecutor => {
    const { get, set } = context;

    return {
        executeTools: async (sessionId: string, toolCalls: ToolCall[], targetMessageId?: string) => {
            const state = get();
            const session = state.getSession(sessionId);
            if (!session) return;

            const agentStore = useAgentStore.getState();
            const agent = agentStore.getAgent(session.agentId);
            if (!agent) return;

            // 验证目标消息或查找最后一个 assistant 消息
            let targetMsgId = targetMessageId;
            if (!targetMsgId) {
                const lastAssistant = [...session.messages].reverse().find(m => m.role === 'assistant');
                if (lastAssistant) targetMsgId = lastAssistant.id;
            }

            if (!targetMsgId) return;

            const targetMsg = session.messages.find(m => m.id === targetMsgId);
            if (!targetMsg) return;

            // 🛡️ 应用级调度防护：防止 OpenAI 兼容模型在流式初期发送空参数导致的崩溃循环
            const hasIncompleteCall = toolCalls.some(tc => {
                const name = (tc as any).name || (tc as any).function?.name;
                if (name === 'manage_task') {
                    const args = tc.arguments || (tc as any).function?.arguments || {};
                    // 只有当消息处于流式状态且 action 缺失时才拦截
                    return targetMsg.status === 'streaming' && (!args || !args.action);
                }
                return false;
            });

            if (hasIncompleteCall) return;

            // 辅助函数：更新执行步骤
            const updateSteps = (newStep: ExecutionStep) => {
                set(state => {
                    const session = state.sessions.find(s => s.id === sessionId);
                    if (!session) return {};
                    const currentMsg = session.messages.find(m => m.id === targetMsgId);
                    if (!currentMsg) return {};

                    const currentSteps = currentMsg.executionSteps || [];
                    const index = currentSteps.findIndex(s => s.id === newStep.id);
                    let updatedSteps = [...currentSteps];

                    if (index > -1) {
                        updatedSteps[index] = newStep;
                    } else {
                        updatedSteps.push(newStep);
                    }

                    // 🔑 Fix Persistence: Immediately save steps to DB
                    // Fire-and-forget to avoid blocking UI
                    SessionRepository.updateMessage(sessionId, targetMsgId!, {
                        executionSteps: updatedSteps
                    }).catch(e => console.warn('[ToolExecutor] Failed to persist steps:', e));

                    return {
                        sessions: state.sessions.map(s => s.id === sessionId ? {
                            ...s,
                            messages: s.messages.map(m => m.id === targetMsgId ? { ...m, executionSteps: updatedSteps } : m)
                        } : s)
                    };
                });
            };

            console.log('[ToolExecutor] executing tools:', toolCalls.length);
            for (const tc of toolCalls) {
                if (!tc) continue;
                const tcName = (tc as any).name || (tc as any).function?.name;
                console.log('[ToolExecutor] processing tool:', tcName, tc.id);
                if (!tcName) continue;

                const skill = skillRegistry.getSkill(tcName);
                const stepId = `step_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;

                const tcArgs = (tc as any).arguments || (tc as any).function?.arguments || {};
                let finalArgs = typeof tcArgs === 'string' ? JSON.parse(tcArgs) : tcArgs;

                // 🛡️ 智能参数解包
                if (finalArgs && (finalArgs.parameters || finalArgs.arguments)) {
                    const target = finalArgs.parameters || finalArgs.arguments;
                    if (typeof target === 'string') {
                        try { finalArgs = JSON.parse(target); } catch (e) { console.warn('Param unwrap failed', e); }
                    } else if (typeof target === 'object') {
                        finalArgs = target;
                    }
                }

                updateSteps({
                    id: stepId,
                    type: 'tool_call',
                    toolName: tcName,
                    toolArgs: finalArgs,
                    toolCallId: tc.id,
                    timestamp: Date.now()
                });

                let result: ToolResult;
                try {
                    if (skill) {
                        console.log('[ToolExecutor] calling skill execution:', tcName);
                        result = await skill.execute(finalArgs, { sessionId, agentId: agent.id });
                    } else {
                        result = { id: (tc as any).id, content: `Error: Skill ${tcName} not found`, status: 'error' };
                    }
                } catch (e: any) {
                    console.error('[ToolExecutor] skill execution failed:', e);
                    result = { id: (tc as any).id, content: `Error: ${e.message}`, status: 'error' };
                }

                // 🛡️ Global Tool Interceptor: Auto-Reflection for Resilience
                if (result.status === 'error') {
                    // Append system guidance to the error message
                    const reflectionHint = `\n\n[SYSTEM NOTE]: The tool execution failed. Please analyze the error message above. Do NOT give up or apologize. Instead:\n1. Check if arguments were correct (e.g., path existence, valid options).\n2. If a file/directory is missing, use 'list_dir' or 'search_by_name' to locate it.\n3. If a parameter was invalid, check the docs or schema and retry.\n4. Propose a specific alternative approach immediately.`;

                    result.content += reflectionHint;
                    console.log('[ToolExecutor] Interceptor: Added reflection hint to error result');
                }

                updateSteps({
                    id: `res_${stepId}`,
                    type: result.status === 'success' ? 'tool_result' : 'error',
                    toolName: tcName,
                    toolCallId: tc.id,
                    content: result.content,
                    data: result.data,
                    timestamp: Date.now()
                });

                console.log('[ToolExecutor] adding tool message to store:', tc.id);
                // 🧐 UI 优化：所有工具执行结果都必须加入历史记录
                await get().addMessage(sessionId, {
                    id: `tool_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
                    role: 'tool',
                    tool_call_id: tc.id,
                    content: result.content,
                    name: tcName,
                    createdAt: Date.now(),
                    thought_signature: targetMsg.thought_signature // 🔑 继承父 assistant 消息的签名
                });
                console.log('[ToolExecutor] message added');

                // ✅ 任务状态持久化：如果工具返回了 TaskState 数据 (特别是 manage_task)，
                // 则将其同步回写至触发它的 Assistant 消息中。
                if (result.data && result.status === 'success') {
                    const isTaskState = (data: any): data is TaskState =>
                        data && typeof data === 'object' && 'steps' in data && 'progress' in data;

                    if (isTaskState(result.data)) {
                        // 🧠 Intelligent UI Hoisting: 
                        // If completing a task with a summary, promote the summary to the visible message body!
                        // This fixes the issue where the final result is hidden inside the JSON tool args.
                        let visibleContent = targetMsg.content;
                        if (tcName === 'manage_task' && finalArgs.action === 'complete' && finalArgs.final_summary) {
                            visibleContent = finalArgs.final_summary;
                            console.log('[ToolExecutor] Hoisted final_summary to message body:', visibleContent.substring(0, 50) + '...');
                        }

                        get().updateMessageContent(
                            sessionId,
                            targetMsgId,
                            visibleContent, // 🚀 Updated Content
                            undefined,
                            undefined,
                            undefined,
                            undefined,
                            false,
                            undefined,
                            undefined,
                            result.data // taskState (param 11)
                        );

                        // 🏁 Persistence Finalizer: 
                        // Force immediate DB flush when task is complete to prevent data loss on app restart.
                        if (get().flushMessageUpdates && tcName === 'manage_task' && finalArgs.action === 'complete') {
                            console.log('[ToolExecutor] Forcing immediate DB flush for final_summary');
                            get().flushMessageUpdates(sessionId, targetMsgId);
                        }
                    }
                }
            }
        },
    };
};

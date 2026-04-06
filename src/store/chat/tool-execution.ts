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

            // 🛡️ Tool Shielding & Self-Reflection Interceptor
            // If tools are explicitly disabled in session options, intercept the call and force a reflection.
            if (session.options?.toolsEnabled === false) {
                console.warn('[ToolExecutor] Intercepted tool call because tools are disabled for this session.');

                // Create synthetic error results for all calls
                const syntheticResults: ToolResult[] = toolCalls.map(tc => ({
                    id: (tc as any).id || 'unknown',
                    status: 'error',
                    content: `[SYSTEM WARNING]: Tool usage is currently DISABLED by the user configuration.
You CANNOT use tools in this turn.
Please STOP trying to use tools and answer the user's request directly using your internal knowledge.`
                }));

                // Persist these "errors" so the model sees them and self-corrects
                for (const result of syntheticResults) {
                    // We need to add a "tool_result" step to visualization so user knows what happened
                    const stepId = `step_shield_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
                    const tc = toolCalls.find(t => t.id === result.id);
                    const tcName = tc ? ((tc as any).name || (tc as any).function?.name) : 'unknown_tool';

                    set(state => {
                        const session = state.sessions.find(s => s.id === sessionId);
                        if (!session) return {};
                        // Add a visual step (optional, but good for UX)
                        /* 
                        // Skipped adding visual step to avoid cluttering UI with 'failed' steps for blocked actions?
                        // Actually, showing it as an error is good feedback.
                        */
                        return {};
                    });

                    // Add the tool message (Role: tool) so model sees the feedback
                    await get().addMessage(sessionId, {
                        id: `tool_shield_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
                        role: 'tool',
                        tool_call_id: result.id,
                        content: result.content,
                        name: tcName,
                        createdAt: Date.now(),
                        thought_signature: targetMsg.thought_signature
                    });
                }

                return; // ⛔ Stop execution here
            }

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

            // 🔑 统一更新路径：确保原子化更新步骤列表
            // 解决缓冲竞争导致的指令步骤消失问题
            const appendStep = (newStep: ExecutionStep) => {
                const state = get();

                // 🛡️ 关键修复：在读取前强制冲刷该消息的缓冲区，确保 currentSteps 是最新的
                if (state.flushMessageUpdates) {
                    state.flushMessageUpdates(sessionId, targetMsgId);
                }

                const updatedSession = get().getSession(sessionId);
                if (!updatedSession) return;
                const currentMsg = updatedSession.messages.find(m => m.id === targetMsgId);
                if (!currentMsg) return;

                const currentSteps = currentMsg.executionSteps || [];
                // 查找并更新现有步骤，或追加新步骤
                const index = currentSteps.findIndex(s => s.id === newStep.id || (newStep.toolCallId && s.toolCallId === newStep.toolCallId && s.type === newStep.type));

                let updatedSteps = [...currentSteps];
                if (index > -1) {
                    updatedSteps[index] = { ...updatedSteps[index], ...newStep };
                } else {
                    updatedSteps.push(newStep);
                }

                // 通过缓冲区更新
                get().updateMessageContent(
                    sessionId,
                    targetMsgId,
                    currentMsg.content || '',
                    { executionSteps: updatedSteps }
                );
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

                if (skill?.mcpServerId) {
                    // 🛡️ 运行时过滤：检查 MCP 服务器在当前会话中是否启用
                    const activeMcpIds = session.activeMcpServerIds || [];
                    if (!activeMcpIds.includes(skill.mcpServerId)) {
                        console.warn(`[ToolExecutor] Blocking call to disabled MCP tool: ${tcName}`);
                        const result: ToolResult = {
                            id: tc.id,
                            status: 'error',
                            content: `[SYSTEM ERROR]: Tool "${tcName}" is currently DISABLED for this session. 
Please DO NOT try to call it again. Instead:
1. Use an alternative tool if available (e.g., 'search_internet').
2. If this was a necessary step, update the task plan to mark it as blocked or failed, then propose a different route.
3. Answer based on your existing knowledge if possible.`
                        };

                        appendStep({
                            id: `res_${stepId}`,
                            type: 'error',
                            toolName: tcName,
                            toolCallId: tc.id,
                            content: result.content,
                            timestamp: Date.now()
                        });

                        await get().addMessage(sessionId, {
                            id: `tool_block_${Date.now()}_${tc.id}`,
                            role: 'tool',
                            tool_call_id: tc.id,
                            content: result.content,
                            name: tcName,
                            createdAt: Date.now(),
                            thought_signature: targetMsg.thought_signature
                        });
                        continue;
                    }

                    const mcpStore = (await import('../../store/mcp-store')).useMcpStore.getState();
                    const server = mcpStore.servers.find(s => s.id === skill.mcpServerId);

                    if (server?.callInterval && server.callInterval > 0) {
                        const now = Date.now();
                        const lastCall = server.lastCallTimestamp || 0;
                        const waitMs = (server.callInterval * 1000) - (now - lastCall);

                        if (waitMs > 0) {
                            console.log(`[ToolExecutor] Rate limit hit for MCP '${server.name}'. Waiting ${waitMs}ms...`);

                            // 更新步骤为挂起倒计时状态
                            appendStep({
                                id: stepId,
                                type: 'throttled',
                                toolName: tcName,
                                toolArgs: finalArgs,
                                toolCallId: tc.id,
                                timestamp: now,
                                throttledUntil: now + waitMs
                            });

                            // 执行挂起
                            await new Promise(resolve => setTimeout(resolve, waitMs));

                            // 恢复后切换回常规 call 状态
                            appendStep({
                                id: stepId,
                                type: 'tool_call',
                                toolName: tcName,
                                toolArgs: finalArgs,
                                toolCallId: tc.id,
                                timestamp: Date.now()
                            });
                        }
                    }

                    // 通用：更新该服务器的最后调用时间戳
                    (await import('../../store/mcp-store')).useMcpStore.getState().updateServer(server!.id, {
                        lastCallTimestamp: Date.now()
                    });
                } else {
                    appendStep({
                        id: stepId,
                        type: 'tool_call',
                        toolName: tcName,
                        toolArgs: finalArgs,
                        toolCallId: tc.id,
                        timestamp: Date.now()
                    });
                }

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

                appendStep({
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

                // ✅ 任务状态持久化
                if (result.data && result.status === 'success') {
                    const isTaskState = (data: any): data is TaskState =>
                        data && typeof data === 'object' && 'steps' in data && 'progress' in data;

                    if (isTaskState(result.data)) {
                        get().updateMessageContent(
                            sessionId,
                            targetMsgId,
                            targetMsg.content,
                            { ragReferencesLoading: false, planningTask: result.data }
                        );

                        if (get().flushMessageUpdates && tcName === 'manage_task' && finalArgs.action === 'complete') {
                            console.log('[ToolExecutor] Forcing immediate DB flush for final_summary');
                            get().flushMessageUpdates(sessionId, targetMsgId);
                        }
                    }
                }

                // 🌟 SPECIAL FEATURE: Direct Rendering Injection
                // If the tool is a rendering tool and it succeeded, we immediately inject
                // the content into the CALLING assistant message as a structured artifact.
                if ((tcName === 'render_echarts' || tcName === 'render_mermaid') && result.status === 'success') {
                    const artifactType = tcName === 'render_echarts' ? 'echarts' : 'mermaid';
                    const regex = tcName === 'render_echarts' ? /```echarts[\s\S]*?```/ : /```mermaid[\s\S]*?```/;
                    const markdownMatch = result.content.match(regex);

                    if (markdownMatch) {
                        console.log(`[ToolExecutor] Adding ${artifactType} artifact to parent assistant message`);
                        const currentMsg = session.messages.find(m => m.id === targetMsgId);
                        if (currentMsg) {
                            const newToolResults = [
                                ...(currentMsg.toolResults || []),
                                { type: artifactType as any, content: markdownMatch[0], name: tcName }
                            ];
                            get().updateMessageContent(
                                sessionId,
                                targetMsgId!,
                                currentMsg.content || '',
                                { toolResults: newToolResults }
                            );
                        }
                    }
                }
            }
        },
    };
};

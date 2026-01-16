/**
 * 审批管理模块
 * 负责Semi-Auto/Manual模式下的审批流程
 */

import type { ManagerContext, ApprovalManager, LoopStatus } from './types';
import type { ExecutionStep } from '../../types/skills';

export const createApprovalManager = (context: ManagerContext): ApprovalManager => {
    const { get, set } = context;

    return {
        setApprovalRequest: (sessionId: string, request) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, approvalRequest: request } : s
                ),
            }));
        },

        resumeGeneration: async (sessionId, approved = true, intervention) => {
            const session = get().getSession(sessionId);
            if (!session || !session.approvalRequest) return;

            // 0. Update Timeline with Decision
            // ✅ CRITICAL FIX: 查找包含待审批工具（pendingApprovalToolIds）的 Assistant 消息
            // 因为低风险工具执行后，最后一条消息可能是 tool 消息，而非 assistant 消息
            const targetMsg = session.messages
                .slice()
                .reverse()
                .find(m => m.role === 'assistant' && (m as any).pendingApprovalToolIds?.length > 0)
                || session.messages[session.messages.length - 1]; // Fallback

            if (targetMsg && targetMsg.role === 'assistant') {
                const decisionStep: ExecutionStep = {
                    id: `dec_${Date.now()}`,
                    type: 'intervention_result',
                    content: intervention
                        ? `Human Instruction: ${intervention}`
                        : approved
                            ? 'User Approved'
                            : 'User Rejected',
                    timestamp: Date.now(),
                };

                set((state) => ({
                    sessions: state.sessions.map((s) =>
                        s.id === sessionId
                            ? {
                                ...s,
                                messages: s.messages.map((m) =>
                                    m.id === targetMsg.id
                                        ? {
                                            ...m,
                                            executionSteps: [
                                                ...(m.executionSteps || []).filter(
                                                    (st) => st.type !== 'intervention_required'
                                                ),
                                                decisionStep,
                                            ],
                                        }
                                        : m
                                ),
                            }
                            : s
                    ),
                }));
            }

            if (intervention) {
                get().setPendingIntervention(sessionId, intervention);
            }

            if (!approved && !intervention) {
                // If rejected without instruction, stop loop
                get().setLoopStatus(sessionId, 'paused');
                get().setApprovalRequest(sessionId, undefined);
                return;
            }

            // 1. Execute Tools if approved
            if (approved && !intervention) {
                if (targetMsg && targetMsg.tool_calls) {
                    // ✅ CRITICAL FIX: 只执行待审批的工具（由 pendingApprovalToolIds 标记）
                    // 低风险工具在暂停前已执行，不应重复执行
                    const pendingIds = (targetMsg as any).pendingApprovalToolIds || [];
                    const toolsToExecute = pendingIds.length > 0
                        ? targetMsg.tool_calls.filter((tc: any) => pendingIds.includes(tc.id))
                        : targetMsg.tool_calls; // 回退：如果没有标记，执行全部

                    console.log('[ApprovalManager] Executing', toolsToExecute.length, 'of', targetMsg.tool_calls.length, 'tools');
                    await get().executeTools(sessionId, toolsToExecute, targetMsg.id);

                    // 清除 pendingApprovalToolIds 标记
                    set((state) => ({
                        sessions: state.sessions.map((s) =>
                            s.id === sessionId
                                ? {
                                    ...s,
                                    messages: s.messages.map((m) =>
                                        m.id === targetMsg.id
                                            ? { ...m, pendingApprovalToolIds: undefined }
                                            : m
                                    ),
                                }
                                : s
                        ),
                    }));
                }
            }

            // 2. Clear Request & Update Status
            get().setApprovalRequest(sessionId, undefined);
            get().setLoopStatus(sessionId, 'running');

            // 3. Continue Generation (Next Turn)
            await get().generateMessage(sessionId, '', {
                isResumption: true,
            } as any);
        },

        setExecutionMode: (sessionId, mode) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, executionMode: mode } : s
                ),
            }));
        },

        setLoopStatus: (sessionId: string, status: LoopStatus) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, loopStatus: status } : s
                ),
            }));
        },

        setPendingIntervention: (sessionId, intervention) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, pendingIntervention: intervention } : s
                ),
            }));
        },
    };
};

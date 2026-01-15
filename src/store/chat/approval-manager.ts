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
            const lastMsg = session.messages[session.messages.length - 1];
            if (lastMsg && lastMsg.role === 'assistant') {
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
                                    m.id === lastMsg.id
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
                if (lastMsg && lastMsg.tool_calls) {
                    await get().executeTools(sessionId, lastMsg.tool_calls, lastMsg.id);
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

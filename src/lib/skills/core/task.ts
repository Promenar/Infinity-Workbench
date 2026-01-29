import { z } from 'zod';
import { Skill, SkillContext, ToolResult } from '../../../types/skills';
import { useChatStore } from '../../../store/chat-store';

export const TaskManagementSkill: Skill = {
    id: 'manage_task',
    name: 'Task Manager',
    description: `Manage a persistent, multi-step task or plan. 
IMPORTANT: You should ALWAYS call this tool to "create" a plan BEFORE starting a complex task sequence.
CRITICAL: Each step MUST have a descriptive 'title'.

INTERACTIVE PAUSE & USER FEEDBACK:
- You MUST use action='ask_user' WHENEVER you need to ask the user a question, confirm a step, or request clarifying information.
- This is the ONLY way to yield control back to the user to get a response during a task.
- Use this aggressively for: Confirmation, Ambiguity Resolution, or Next-Step Guidance.
- Do NOT just print a question; you MUST call this tool.

Self-Correction: If you forget the 'action' parameter, I will try to infer it based on context (defaulting to 'update').`,
    schema: z.object({
        action: z.enum(['create', 'update', 'complete', 'fail', 'ask_user']).optional().describe('The action to perform. Defaults to "update" if omitted but steps are provided.'),
        title: z.string().optional().describe('Title of the entire task (required for "create"). Must be descriptive.'),
        steps: z.array(z.object({
            id: z.string().optional().describe('Unique ID for the step. If omitted, will be auto-generated or matched by title.'),
            title: z.string().describe('REQUIRED: Descriptive title of the step.'),
            status: z.enum(['pending', 'in-progress', 'completed', 'failed', 'skipped']).optional().describe('Status of the step'),
            description: z.string().optional().describe('Optional detailed description')
        })).optional().describe('List of steps to create or update.'),
        progress: z.number().min(0).max(100).optional().describe('Override overall progress percentage (0-100).'),
        final_summary: z.string().optional().describe('REQUIRED when action="complete". The FINAL DELIVERABLE or ANSWER requested by the user. Do NOT describe the process or what you did; just provide the result.'),
        question: z.string().optional().describe('REQUIRED when action="ask_user". The question to ask the user to proceed.')
    }),
    execute: async (args: any, context: SkillContext): Promise<ToolResult> => {
        const { sessionId } = context;
        if (!sessionId) return { id: 'error', content: 'Session ID is required', status: 'error' };

        const store = useChatStore.getState();
        const session = store.getSession(sessionId);
        if (!session) return { id: 'error', content: 'Session not found', status: 'error' };

        let activeTask = session.activeTask;
        let taskArgs = args;

        // 🛡️ 智能参数展平 (Universal Parameter Flattening)
        if (args && typeof args === 'object') {
            const nestedTarget = args.parameters || args.arguments;
            if (nestedTarget) {
                if (typeof nestedTarget === 'string') {
                    try {
                        const parsed = JSON.parse(nestedTarget);
                        taskArgs = { ...args, ...parsed };
                        console.log('[TaskSkill] Auto-unwrapped string parameters');
                    } catch (e) {
                        console.warn('[TaskSkill] Failed to parse nested parameters string:', e);
                    }
                } else if (typeof nestedTarget === 'object') {
                    taskArgs = { ...args, ...nestedTarget };
                    console.log('[TaskSkill] Auto-flattened nested parameters object');
                }
            }
        }

        try {
            // 🛡️ 防御性解析：兼容 steps 被传为 JSON 字符串
            if (typeof taskArgs.steps === 'string') {
                try {
                    const sanitized = taskArgs.steps.trim().replace(/^```json\s*|\s*```$/g, '');
                    taskArgs.steps = JSON.parse(sanitized);
                } catch (pe) {
                    console.error('[TaskSkill] Failed to parse steps string:', pe);
                }
            }

            // 🧠 智能动作推断 (Self-Correction)
            if (!taskArgs.action) {
                if (!activeTask && taskArgs.title && taskArgs.steps?.length > 0) {
                    taskArgs.action = 'create';
                    console.log('[TaskSkill] Auto-inferred action: create');
                } else if (activeTask) {
                    taskArgs.action = 'update';
                    console.log('[TaskSkill] Auto-inferred action: update');
                } else {
                    return {
                        id: 'error',
                        content: 'Missing "action" parameter. Please specify "create" (for new tasks) or "update" (for existing tasks).',
                        status: 'error'
                    };
                }
            }

            if (taskArgs.action === 'create') {
                // 🛡️ Strict Mode: Prevent overwriting active tasks
                if (activeTask && activeTask.status === 'in-progress') {
                    return {
                        id: 'error',
                        content: `⚠️ REJECTED: An active task "${activeTask.title}" is already in progress. You MUST complete or fail the current task before creating a new one.\nUse 'action': 'update' to track the current task.`,
                        status: 'error',
                        data: activeTask
                    };
                }

                if (!taskArgs.title || !taskArgs.steps || taskArgs.steps.length === 0) {
                    return {
                        id: 'error',
                        content: 'Invalid CREATE request. Title and steps are required.\nCorrection: Please call again with { "action": "create", "title": "...", "steps": [...] }.',
                        status: 'error'
                    };
                }

                const taskUid = `task-${Date.now()}-${Math.random().toString(36).substr(2, 6)}`;
                activeTask = {
                    id: taskUid,
                    title: taskArgs.title,
                    status: 'in-progress',
                    progress: 0,
                    steps: taskArgs.steps.map((s: any, idx: number) => {
                        // 🧠 Robustness: If s is just a string, treat it as the title
                        if (typeof s === 'string') {
                            return {
                                id: `${taskUid}-step-${idx + 1}`,
                                title: s,
                                status: 'pending'
                            };
                        }

                        return {
                            id: s.id || `${taskUid}-step-${idx + 1}`, // 🧠 1-based indexing for Model Alignment
                            title: s.title || s.description || `Step ${idx + 1}`,
                            status: s.status || 'pending',
                            description: s.description
                        };
                    }),
                    createdAt: Date.now(),
                    updatedAt: Date.now()
                } as any;
            }
            else if (taskArgs.action === 'update') {
                if (!activeTask) {
                    return {
                        id: 'error',
                        content: 'No active task found to update. Correction: Use "action": "create" to start a new task.',
                        status: 'error'
                    };
                }

                const updatedSteps = [...activeTask.steps];

                if (taskArgs.steps) {
                    // 🛡️ Strict Mode: Immutable Plan & Sequential Execution
                    // Feature: Atomic Completion (Zero-Tolerance for Rapids-Fire Completion)
                    let completionCount = 0;
                    for (const s of taskArgs.steps) {
                        if (s.status === 'completed') completionCount++;
                    }
                    if (completionCount > 1) {
                        return {
                            id: 'error',
                            content: `⛔ STRICT MODE REJECTION: You are attempting to complete ${completionCount} steps at once.\n\n⚠️ RULE: To prevent hallucination, you can only complete ONE step per tool call.\n👉 FIX: Mark ONLY the first completed step as 'completed'. Then, EXECUTE the actions for the next step. Then, mark the next step.`,
                            status: 'error',
                            data: activeTask
                        };
                    }

                    for (const newStep of taskArgs.steps) {
                        // 1. Identify Target Step with Smart Resolution
                        let index = -1;

                        // Strategy A: Exact ID Match
                        if (newStep.id) {
                            index = updatedSteps.findIndex(s => s.id === newStep.id);
                        }

                        // Strategy B: 1-Based Index Match (Handling model guessing "1", "2", "3")
                        if (index === -1 && newStep.id && !isNaN(parseInt(newStep.id))) {
                            const numIndex = parseInt(newStep.id) - 1;
                            if (numIndex >= 0 && numIndex < updatedSteps.length) {
                                index = numIndex;
                                console.log(`[TaskSkill] Resolved numeric ID "${newStep.id}" to step index ${index}`);
                            }
                        }

                        // Strategy C: Fuzzy Title Match
                        if (index === -1 && newStep.title) {
                            index = updatedSteps.findIndex(s =>
                                s.title?.trim().toLowerCase() === newStep.title.trim().toLowerCase()
                            );
                        }

                        // ❌ REJECT: Step Not Found? Give Helpful Hints!
                        if (index === -1) {
                            const validStepsHint = updatedSteps.map((s, i) =>
                                `${i + 1}. [${s.id}] "${s.title}" (${s.status})`
                            ).join('\n');

                            return {
                                id: 'error',
                                content: `⛔ REJECTED: Step identifier "${newStep.id || newStep.title}" not found.\n\n💡 HINT: You provided an invalid ID. Here are the valid steps:\n${validStepsHint}\n\nPlease retry using the exact ID or the 1-based index (e.g., "id": "1").`,
                                status: 'error',
                                data: activeTask
                            };
                        }

                        // ❌ REJECT: Modifying Title/Description (Structural Change) - Warning only or Strict? 
                        // User said "add/delete/change steps content not allowed".
                        // We will ignore title/desc changes and ONLY update status.
                        // But we should verify status transition.

                        const targetStep = updatedSteps[index];
                        const nextStatus = newStep.status || targetStep.status;

                        // 🛡️ Sequential Check: If marking as completed/skipped
                        if ((nextStatus === 'completed' || nextStatus === 'skipped') && targetStep.status !== 'completed' && targetStep.status !== 'skipped') {
                            // Verify all previous steps are done
                            for (let i = 0; i < index; i++) {
                                const prevStep = updatedSteps[i];
                                if (prevStep.status !== 'completed' && prevStep.status !== 'skipped') {
                                    return {
                                        id: 'error',
                                        content: `⛔ REJECTED: Sequential Order Violation. Step ${i + 1} ("${prevStep.title}") is not yet completed.\n\n⚠️ RULE: You MUST complete steps in strict order (1->2->3...).\n👉 FIX: Please mark Step ${i + 1} as 'completed' first.`,
                                        status: 'error',
                                        data: activeTask
                                    };
                                }
                            }
                        }

                        // Apply Status Update ONLY
                        updatedSteps[index] = {
                            ...targetStep,
                            status: nextStatus
                            // Ignore title/desc updates to enforce immutability
                        };
                    }
                }

                // 🧠 Auto-calculate progress
                let newProgress = taskArgs.progress;
                if (newProgress === undefined) {
                    const completed = updatedSteps.filter(s => s.status === 'completed' || s.status === 'skipped').length;
                    newProgress = Math.round((completed / updatedSteps.length) * 100);
                }

                activeTask = {
                    ...activeTask,
                    steps: updatedSteps,
                    progress: newProgress,
                    updatedAt: Date.now()
                };
            }
            else if (taskArgs.action === 'complete') {
                if (!activeTask) return { id: 'error', content: 'No active task found to complete.', status: 'error' };

                // 🛡️ Strict Mode: Enforce Final Summary
                if (!taskArgs.final_summary || taskArgs.final_summary.length < 5) {
                    return {
                        id: 'error',
                        content: `⛔ REJECTED: You MUST provide a 'final_summary' when completing a task. Please summarize what was achieved in natural language for the user.\nCall again with 'action': 'complete' AND 'final_summary': '...'.`,
                        status: 'error',
                        data: activeTask
                    };
                }

                // 🛡️ Auto-complete pending steps
                const completedSteps = activeTask.steps.map((step: any) => {
                    if (step.status === 'pending' || step.status === 'in-progress') {
                        return { ...step, status: 'completed' };
                    }
                    return step;
                });

                activeTask = {
                    ...activeTask,
                    steps: completedSteps,
                    status: 'completed',
                    progress: 100,
                    final_summary: taskArgs.final_summary, // ✅ Persist the summary
                    updatedAt: Date.now()
                };
            }
            else if (taskArgs.action === 'fail') {
                if (!activeTask) return { id: 'error', content: 'No active task found to fail.', status: 'error' };
                activeTask = { ...activeTask, status: 'failed', updatedAt: Date.now() };
            }
            else if (taskArgs.action === 'ask_user') {
                if (!activeTask) return { id: 'error', content: 'No active task found. create a task first.', status: 'error' };

                if (!taskArgs.question) {
                    return {
                        id: 'error',
                        content: 'Missing "question". usage: { action: "ask_user", question: "..." }',
                        status: 'error'
                    }
                }

                // ⏸️ PAUSE MECHANISM
                // We update the session to 'paused' and store the question.
                // The Agent Loop will see this status and exit gracefully.
                store.updateSession(sessionId, {
                    loopStatus: 'paused',
                    pendingIntervention: taskArgs.question
                });

                return {
                    id: 'success',
                    content: `⏸️ Task Paused. Waiting for user input: "${taskArgs.question}"`,
                    status: 'success',
                    data: activeTask
                };
            }
            else {
                return {
                    id: 'error',
                    content: `Invalid action "${taskArgs.action}". Allowed: create, update, complete, fail, ask_user.`,
                    status: 'error'
                };
            }

            // Update Store
            if (activeTask) {
                store.updateSession(sessionId, { activeTask });
            }

            const finalStatus = activeTask ? activeTask.status : 'unknown';
            const finalProgress = activeTask ? activeTask.progress : 0;
            const finalTitle = activeTask ? activeTask.title : 'Untitled';

            // 🧠 Enhanced Contextual Feedback
            let resultContent = `Task "${finalTitle}" updated. Status: ${finalStatus}, Progress: ${finalProgress}%`;

            if (taskArgs.action === 'create') {
                resultContent = `✅ Task "${finalTitle}" created.\n\n👉 NEXT: Execute the first step immediately.`;
            } else if (taskArgs.action === 'complete') {
                resultContent = `🎉 Task "${finalTitle}" completed. Please provide a final summary to the user.`;
            } else if (taskArgs.action === 'update') {
                // Anti-Hallucination: Find next pending step
                if (activeTask) {
                    const nextStep = activeTask.steps.find((s: any) => s.status === 'pending');
                    if (nextStep) {
                        resultContent += `\n\n⚠️ WAIT: The next step is "${nextStep.title}" (ID: ${nextStep.id}).
If you have NOT executed this action yet, please DO NOT mark it as completed. 
If the required tool is failing or unavailable, please:
1. Update the task to mark this step as 'failed' or 'skipped'.
2. Explain the roadblock and propose an alternative plan.
3. Use 'search_internet' or internal knowledge to find workarounds.`;
                    }
                }
            }

            return {
                id: 'success',
                content: resultContent,
                status: 'success',
                data: activeTask
            };

        } catch (e: any) {
            return { id: 'error', content: `Task Management Error: ${e.message}`, status: 'error' };
        }
    }
};

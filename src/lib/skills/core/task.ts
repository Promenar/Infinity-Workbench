import { z } from 'zod';
import { Skill, SkillContext, ToolResult } from '../../../types/skills';
import { useChatStore } from '../../../store/chat-store';

export const TaskManagementSkill: Skill = {
    id: 'manage_task',
    name: 'Task Manager',
    description: 'Manage a persistent, multi-step task or plan. IMPORTANT: You should ALWAYS call this tool to "create" a plan BEFORE starting a complex task sequence to let the user know your roadmap.',
    schema: z.object({
        action: z.enum(['create', 'update', 'complete', 'fail']).describe('The action to perform on the task.'),
        title: z.string().optional().describe('Title of the entire task (required for "create").'),
        steps: z.array(z.object({
            id: z.string().describe('Unique ID for the step (e.g., "step-1")'),
            title: z.string().optional().describe('Short title of the step. If omitted, it will be derived from description.'),
            status: z.enum(['pending', 'in-progress', 'completed', 'failed', 'skipped']).optional().describe('Status of the step'),
            description: z.string().optional().describe('Optional detailed description')
        })).optional().describe('List of steps to create or update.'),
        progress: z.number().min(0).max(100).optional().describe('Override overall progress percentage (0-100). If omitted, auto-calculated.')
    }),
    execute: async (args: any, context: SkillContext): Promise<ToolResult> => {
        const { sessionId } = context;
        if (!sessionId) {
            return { id: 'error', content: 'Session ID is required', status: 'error' };
        }
        const store = useChatStore.getState();
        const session = store.getSession(sessionId);

        if (!session) {
            return { id: 'error', content: 'Session not found', status: 'error' };
        }

        let activeTask = session.activeTask;

        let taskArgs = args;

        // 🛡️ GLM 专项加固：自动解包嵌套在 'parameters' 或 'arguments' 里的参数
        if (args.parameters || args.arguments) {
            const rawTarget = args.parameters || args.arguments;
            if (typeof rawTarget === 'string') {
                try {
                    taskArgs = JSON.parse(rawTarget);
                    console.log('[TaskSkill] Auto-unwrapped string parameters from GLM');
                } catch (e) {
                    console.warn('[TaskSkill] Failed to unwrap parameters string:', e);
                }
            } else if (typeof rawTarget === 'object') {
                taskArgs = rawTarget;
                console.log('[TaskSkill] Auto-flattened nested parameters object from GLM');
            }
        }

        try {
            // 🛡️ 防御性解析：兼容 steps 被传为 JSON 字符串的情况
            if (typeof taskArgs.steps === 'string') {
                try {
                    const sanitized = taskArgs.steps.trim().replace(/^```json\s*|\s*```$/g, '');
                    taskArgs.steps = JSON.parse(sanitized);
                } catch (pe) {
                    console.error('[TaskSkill] Failed to parse steps string:', pe);
                }
            }

            if (taskArgs.action === 'create') {
                if (!taskArgs.title || !taskArgs.steps || taskArgs.steps.length === 0) {
                    return { id: 'error', content: 'Title and steps are required for creating a task. You MUST specify { "action": "create", "title": "...", "steps": [...] }.', status: 'error' };
                }

                activeTask = {
                    title: taskArgs.title,
                    status: 'in-progress',
                    progress: 0,
                    steps: taskArgs.steps.map((s: any, idx: number) => {
                        const derivedTitle = s.title || (s.description ?
                            (s.description.length > 20 ? s.description.substring(0, 20) + '...' : s.description) :
                            `动作 ${idx + 1}`);
                        return {
                            id: s.id || `step-${idx}`,
                            title: derivedTitle,
                            status: s.status || 'pending',
                            description: s.description
                        };
                    }),
                    createdAt: Date.now(),
                    updatedAt: Date.now()
                };
            }
            else if (taskArgs.action === 'update') {
                if (!activeTask) {
                    return { id: 'error', content: 'No active task found to update. Use "create" first.', status: 'error' };
                }

                const updatedSteps = [...activeTask.steps];

                if (taskArgs.steps) {
                    taskArgs.steps.forEach((newStep: any) => {
                        const index = updatedSteps.findIndex(s => s.id === newStep.id);
                        if (index !== -1) {
                            updatedSteps[index] = {
                                ...updatedSteps[index],
                                ...newStep,
                                title: newStep.title || updatedSteps[index].title // Keep old title if new one missing
                            };
                        } else {
                            updatedSteps.push({
                                id: newStep.id || `step-${updatedSteps.length}`,
                                title: newStep.title || (newStep.description ?
                                    (newStep.description.length > 20 ? newStep.description.substring(0, 20) + '...' : newStep.description) :
                                    `动作 ${updatedSteps.length + 1}`),
                                status: newStep.status || 'pending',
                                description: newStep.description
                            });
                        }
                    });
                }

                // Auto-calculate progress if not provided
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
                activeTask = { ...activeTask, status: 'completed', progress: 100, updatedAt: Date.now() };
            }
            else if (taskArgs.action === 'fail') {
                if (!activeTask) return { id: 'error', content: 'No active task found to fail.', status: 'error' };
                activeTask = { ...activeTask, status: 'failed', updatedAt: Date.now() };
            }
            else {
                return {
                    id: 'error',
                    content: `Missing or invalid "action" parameter: "${taskArgs.action}". You MUST provide "action": "create" | "update" | "complete" | "fail".`,
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

            return {
                id: 'success',
                content: `Task "${finalTitle}" handled. Status: ${finalStatus}, Progress: ${finalProgress}%`,
                status: 'success',
                data: activeTask
            };

        } catch (e: any) {
            return { id: 'error', content: `Failed to manage task: ${e.message}`, status: 'error' };
        }
    }
};

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

        try {
            // 🛡️ 防御性解析：如果模型（如 GLM）因为逻辑混乱将 steps 传成了 JSON 字符串
            if (typeof args.steps === 'string') {
                try {
                    console.log('[TaskSkill] Raw steps string detected, attempting auto-fix...');
                    const sanitized = args.steps.trim().replace(/^```json\s*|\s*```$/g, '');
                    args.steps = JSON.parse(sanitized);
                } catch (pe) {
                    console.error('[TaskSkill] Failed to pre-parse steps string:', pe);
                }
            }

            if (args.action === 'create') {
                if (!args.title || !args.steps || args.steps.length === 0) {
                    return { id: 'error', content: 'Title and steps are required for creating a task.', status: 'error' };
                }

                activeTask = {
                    title: args.title,
                    status: 'in-progress',
                    progress: 0,
                    steps: args.steps.map((s: any, idx: number) => {
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
            else if (args.action === 'update') {
                if (!activeTask) {
                    return { id: 'error', content: 'No active task found to update. Use "create" first.', status: 'error' };
                }

                const updatedSteps = [...activeTask.steps];

                if (args.steps) {
                    args.steps.forEach((newStep: any) => {
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
                let newProgress = args.progress;
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
            else if (args.action === 'complete') {
                if (!activeTask) return { id: 'error', content: 'No active task.', status: 'error' };
                activeTask = { ...activeTask, status: 'completed', progress: 100, updatedAt: Date.now() };
                // Also mark all pending steps as completed? 
                // Optional, but let's leave steps as is to reflect reality
            }
            else if (args.action === 'fail') {
                if (!activeTask) return { id: 'error', content: 'No active task.', status: 'error' };
                activeTask = { ...activeTask, status: 'failed', updatedAt: Date.now() };
            }

            // Update Store
            store.updateSession(sessionId, { activeTask });

            return {
                id: 'success',
                content: `Task "${activeTask!.title}" updated. Status: ${activeTask!.status}, Progress: ${activeTask!.progress}%`,
                status: 'success',
                data: activeTask
            };

        } catch (e: any) {
            return { id: 'error', content: `Failed to manage task: ${e.message}`, status: 'error' };
        }
    }
};

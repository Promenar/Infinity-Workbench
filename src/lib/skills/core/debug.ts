import { z } from 'zod';
import { Skill } from '../../../types/skills'; // Correct path to types/skills
import { emitToast } from '../../utils/toast-emitter';

export const ShowDebugToastSkill: Skill = {
    id: 'show_debug_toast',
    name: 'Show Debug Toast',
    description: 'Display a temporary toast notification on the user screen. Useful for debugging or confirming actions without full output.',
    schema: z.object({
        message: z.string().describe('The message to display in the toast'),
        type: z.enum(['success', 'error', 'info', 'warning']).optional().describe('The type of toast to show (default: info)'),
    }),
    execute: async (params: { message: string, type?: 'success' | 'error' | 'info' | 'warning' }, context) => {
        emitToast(params.message, params.type || 'info');
        return {
            id: 'debug-toast', // Add required ID
            content: `Toast displayed: "${params.message}" (${params.type || 'info'})`,
            status: 'success'
        };
    },
};

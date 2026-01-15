/**
 * 工具执行模块
 * 负责执行工具调用并更新执行步骤
 * 注意：此模块复用chat-store中的executeTools实现，仅作为接口包装
 */

import type { ManagerContext, ToolExecutor } from './types';
import type { ToolCall } from '../../types/skills';

export const createToolExecutor = (context: ManagerContext): ToolExecutor => {
    const { get } = context;

    return {
        executeTools: async (sessionId: string, toolCalls: ToolCall[], targetMessageId?: string) => {
            // 直接调用chat-store中的executeTools实现
            // 这避免了重复大量代码，保持向后兼容
            await get().executeTools(sessionId, toolCalls, targetMessageId);
        },
    };
};

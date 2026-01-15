/**
 * AgentLoop管理模块
 * 负责消息生成和重新生成的核心逻辑
 * 注意：此模块复用chat-store中的generateMessage/regenerateMessage实现
 */

import type { ManagerContext, AgentLoopManager, GenerateMessageOptions } from './types';

export const createAgentLoopManager = (context: ManagerContext): AgentLoopManager => {
    const { get } = context;

    return {
        generateMessage: async (sessionId: string, content: string, options?: GenerateMessageOptions) => {
            // 直接调用chat-store中的generateMessage实现
            await get().generateMessage(sessionId, content, options as any);
        },

        regenerateMessage: async (sessionId: string, messageId: string) => {
            // 直接调用chat-store中的regenerateMessage实现
            await get().regenerateMessage(sessionId, messageId);
        },
    };
};

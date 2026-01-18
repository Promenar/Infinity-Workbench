/**
 * chat-store模块统一导出
 * 
 * 此文件提供模块化的chat-store功能访问
 * V2: 移除了未使用的占位模块 (agent-loop, streaming-handler)
 */

export { createMessageManager } from './message-manager';
export { createSessionManager } from './session-manager';
export { createApprovalManager } from './approval-manager';
export { createToolExecutor } from './tool-execution';

export type {
    ManagerContext,
    MessageManager,
    SessionManager,
    ApprovalManager,
    ToolExecutor,
    StateGetter,
    StateSetter,
    LoopStatus,
    GenerateMessageOptions,
} from './types';

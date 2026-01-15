/**
 * chat-store模块统一导出
 * 
 * 此文件提供模块化的chat-store功能访问
 * 当前为V1实现：模块已创建但chat-store暂未完全集成
 * 
 * 将来可以逐步将chat-store的实现迁移到这些模块中
 */

export { createMessageManager } from './message-manager';
export { createSessionManager } from './session-manager';
export { createApprovalManager } from './approval-manager';
export { createToolExecutor } from './tool-execution';
export { createAgentLoopManager } from './agent-loop';

export type {
    ManagerContext,
    MessageManager,
    SessionManager,
    ApprovalManager,
    ToolExecutor,
    AgentLoopManager,
    StateGetter,
    StateSetter,
    LoopStatus,
    GenerateMessageOptions,
} from './types';

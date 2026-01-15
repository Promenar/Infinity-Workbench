/**
 * 共享类型定义
 * 用于chat-store各模块间的通信和状态管理
 */

import type { ChatState } from '../chat-store';
import type { StoreApi } from 'zustand';
import {
    Message,
    Session,
    SessionId,
    TokenUsage,
    RagReference,
    RagProgress,
    RagMetadata,
    TaskState,
    InferenceParams
} from '../../types/chat';
import { ToolCall, ExecutionStep } from '../../types/skills';

// ===== 核心状态访问器 =====

export type StateGetter = () => ChatState;
export type StateSetter = StoreApi<ChatState>['setState'];

export interface ManagerContext {
    get: StateGetter;
    set: StateSetter;
}

// ===== 消息管理接口 =====

export interface MessageManager {
    addMessage: (sessionId: string, message: Message) => void;

    updateMessageContent: (
        sessionId: string,
        messageId: string,
        content: string,
        usage?: TokenUsage,
        reasoning?: string,
        citations?: any[],
        ragReferences?: RagReference[],
        ragReferencesLoading?: boolean,
        ragMetadata?: RagMetadata,
        thought_signature?: string,
        taskState?: TaskState
    ) => void;

    deleteMessage: (sessionId: string, messageId: string) => void;

    vectorizeMessage: (sessionId: string, messageId: string) => Promise<void>;

    updateMessageProgress: (
        sessionId: string,
        messageId: string,
        progress: RagProgress
    ) => void;

    updateMessageLayout: (
        sessionId: string,
        messageId: string,
        height: number
    ) => void;

    // Phase 4a: 新增方法
    setVectorizationStatus: (sessionId: string, messageIds: string[], status: 'processing' | 'success' | 'error') => void;
}

// ===== 会话管理接口 =====

export interface SessionManager {
    addSession: (session: Session) => void;

    updateSession: (id: SessionId, updates: Partial<Session>) => void;

    deleteSession: (id: SessionId) => void;

    getSession: (id: SessionId) => Session | undefined;

    updateSessionDraft: (sessionId: SessionId, draft: string | undefined) => void;

    toggleSessionPin: (sessionId: SessionId) => void;

    updateSessionInferenceParams: (id: SessionId, params: InferenceParams) => void;

    // Phase 4a: 新增辅助方法
    updateSessionTitle: (id: SessionId, title: string) => void;
    updateSessionPrompt: (id: SessionId, prompt: string | undefined) => void;
    updateSessionModel: (id: SessionId, modelId: string | undefined) => void;
    updateSessionOptions: (id: SessionId, options: any) => void;
    updateSessionScrollOffset: (id: SessionId, offset: number) => void;
    getSessionsByAgent: (agentId: string) => Session[];
    dismissActiveTask: (sessionId: SessionId) => void;
    setKGExtractionStatus: (sessionId: SessionId, isExtracting: boolean) => void;
}

// ===== 工具执行接口 =====

export interface ToolExecutor {
    executeTools: (
        sessionId: string,
        toolCalls: ToolCall[],
        targetMessageId?: string
    ) => Promise<void>;
}

// ===== 审批管理接口 =====

export type LoopStatus = 'idle' | 'running' | 'paused' | 'waiting_for_approval' | 'completed';

export interface ApprovalManager {
    setApprovalRequest: (
        sessionId: string,
        request: { toolName: string; args: any; reason: string } | undefined
    ) => void;

    resumeGeneration: (
        sessionId: string,
        approved?: boolean,
        intervention?: string
    ) => Promise<void>;

    setExecutionMode: (
        sessionId: string,
        mode: 'auto' | 'semi' | 'manual'
    ) => void;

    setLoopStatus: (sessionId: string, status: LoopStatus) => void;

    setPendingIntervention: (
        sessionId: string,
        intervention: string | undefined
    ) => void;
}

// ===== AgentLoop管理接口 =====

export interface GenerateMessageOptions {
    isResumption?: boolean;
    images?: any[];
}

export interface AgentLoopManager {
    generateMessage: (
        sessionId: string,
        content: string,
        options?: GenerateMessageOptions
    ) => Promise<void>;

    regenerateMessage: (
        sessionId: string,
        messageId: string
    ) => Promise<void>;
}

/**
 * 上下文Token估算工具
 * 
 * 用于估算当前会话的上下文长度，包括：
 * - 历史消息
 * - 系统提示词
 * - RAG检索内容
 * 
 * 设计原则：
 * - 性能优先：使用简单估算，避免每次渲染重新计算
 * - 提供缓存机制
 */

import { estimateTokens } from './token-counter';
import { findModelSpec } from '../../../lib/llm/model-utils';
import type { Session, Message, Agent } from '../../../types/chat';

/**
 * 上下文Token估算结果
 */
export interface ContextTokenEstimate {
    /** 历史消息token数 */
    messagesTokens: number;
    /** 系统提示词token数 */
    systemPromptTokens: number;
    /** RAG检索内容token数 */
    ragTokens: number;
    /** 总计 */
    totalTokens: number;
    /** 模型上下文上限 */
    contextLimit: number;
    /** 使用百分比 */
    usagePercent: number;
    /** 是否为估算值 */
    isEstimated: boolean;
}

/**
 * 估算消息列表的token数
 */
export function estimateMessagesTokens(messages: Message[]): number {
    let total = 0;
    for (const msg of messages) {
        // 如果消息已有token信息，使用实际值
        if (msg.tokens) {
            total += msg.tokens.input || 0;
            total += msg.tokens.output || 0;
        } else {
            // 否则估算内容
            total += estimateTokens(msg.content || '');
            // 估算reasoning内容（如有）
            if (msg.reasoning) {
                total += estimateTokens(msg.reasoning);
            }
        }
    }
    return total;
}

/**
 * 估算系统提示词token数
 */
export function estimateSystemPromptTokens(
    agent: Agent | null,
    customPrompt?: string
): number {
    let total = 0;

    if (agent?.systemPrompt) {
        total += estimateTokens(agent.systemPrompt);
    }

    if (customPrompt) {
        total += estimateTokens(customPrompt);
    }

    // 预留工具描述和系统元数据的token
    // 典型工具描述约 500-2000 tokens
    const toolOverhead = 1000;
    total += toolOverhead;

    return total;
}

/**
 * 估算RAG检索内容token数
 * 从最近的助手消息中提取RAG引用内容
 */
export function estimateRagTokens(messages: Message[]): number {
    let total = 0;

    // 从最近的助手消息中获取RAG引用
    for (let i = messages.length - 1; i >= 0; i--) {
        const msg = messages[i];
        if (msg.role === 'assistant' && msg.ragReferences?.length) {
            for (const ref of msg.ragReferences) {
                total += estimateTokens(ref.content || '');
            }
            break; // 只计算最近的RAG内容
        }
    }

    return total;
}

/**
 * 获取模型上下文上限
 * 优先级：模型配置 > MODEL_SPECS默认值 > 兜底值
 */
export function getContextLimit(
    modelId: string | undefined,
    modelConfigContextLength?: number
): number {
    // 1. 使用用户配置的上下文上限
    if (modelConfigContextLength && modelConfigContextLength > 0) {
        return modelConfigContextLength;
    }

    // 2. 从MODEL_SPECS获取默认值
    if (modelId) {
        const spec = findModelSpec(modelId);
        if (spec?.contextLength) {
            return spec.contextLength;
        }
    }

    // 3. 兜底值
    return 4096;
}

/**
 * 估算当前会话的上下文Token使用情况
 */
export function estimateContextTokens(
    session: Session | null | undefined,
    agent: Agent | null,
    modelConfigContextLength?: number
): ContextTokenEstimate {
    // 默认值
    const defaultResult: ContextTokenEstimate = {
        messagesTokens: 0,
        systemPromptTokens: 0,
        ragTokens: 0,
        totalTokens: 0,
        contextLimit: 4096,
        usagePercent: 0,
        isEstimated: true,
    };

    if (!session) {
        return defaultResult;
    }

    const messages = session.messages || [];
    const modelId = session.modelId || agent?.defaultModel;

    // 估算各部分token
    const messagesTokens = estimateMessagesTokens(messages);
    const systemPromptTokens = estimateSystemPromptTokens(agent, session.customPrompt);
    const ragTokens = estimateRagTokens(messages);
    const totalTokens = messagesTokens + systemPromptTokens + ragTokens;

    // 获取上下文上限
    const contextLimit = getContextLimit(modelId, modelConfigContextLength);

    // 计算使用百分比
    const usagePercent = contextLimit > 0 ? Math.min(100, (totalTokens / contextLimit) * 100) : 0;

    return {
        messagesTokens,
        systemPromptTokens,
        ragTokens,
        totalTokens,
        contextLimit,
        usagePercent,
        isEstimated: true,
    };
}

/**
 * 格式化上下文使用情况显示
 * 返回如 "12.5K / 128K" 或百分比形式
 */
export function formatContextUsage(
    currentTokens: number,
    limit: number
): { display: string; displayShort: string; color: string } {
    const percent = limit > 0 ? (currentTokens / limit) * 100 : 0;

    // 格式化数字
    const formatNum = (n: number): string => {
        if (n >= 1000000) {
            return (n / 1000000).toFixed(1) + 'M';
        } else if (n >= 1000) {
            return (n / 1000).toFixed(1) + 'K';
        }
        return n.toString();
    };

    const display = `${formatNum(currentTokens)} / ${formatNum(limit)}`;
    const displayShort = `${percent.toFixed(1)}%`;

    // 根据使用比例决定颜色
    let color: string;
    if (percent < 50) {
        color = '#22c55e'; // 绿色
    } else if (percent < 80) {
        color = '#f59e0b'; // 黄色
    } else {
        color = '#ef4444'; // 红色
    }

    return { display, displayShort, color };
}

/**
 * 简化的上下文估算Hook返回类型
 */
export interface ContextUsageInfo {
    /** 当前上下文token数 */
    currentTokens: number;
    /** 上下文上限 */
    contextLimit: number;
    /** 使用百分比 (0-100) */
    usagePercent: number;
    /** 格式化显示文本 */
    display: string;
    /** 短格式显示 */
    displayShort: string;
    /** 状态颜色 */
    color: string;
    /** 详细信息 */
    details: ContextTokenEstimate;
}

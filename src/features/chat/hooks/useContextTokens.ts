/**
 * useContextTokens Hook
 * 
 * 用于获取当前会话的上下文Token使用情况
 * 包含性能优化：memo和缓存机制
 */

import { useMemo } from 'react';
import { useChatStore } from '../../../store/chat-store';
import { useAgentStore } from '../../../store/agent-store';
import { useApiStore } from '../../../store/api-store';
import {
    estimateContextTokens,
    formatContextUsage,
    getContextLimit,
    type ContextTokenEstimate,
    type ContextUsageInfo,
} from '../utils/context-token-estimator';

/**
 * 获取上下文Token使用情况的Hook
 * 
 * @param sessionId 会话ID
 * @returns ContextUsageInfo 上下文使用信息
 */
export function useContextTokens(sessionId: string): ContextUsageInfo {
    // 获取会话数据
    const session = useChatStore((state) =>
        state.sessions.find((s) => s.id === sessionId)
    );

    // 获取Agent数据
    const agentId = session?.agentId;
    const agent = useAgentStore((state) => {
        if (!agentId) return null;
        return state.agents.find((a) => a.id === agentId) || null;
    });

    // 获取模型ID
    const modelId = session?.modelId || agent?.defaultModel;

    // 获取模型配置中的上下文长度
    const modelConfigContextLength = useApiStore((state) => {
        if (!modelId) return undefined;

        // 遍历所有provider查找模型配置
        for (const provider of state.providers) {
            const model = provider.models.find(
                (m) => m.id === modelId || m.uuid === modelId
            );
            if (model?.contextLength) {
                return model.contextLength;
            }
        }
        return undefined;
    });

    // 计算上下文估算（memo优化）
    const estimate: ContextTokenEstimate = useMemo(() => {
        return estimateContextTokens(session, agent, modelConfigContextLength);
    }, [session, agent, modelConfigContextLength]);

    // 格式化显示（memo优化）
    const formatted = useMemo(() => {
        return formatContextUsage(estimate.totalTokens, estimate.contextLimit);
    }, [estimate.totalTokens, estimate.contextLimit]);

    return {
        currentTokens: estimate.totalTokens,
        contextLimit: estimate.contextLimit,
        usagePercent: estimate.usagePercent,
        display: formatted.display,
        displayShort: formatted.displayShort,
        color: formatted.color,
        details: estimate,
    };
}

/**
 * 轻量级Hook：仅获取上下文上限
 * 用于不需要完整估算的场景
 */
export function useContextLimit(modelId?: string): number {
    const modelConfigContextLength = useApiStore((state) => {
        if (!modelId) return undefined;

        for (const provider of state.providers) {
            const model = provider.models.find(
                (m) => m.id === modelId || m.uuid === modelId
            );
            if (model?.contextLength) {
                return model.contextLength;
            }
        }
        return undefined;
    });

    return useMemo(() => {
        return getContextLimit(modelId, modelConfigContextLength);
    }, [modelId, modelConfigContextLength]);
}

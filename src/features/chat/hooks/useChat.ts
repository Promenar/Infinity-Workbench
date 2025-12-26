import { useState, useCallback, useRef } from 'react';
import { useChatStore } from '../../../store/chat-store';
import { useAgentStore } from '../../../store/agent-store';
import { useApiStore } from '../../../store/api-store';
import { createLlmClient } from '../../../lib/llm/factory';
import { Message } from '../../../types/chat';
import { trimContext } from '../utils/context-manager';

export function useChat(sessionId: string) {
    const { getSession, addMessage, updateSession } = useChatStore();
    const { getAgent } = useAgentStore();
    const { providers } = useApiStore();
    const session = getSession(sessionId);
    const [loading, setLoading] = useState(false);

    const messages = session?.messages || [];

    const sendMessage = useCallback(async (content: string) => {
        if (!content.trim() || !sessionId || !session) return;

        // 1. 获取 Agent 和其关联的 Provider
        const agent = getAgent(session.agentId);
        if (!agent) {
            console.error('Agent not found');
            return;
        }

        const modelId = session.modelId || agent.defaultModel;

        // 寻找能提供该模型的提供商
        // 1. 优先通过 UUID 匹配 (精确匹配)
        let provider = providers.find(p => p.enabled && p.models.some(m => m.uuid === modelId));
        let modelConfig = provider?.models.find(m => m.uuid === modelId);

        // 2. 如果 UUID 没找到（可能是预设或配置重置），则通过 ID 匹配
        if (!provider) {
            provider = providers.find(p => p.enabled && p.models.some(m => m.id === modelId));
            modelConfig = provider?.models.find(m => m.id === modelId);
        }

        if (!provider || !modelConfig) {
            console.error('No enabled provider found for model:', modelId);
            return;
        }

        // 2. 添加用户消息
        const userMsg: Message = {
            id: `msg_${Date.now()}`,
            role: 'user',
            content,
            timestamp: Date.now(),
        };
        addMessage(sessionId, userMsg);
        setLoading(true);

        // 3. 准备助手消息占位
        const assistantMsgId = `msg_ai_${Date.now()}`;
        const assistantMsg: Message = {
            id: assistantMsgId,
            role: 'assistant',
            content: '',
            timestamp: Date.now(),
            modelId: agent.defaultModel
        };
        addMessage(sessionId, assistantMsg);

        try {
            // 4. 初始化客户端
            if (!modelConfig) throw new Error(`Model ${agent.defaultModel} not found`);

            const extendedConfig = {
                ...modelConfig,
                provider: provider.type,
                apiKey: provider.apiKey,
                baseUrl: provider.baseUrl,
                temperature: agent.params.temperature,
                vertexProject: provider.vertexProject,
                vertexLocation: provider.vertexLocation,
                vertexKeyJson: provider.vertexKeyJson
            };

            const client = createLlmClient(extendedConfig as any);

            // 5. 准备上下文（应用滑动窗口）
            const history = messages.map(m => ({ role: m.role as any, content: m.content }));
            const context = trimContext([
                { role: 'system', content: agent.systemPrompt } as any,
                ...history as any,
                { role: 'user', content } as any
            ]);

            // 6. 调用流式对话
            let accumulatedContent = '';
            await client.streamChat(
                context,
                (chunk) => {
                    accumulatedContent += chunk;
                    // 实时更新 Store 中的助手消息
                    updateSession(sessionId, {
                        messages: getChatStore().sessions.find(s => s.id === sessionId)?.messages.map(m =>
                            m.id === assistantMsgId ? { ...m, content: accumulatedContent } : m
                        )
                    });
                },
                (error) => {
                    throw error; // 将错误抛出给外部 catch 处理
                }
            );

            // 7. 首条消息自动更新会话标题
            if (messages.length === 0) {
                updateSession(sessionId, { title: content.substring(0, 30) + (content.length > 30 ? '...' : '') });
            }

        } catch (error) {
            console.error('Chat error:', error);
            // 这里可以添加一条错误提示消息
            const errorMsg = (error as Error).message;
            updateSession(sessionId, {
                messages: getChatStore().sessions.find(s => s.id === sessionId)?.messages.map(m =>
                    m.id === assistantMsgId ? { ...m, content: `Error: ${errorMsg}` } : m
                )
            });
        } finally {
            setLoading(false);
        }
    }, [sessionId, session, session?.modelId, messages, getAgent, providers, addMessage, updateSession]);

    return {
        messages,
        loading,
        sendMessage,
    };
}

// 辅助方法，用于在闭包中获取最新的 store 状态（避免复杂的依赖)
function getChatStore() {
    return useChatStore.getState();
}

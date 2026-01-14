import { Message } from '../../types/chat';
import { ToolCall } from '../../types/skills';

/**
 * ChatMessage接口（LLM Client使用的格式）
 */
export interface ChatMessage {
    role: 'system' | 'user' | 'assistant' | 'tool';
    content: string | any[];
    reasoning?: string;
    name?: string;
    tool_call_id?: string;
    tool_calls?: ToolCall[];
    thought_signature?: string;
}

/**
 * MessageFormatter接口
 * 
 * 职责：处理各Provider历史记录构建的差异
 * - 不同Provider对reasoning_content的支持不同
 * - tool_calls格式可能有细微差异
 * - 某些Provider需要特殊的消息过滤
 */
export interface MessageFormatter {
    /**
     * 格式化消息历史为Provider特定格式
     * 
     * @param messages 内部消息格式
     * @param contextWindow 上下文窗口大小（token数）
     * @returns Provider特定的消息数组
     */
    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[];

    /**
     * 判断是否应该从历史中移除"悬挂的"tool_calls
     * （即assistant消息包含tool_calls但后面没有对应的tool结果）
     * 
     * @param message 要检查的消息
     * @returns 是否应该剥离tool_calls
     */
    shouldStripHangingToolCalls(message: Message): boolean;

    /**
     * 判断是否支持reasoning_content回传
     * 不同Provider对此的支持不同：
     * - OpenAI: 仅o1系列输出，不支持回传
     * - DeepSeek: 支持输出和回传
     * - GLM/KIMI: 基本不支持
     */
    supportsReasoningInHistory(): boolean;
}

/**
 * 基础Formatter实现
 * 其他Formatter可继承此类
 */
export abstract class BaseMessageFormatter implements MessageFormatter {
    abstract formatHistory(messages: Message[], contextWindow?: number): ChatMessage[];

    shouldStripHangingToolCalls(message: Message): boolean {
        // 默认：如果存在tool_calls但后续没有tool消息，则剥离
        return false;
    }

    supportsReasoningInHistory(): boolean {
        // 默认：不支持
        return false;
    }

    /**
     * 通用辅助：转换内部Message为ChatMessage基础格式
     */
    protected convertMessage(message: Message): ChatMessage {
        return {
            role: message.role as any,
            content: message.content,
            reasoning: (message as any).reasoning,
            name: (message as any).name,
            tool_call_id: (message as any).tool_call_id,
            tool_calls: (message as any).tool_calls,
            thought_signature: (message as any).thought_signature,
        };
    }
}

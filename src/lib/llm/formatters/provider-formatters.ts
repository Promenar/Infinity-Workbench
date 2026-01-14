import { Message } from '../../../types/chat';
import {
    MessageFormatter,
    BaseMessageFormatter,
    ChatMessage
} from '../message-formatter';

/**
 * OpenAI Formatter
 * 
 * 特性：
 * - 严格的tool_calls完整性校验
 * - 不支持reasoning_content回传（仅o1输出）
 * - 标准的OpenAI消息格式
 */
export class OpenAIFormatter extends BaseMessageFormatter {
    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[] {
        const formatted: ChatMessage[] = [];

        for (const msg of messages) {
            const chatMsg = this.convertMessage(msg);

            // OpenAI不支持reasoning_content回传，移除
            delete chatMsg.reasoning;

            formatted.push(chatMsg);
        }

        return formatted;
    }

    shouldStripHangingToolCalls(message: Message): boolean {
        // OpenAI严格要求tool_calls后必须跟tool结果
        return true;
    }

    supportsReasoningInHistory(): boolean {
        return false;
    }
}

/**
 * DeepSeek Formatter
 * 
 * 特性：
 * - 支持reasoning_content输出和回传
 * - 支持<think>标签（已在网络层处理）
 * - 兼容OpenAI格式但更宽容
 */
export class DeepSeekFormatter extends BaseMessageFormatter {
    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[] {
        const formatted: ChatMessage[] = [];

        for (const msg of messages) {
            const chatMsg = this.convertMessage(msg);

            // DeepSeek支持reasoning回传
            // 保留reasoning字段

            formatted.push(chatMsg);
        }

        return formatted;
    }

    shouldStripHangingToolCalls(message: Message): boolean {
        // DeepSeek相对宽容，但仍建议移除悬挂调用
        return true;
    }

    supportsReasoningInHistory(): boolean {
        return true;
    }
}

/**
 * GLM (智谱AI) Formatter
 * 
 * 特性：
 * - 基本兼容OpenAI
 * - 可能输出XML工具调用（已由StreamParser清理）
 * - 不支持reasoning回传
 */
export class GLMFormatter extends BaseMessageFormatter {
    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[] {
        const formatted: ChatMessage[] = [];

        for (const msg of messages) {
            const chatMsg = this.convertMessage(msg);

            // GLM不支持reasoning回传
            delete chatMsg.reasoning;

            formatted.push(chatMsg);
        }

        return formatted;
    }

    shouldStripHangingToolCalls(message: Message): boolean {
        return true;
    }

    supportsReasoningInHistory(): boolean {
        return false;
    }
}

/**
 * Moonshot (KIMI) Formatter
 * 
 * 特性：
 * - 基本兼容OpenAI
 * - 部分模型支持思维链
 * - 相对稳定的工具调用
 */
export class MoonshotFormatter extends BaseMessageFormatter {
    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[] {
        const formatted: ChatMessage[] = [];

        for (const msg of messages) {
            const chatMsg = this.convertMessage(msg);

            // KIMI基本不支持reasoning回传
            delete chatMsg.reasoning;

            formatted.push(chatMsg);
        }

        return formatted;
    }

    shouldStripHangingToolCalls(message: Message): boolean {
        return true;
    }

    supportsReasoningInHistory(): boolean {
        return false;
    }
}

/**
 * Gemini Formatter
 * 
 * 特性：
 * - 使用不同的消息格式（已在GeminiClient处理）
 * - 支持原生grounding和search
 * - 本Formatter主要用于标准化接口
 */
export class GeminiFormatter extends BaseMessageFormatter {
    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[] {
        // Gemini的消息格式转换在GeminiClient中完成
        // 这里返回标准格式，实际转换在网络层
        return messages.map(m => this.convertMessage(m));
    }

    shouldStripHangingToolCalls(message: Message): boolean {
        // Gemini有自己的function_call处理，不需要额外剥离
        return false;
    }

    supportsReasoningInHistory(): boolean {
        // Gemini Thinking models使用thought_signature
        return false;
    }
}

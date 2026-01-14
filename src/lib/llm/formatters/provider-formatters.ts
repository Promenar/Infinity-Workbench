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
 * - 支持模型特定的System Prompt增强
 */
export class GeminiFormatter extends BaseMessageFormatter {
    private modelName: string;

    constructor(modelName?: string) {
        super();
        this.modelName = modelName || '';
    }

    formatHistory(messages: Message[], contextWindow?: number): ChatMessage[] {
        // Gemini的消息格式转换在GeminiClient中完成
        const formatted = messages.map(m => this.convertMessage(m));

        // 🔑 为system消息注入模型特定指引
        const systemMsg = formatted.find(m => m.role === 'system');
        if (systemMsg && typeof systemMsg.content === 'string') {
            systemMsg.content = this.enhanceSystemPrompt(systemMsg.content);
        }

        return formatted;
    }

    shouldStripHangingToolCalls(message: Message): boolean {
        // Gemini有自己的function_call处理，不需要额外剥离
        return false;
    }

    supportsReasoningInHistory(): boolean {
        // Gemini Thinking models使用thought_signature
        return false;
    }

    /**
     * 增强System Prompt：针对不同Gemini模型注入特定指引
     */
    private enhanceSystemPrompt(originalPrompt: string): string {
        let enhanced = originalPrompt;

        // 🔑 针对Pro模型的特殊指引（Vision Web Reasoning需要更详细的格式规范）
        if (this.modelName.toLowerCase().includes('pro')) {
            enhanced += `

## 📋 manage_task 工具使用规范

创建任务时，步骤**必须**使用详细对象格式，包含 id、description、status 三个字段：

✅ 正确示例：
{
  "action": "create",
  "title": "任务标题",
  "steps": [
    {
      "id": "search_kb",
      "description": "查询全局知识库关于XX的信息",
      "status": "pending"
    },
    {
      "id": "summarize",
      "description": "总结信息并弹出Toast通知",
      "status": "pending"
    }
  ]
}

❌ 错误示例（避免使用简化格式）：
{
  "steps": ["动作1", "动作2"]
}
{
  "steps": ["查询知识库", "弹出通知"]
}

### 步骤命名规则
- id: 使用英文蛇形命名，如 search_kb、summarize、show_toast
- description: 详细描述具体操作，避免"动作1"、"动作2"等抽象名称
- status: 初始创建时统一使用 "pending"

### 步骤更新
完成每个步骤后，使用 update action 更新状态：
{
  "action": "update",
  "steps": [{"id": "search_kb", "status": "completed"}]
}
`;
        }

        // Flash模型已经表现良好，无需额外指引

        return enhanced;
    }
}

import { z } from 'zod';
import { Message } from './chat';

/**
 * 技能 (Skill) 定义接口
 * 所有工具必须实现此接口以被 SkillRegistry 注册和调用
 */
export interface Skill {
    id: string;          // 唯一标识符 (e.g., 'generate_image')
    name: string;        // 模型/UI可见名称 (e.g., 'Generate Image')
    description: string; // Prompt 描述 (决定模型何时调用)
    schema: z.ZodSchema<any>; // 参数校验 Schema (Zod)

    // 执行逻辑
    execute: (params: any, context: SkillContext) => Promise<SkillResult>;
}

/**
 * 技能执行上下文
 * 传递给 execute 方法，包含系统依赖
 */
export interface SkillContext {
    modelId?: string;    // 当前调用的模型 ID
    sessionId?: string;  // 当前会话 ID
    agentId?: string;    // Agent ID
    // 未来可以扩展更多上下文，如 User ID, Location 等
}

/**
 * 技能执行结果
 * 统一返回格式，便于 UI 渲染和历史记录
 */
export interface SkillResult {
    id: string;          // 对应 ToolCall.id
    content: string;     // 文本结果 (Markdown)
    status: 'success' | 'error';
    data?: any;          // 结构化数据 (可选，供 UI 组件使用)
    images?: string[];   // 生成的图片路径 (可选)
}

export type ToolResult = SkillResult;

/**
 * 工具调用请求 (从 LLM 解析而来)
 */
export interface ToolCall {
    id: string;          // 调用 ID (OpenAI tool_call_id)
    name: string;        // 调用的工具名称 (Skill.id)
    arguments: any;      // 解析后的参数对象
}

/**
 * Step in the execution timeline (Thinking Process or Tool Call)
 */
export interface ExecutionStep {
    id: string;
    type: 'thinking' | 'tool_call' | 'tool_result' | 'error' | 'plan_item' | 'intervention_required' | 'intervention_result';
    content?: string; // Markdown content or JSON string
    toolName?: string;
    toolArgs?: any;
    toolCallId?: string; // 🔑 Added for precise context reconstruction
    data?: any; // Structured data from SkillResult
    timestamp: number;
}

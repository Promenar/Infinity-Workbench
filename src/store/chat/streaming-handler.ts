/**
 * 流式处理模块
 * 负责 LLM 流式响应解析、工具调用检测、节流更新
 * Phase 5: 从 chat-store.ts generateMessage 中提取
 */

import { ToolCall } from '../../types/skills';
import { StreamParser } from '../../lib/llm/stream-parser';

// ============ 类型定义 ============

export interface StreamChunk {
    content?: string;
    reasoning?: string;
    toolCalls?: ToolCall[];
    usage?: { input: number; output: number; total: number };
    thought_signature?: string;
    citations?: { title: string; url: string; source?: string }[];
}

export interface StreamState {
    accumulatedContent: string;
    accumulatedReasoning: string;
    toolCalls: ToolCall[];
    usage?: { input: number; output: number; total: number };
    thought_signature?: string;
    citations: { title: string; url: string; source?: string }[];
    planParsed: boolean;
    contentBuffer: string[];
}

export interface ThrottleConfig {
    contentInterval: number;  // 默认 200ms
    timelineInterval: number; // 默认 500ms
}

// ============ 工具函数 ============

/**
 * 创建初始流状态
 */
export function createStreamState(): StreamState {
    return {
        accumulatedContent: '',
        accumulatedReasoning: '',
        toolCalls: [],
        usage: undefined,
        thought_signature: undefined,
        citations: [],
        planParsed: false,
        contentBuffer: [],
    };
}

/**
 * 创建节流更新器
 */
export function createThrottledUpdater(config: ThrottleConfig) {
    let lastContentUpdateTime = 0;
    let lastTimelineUpdateTime = 0;

    return {
        shouldUpdateContent: (now: number): boolean => {
            if (now - lastContentUpdateTime > config.contentInterval) {
                lastContentUpdateTime = now;
                return true;
            }
            return false;
        },
        shouldUpdateTimeline: (now: number): boolean => {
            if (now - lastTimelineUpdateTime > config.timelineInterval) {
                lastTimelineUpdateTime = now;
                return true;
            }
            return false;
        },
        reset: () => {
            lastContentUpdateTime = 0;
            lastTimelineUpdateTime = 0;
        },
    };
}

/**
 * 幂等性更新工具调用列表
 * 确保相同 ID 的工具调用仅保留最新版本
 */
export function mergeToolCalls(existing: ToolCall[], incoming: ToolCall[]): ToolCall[] {
    const result = [...existing];

    incoming.forEach(tc => {
        const idx = result.findIndex(e => e.id === tc.id);
        if (idx > -1) {
            result[idx] = tc;
        } else {
            result.push(tc);
        }
    });

    return result;
}

/**
 * 从文本中提取 XML 格式的工具调用 (DeepSeek/Kimi 兼容)
 */
export function extractXmlToolCalls(content: string): ToolCall[] {
    const extractedCalls: ToolCall[] = [];

    const addCall = (name: string, args: any) => {
        if (!extractedCalls.find(c => c.name === name && JSON.stringify(c.arguments) === JSON.stringify(args))) {
            extractedCalls.push({
                id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                name: name,
                arguments: args
            });
        }
    };

    // Strategy 1: XML-based with JSON content
    // <tool_calls>[JSON]</tool_calls> or <tool_code>[JSON]</tool_code>
    const xmlJsonRegex = /<(?:tool_code|tool_calls|tools)>([\s\S]*?)<\/(?:tool_code|tool_calls|tools)>/gi;
    let xmlMatch;
    while ((xmlMatch = xmlJsonRegex.exec(content)) !== null) {
        const inner = xmlMatch[1].trim();
        try {
            const parsed = JSON.parse(inner);
            if (Array.isArray(parsed)) {
                parsed.forEach(c => {
                    if (c.function?.name) addCall(c.function.name, c.function.arguments);
                    else if (c.name && c.arguments) addCall(c.name, c.arguments);
                    else if (c.id && c.arguments) {
                        addCall(c.function?.name || c.id || c.name, c.arguments || c.parameters);
                    }
                });
            } else if (typeof parsed === 'object') {
                if (parsed.name || parsed.function?.name) {
                    addCall(parsed.function?.name || parsed.name, parsed.function?.arguments || parsed.arguments);
                }
            }
        } catch (e) { /* Not JSON */ }
    }

    // Strategy 2: <call tool="name"><tool_input>JSON</tool_input></call>
    const callToolRegex = /<call\s+tool="([^"]+)"[^>]*>[\s\S]*?<tool_input>([\s\S]*?)<\/tool_input>[\s\S]*?<\/call>/gi;
    let callMatch;
    while ((callMatch = callToolRegex.exec(content)) !== null) {
        const name = callMatch[1];
        const argsStr = callMatch[2].trim();
        try {
            const args = JSON.parse(argsStr);
            addCall(name, args);
        } catch (e) {
            addCall(name, { raw: argsStr });
        }
    }

    // Strategy 3: <tool_call><function_name>name</function_name><parameters>...</parameters></tool_call>
    const toolCallTagRegex = /<tool_call>([\s\S]*?)<\/tool_call>/gi;
    let tagMatch;
    while ((tagMatch = toolCallTagRegex.exec(content)) !== null) {
        const inner = tagMatch[1];
        const nameMatch = inner.match(/<function_name>([\s\S]*?)<\/function_name>/i);
        const paramsMatch = inner.match(/<parameters>([\s\S]*?)<\/parameters>/i);
        if (nameMatch) {
            const name = nameMatch[1].trim();
            let args: any = {};
            if (paramsMatch) {
                try {
                    args = JSON.parse(paramsMatch[1].trim());
                } catch (e) {
                    // Try to extract key-value pairs
                    const kvRegex = /<(\w+)>([\s\S]*?)<\/\1>/g;
                    let kv;
                    while ((kv = kvRegex.exec(paramsMatch[1])) !== null) {
                        args[kv[1]] = kv[2].trim();
                    }
                }
            }
            addCall(name, args);
        }
    }

    return extractedCalls;
}

/**
 * 从文本中提取 ReAct 格式的工具调用
 * Action: tool_name
 * Action Input: {"key": "value"}
 */
export function extractReActToolCalls(content: string): ToolCall[] {
    const extractedCalls: ToolCall[] = [];

    const reactRegex = /Action:\s*(\w+)\s*\n\s*Action Input:\s*(\{[\s\S]*?\})/gi;
    let match;
    while ((match = reactRegex.exec(content)) !== null) {
        const name = match[1];
        try {
            const args = JSON.parse(match[2]);
            if (!extractedCalls.find(c => c.name === name && JSON.stringify(c.arguments) === JSON.stringify(args))) {
                extractedCalls.push({
                    id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                    name,
                    arguments: args
                });
            }
        } catch (e) { /* Invalid JSON */ }
    }

    return extractedCalls;
}

/**
 * 从文本中提取 Gemini call:func(args) 格式的工具调用
 */
export function extractGeminiCallPattern(content: string): ToolCall[] {
    const extractedCalls: ToolCall[] = [];

    const geminiCallRegex = /call:(\w+)\((\{[\s\S]*?\})\)/gi;
    let match;
    while ((match = geminiCallRegex.exec(content)) !== null) {
        const name = match[1];
        try {
            const args = JSON.parse(match[2]);
            extractedCalls.push({
                id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                name,
                arguments: args
            });
        } catch (e) { /* Invalid JSON */ }
    }

    return extractedCalls;
}

/**
 * 综合工具调用提取（所有模式）
 */
export function extractAllToolCalls(content: string): ToolCall[] {
    const xmlCalls = extractXmlToolCalls(content);
    const reactCalls = extractReActToolCalls(content);
    const geminiCalls = extractGeminiCallPattern(content);

    // 合并去重
    const allCalls = [...xmlCalls, ...reactCalls, ...geminiCalls];
    const uniqueCalls: ToolCall[] = [];

    allCalls.forEach(call => {
        if (!uniqueCalls.find(c => c.name === call.name && JSON.stringify(c.arguments) === JSON.stringify(call.arguments))) {
            uniqueCalls.push(call);
        }
    });

    return uniqueCalls;
}

/**
 * 清理 Plan 标签
 */
export function stripPlanTags(content: string): string {
    return content.replace(/<plan>[\s\S]*?<\/plan>/gi, '').trim();
}

/**
 * 创建 StreamParser 实例
 */
export function createStreamParser(): StreamParser {
    return new StreamParser();
}

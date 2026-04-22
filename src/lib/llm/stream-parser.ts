import { ToolCall } from '../../types/skills';
import { ProviderType } from './response-normalizer';
import { LLM_STRUCTURED_BLOCK_REGEX } from './patterns';

export interface ParseResult {
    content: string;
    reasoning: string;
    toolCalls?: ToolCall[];
    plan?: any[];
}

type ParserState = 'IDLE' | 'IN_TOOL_XML' | 'IN_PLAN';

/**
 * StreamParser
 * 
 * A stateful, incremental parser for LLM output streams.
 * Optimized to handle mixed content (text, tool calls, plans) 
 * without O(N^2) regex matching on the entire history.
 * 
 * NOTE: Thinking/reasoning tag extraction is NO LONGER handled here.
 * All thinking tag detection is now handled by ThinkingDetector at the Provider layer.
 * StreamParser only handles: tool_call XML blocks, plan blocks, and code fences.
 */
export class StreamParser {
    private buffer: string = '';
    private state: ParserState = 'IDLE';
    private provider: ProviderType;

    // Buffers for specific blocks
    private startTag: string = ''; // Stores which tag started the block

    // 🧠 Context Awareness State
    private inFence: boolean = false;
    private inInlineCode: boolean = false;
    private fenceMarker: string = ''; // e.g. "```" or "`"

    constructor(provider: ProviderType = 'openai') {
        this.provider = provider;
    }

    process(chunk: string): ParseResult {
        let outputContent = '';
        const outputReasoning = ''; // Deprecated: reasoning is now handled by ThinkingDetector at Provider layer
        const outputToolCalls: ToolCall[] = [];
        let outputPlan: any[] | undefined;

        this.buffer += chunk;

        // Process buffer until we can't anymore
        let loopGuard = 0;
        while (loopGuard++ < 1000) {
            // Break if buffer empty
            if (this.buffer.length === 0) break;

            // =================================================================================
            // State 1: IDLE (Scanning for Tags OR Code Starts)
            // =================================================================================
            if (this.state === 'IDLE') {

                // 🛡️ SUB-STATE: Inside Code Block (Fence)
                if (this.inFence) {
                    // Look for the closing fence
                    // It must allow newlines before it (usually)
                    // We just look for the marker string literally
                    const closeIdx = this.buffer.indexOf(this.fenceMarker);

                    if (closeIdx !== -1) {
                        // Found it!
                        // Consume everything up to and including the marker as CONTENT
                        const segment = this.buffer.slice(0, closeIdx + this.fenceMarker.length);
                        outputContent += segment;
                        this.buffer = this.buffer.slice(closeIdx + this.fenceMarker.length);

                        // Reset state
                        this.inFence = false;
                        this.fenceMarker = '';
                        continue;
                    } else {
                        // Not found yet.
                        // Safe optimization: consume strictly everything except the last few chars 
                        // that *might* form the start of the marker.
                        // But finding the marker is fast.
                        // We must NOT consume the whole buffer unless we are sure the marker isn't split.
                        // Let's keep a small window or just keep the buffer?
                        // If buffer gets too large (e.g. > 1KB) and no fence, we can dump some?
                        // For safety/simplicity, we just output everything but leave a tail overlapping window?
                        // Actually, if we just break, the next chunk appends.
                        // To allow streaming output of the code block:
                        if (this.buffer.length > 20) {
                            const safeLen = this.buffer.length - 10; // Keep 10 chars for safety overlap
                            outputContent += this.buffer.slice(0, safeLen);
                            this.buffer = this.buffer.slice(safeLen);
                        }
                        break;
                    }
                }

                // 🛡️ SUB-STATE: Inside Inline Code
                if (this.inInlineCode) {
                    const closeIdx = this.buffer.indexOf(this.fenceMarker); // usually "`" or "``"
                    if (closeIdx !== -1) {
                        outputContent += this.buffer.slice(0, closeIdx + this.fenceMarker.length);
                        this.buffer = this.buffer.slice(closeIdx + this.fenceMarker.length);
                        this.inInlineCode = false;
                        this.fenceMarker = '';
                        continue;
                    } else {
                        // Streaming optimization
                        if (this.buffer.length > 20) {
                            const safeLen = this.buffer.length - 10;
                            outputContent += this.buffer.slice(0, safeLen);
                            this.buffer = this.buffer.slice(safeLen);
                        }
                        break;
                    }
                }

                // 🛡️ NORMAL: Look for Tags OR Code Starts
                // We must find which one happens FIRST.

                // 1. Tag Regex - only tool and plan tags (think/thought removed, handled by ThinkingDetector)
                const tagRegex = /<(?:(plan|tool_code|tool_calls|tools|tool_call|call)(?=[\s>]))/i;
                const tagMatch = tagRegex.exec(this.buffer);

                // 2. Code Start Regex
                // Matches "```", "~~~" (blocks) OR "`" (inline)
                // Note: We need to capture the exact marker to match closing.
                // We prefer "```" over "`" if both start at same index.
                const codeRegex = /(`{3,}|~{3,}|`{1,2})/;
                const codeMatch = codeRegex.exec(this.buffer);

                const tagIdx = tagMatch ? tagMatch.index : -1;
                const codeIdx = codeMatch ? codeMatch.index : -1;

                // Decision: Who wins?
                let winner: 'tag' | 'code' | 'none' = 'none';

                if (tagIdx !== -1 && codeIdx !== -1) {
                    winner = tagIdx < codeIdx ? 'tag' : 'code';
                } else if (tagIdx !== -1) {
                    winner = 'tag';
                } else if (codeIdx !== -1) {
                    winner = 'code';
                }

                // -- NO MATCH --
                if (winner === 'none') {
                    // Check for partials (trailing '<' or trailing '`')
                    // If buffer ends with '<' or '`', wait for more.
                    // Otherwise output content.

                    const lastOpen = this.buffer.lastIndexOf('<');
                    const lastBacktick = this.buffer.lastIndexOf('`');
                    const lastTilde = this.buffer.lastIndexOf('~');

                    const dangerZone = Math.max(lastOpen, lastBacktick, lastTilde);

                    if (dangerZone !== -1 && dangerZone > this.buffer.length - 10) {
                        // We are close to end, keep safe buffer
                        outputContent += this.buffer.slice(0, dangerZone);
                        this.buffer = this.buffer.slice(dangerZone);
                    } else {
                        outputContent += this.buffer;
                        this.buffer = '';
                    }
                    break;
                }

                // -- CODE WINNER --
                if (winner === 'code' && codeMatch) {
                    // Output text before code
                    outputContent += this.buffer.slice(0, codeIdx);
                    this.buffer = this.buffer.slice(codeIdx);

                    // Determine type
                    const marker = codeMatch[0];
                    if (marker.length >= 3) {
                        this.inFence = true;
                    } else {
                        this.inInlineCode = true;
                    }
                    this.fenceMarker = marker; // "`" or "```" or "~~~~"

                    // Consume marker
                    // Note: We don't output marker yet? 
                    // Wait, we SHOULD output the marker as content! Code blocks are content.
                    outputContent += marker;
                    this.buffer = this.buffer.slice(marker.length);
                    continue;
                }

                // -- TAG WINNER --
                if (winner === 'tag' && tagMatch) {
                    // Output text before tag
                    outputContent += this.buffer.slice(0, tagIdx);
                    this.buffer = this.buffer.slice(tagIdx);

                    let tagName = tagMatch[1] ? tagMatch[1].toLowerCase() : '';
                    this.startTag = tagName;

                    if (tagName === 'plan') {
                        this.state = 'IN_PLAN';
                        const closeBracket = this.buffer.indexOf('>');
                        if (closeBracket !== -1) {
                            this.buffer = this.buffer.slice(closeBracket + 1);
                        } else { break; }
                    } else {
                        this.state = 'IN_TOOL_XML';
                        // Continue loop to process close tag in same process() call
                        continue;
                    }
                }

            }
            // =================================================================================
            // State: IN_PLAN / IN_TOOL_XML
            // =================================================================================
            if (this.state === 'IN_PLAN') {
                const endRegex = /<\/plan>/i;
                const match = endRegex.exec(this.buffer);
                if (match) {
                    const planContent = this.buffer.slice(0, match.index);
                    outputPlan = this.parsePlan(planContent);
                    this.buffer = this.buffer.slice(match.index + match[0].length);
                    this.state = 'IDLE';
                } else { break; }
            } else if (this.state === 'IN_TOOL_XML') {
                const closeRegex = new RegExp(`<\/${this.startTag}>`, 'i');
                const match = closeRegex.exec(this.buffer);
                if (match) {
                    const fullBlock = this.buffer.slice(0, match.index + match[0].length);
                    const tools = this.parseTools(fullBlock);
                    if (tools.length > 0) outputToolCalls.push(...tools);

                    this.buffer = this.buffer.slice(match.index + match[0].length);
                    this.state = 'IDLE';
                } else { break; }
            }
        }

        return {
            content: outputContent,
            reasoning: outputReasoning,
            toolCalls: outputToolCalls.length > 0 ? outputToolCalls : undefined,
            plan: outputPlan
        };
    }

    private parsePlan(text: string): any[] | undefined {
        try {
            const sanitizedJson = text.trim().replace(/^```json\s*|\s*```$/g, '');
            // Try parsing JSON first
            const parsed = JSON.parse(sanitizedJson);

            let steps: any[] = [];
            if (Array.isArray(parsed)) {
                steps = parsed;
            } else if (typeof parsed === 'object' && parsed.steps && Array.isArray(parsed.steps)) {
                steps = parsed.steps;
            }

            if (steps.length > 0) {
                return steps.map((item: any, idx: number) => ({
                    id: item.id || `plan_step_${Date.now()}_${idx}`,
                    title: item.title || item.content || `Step ${idx + 1}`,
                    status: item.status || 'pending',
                    description: item.description
                }));
            }
        } catch (e) {
            // Fallback: line-by-line
            const lines = text.split('\n').map(l => l.trim()).filter(l => l.length > 0);
            return lines.map((line, idx) => ({
                id: `legacy_step_${Date.now()}_${idx}`,
                title: line.replace(/^\d+[\.\)]\s*/, ''),
                status: 'pending'
            }));
        }
        return undefined;
    }

    private parseTools(block: string): ToolCall[] {
        const calls: ToolCall[] = [];
        const addCall = (name: string, args: any) => {
            calls.push({
                id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                name,
                arguments: args
            });
        };

        // 1. Kimi/Generic: <tool_code>JSON</tool_code>
        if (this.startTag === 'tool_code' || this.startTag === 'tool_calls' || this.startTag === 'tools') {
            // Extract inner
            const inner = block.replace(/<\/?(tool_code|tool_calls|tools)>/gi, '').trim();
            try {
                const parsed = JSON.parse(inner);
                if (Array.isArray(parsed)) {
                    parsed.forEach(c => {
                        const name = c.function?.name || c.name || c.id;
                        const args = c.function?.arguments || c.arguments || c.parameters;
                        if (name && args) addCall(name, args);
                    });
                } else if (typeof parsed === 'object') {
                    const name = parsed.function?.name || parsed.name;
                    const args = parsed.function?.arguments || parsed.arguments;
                    if (name && args) addCall(name, args);
                }
            } catch (e) { }
        }

        // 2. DeepSeek Reasoner: <call tool="name">JSON</call>
        else if (this.startTag === 'call') {
            // Support single and double quotes for tool attribute
            const match = /<call\s+tool=["']([^"']+)["']>([\s\S]*?)<\/call>/i.exec(block);
            if (match) {
                const name = match[1];
                const inner = match[2].trim();

                let jsonStr = inner;
                // Check for <tool_input> wrapper
                const inputMatch = /<tool_input>([\s\S]*?)<\/tool_input>/i.exec(inner);
                if (inputMatch) {
                    jsonStr = inputMatch[1];
                } else {
                    // Fallback: If no tool_input, try to find the first JSON object '{...}'
                    const firstBrace = inner.indexOf('{');
                    const lastBrace = inner.lastIndexOf('}');
                    if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
                        jsonStr = inner.substring(firstBrace, lastBrace + 1);
                    }
                }

                try {
                    const parsed = JSON.parse(jsonStr);
                    // Ensure it's not empty if possible, or accept it. 
                    // DeepSeek sometimes emits empty JSON? 
                    addCall(name, parsed);
                } catch (e) {
                    console.warn('[StreamParser] Failed to parse DeepSeek tool args:', jsonStr);
                }
            }
        }

        // 3. DeepSeek Chat: <tool_call><function_name>...</function_name><parameters>...</parameters></tool_call>
        else if (this.startTag === 'tool_call') {
            const nameMatch = /<function_name>([\s\S]*?)<\/function_name>/i.exec(block);
            const paramsMatch = /<parameters>([\s\S]*?)<\/parameters>/i.exec(block);

            if (nameMatch && paramsMatch) {
                const name = nameMatch[1].trim();
                const paramsInner = paramsMatch[1].trim();

                // Try XML params first: <key>val</key>
                const args: any = {};
                const argRegex = /<([^>]+)>([\s\S]*?)<\/\1>/g;
                let hasXmlArgs = false;
                let argMatch;
                while ((argMatch = argRegex.exec(paramsInner)) !== null) {
                    args[argMatch[1]] = argMatch[2].trim();
                    hasXmlArgs = true;
                }

                if (!hasXmlArgs && paramsInner.startsWith('{')) {
                    try { Object.assign(args, JSON.parse(paramsInner)); } catch (e) { }
                }

                addCall(name, args);
            }
        }

        return calls;
    }

    /**
     * 获取清理后的内容（移除Provider特定的XML/标签）
     * 
     * @param rawContent 原始内容
     * @returns 清理后的纯文本内容
     */
    getCleanContent(rawContent: string): string {
        // Use the shared robust regex to remove ALL tool/thinking blocks
        // regardless of provider. This is the safest way to ensure clean UI.
        return rawContent.replace(LLM_STRUCTURED_BLOCK_REGEX, '').trim();
    }

    /**
     * 获取原始未清理的内容（用于调试或特殊用途）
     */
    getRawContent(content: string): string {
        return content;
    }
}


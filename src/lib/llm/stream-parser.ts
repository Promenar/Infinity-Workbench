import { ToolCall } from '../../types/skills';

export interface ParseResult {
    content: string;
    reasoning: string;
    toolCalls?: ToolCall[];
    plan?: any[];
}

type ParserState = 'IDLE' | 'IN_THINK' | 'IN_TOOL_XML' | 'IN_PLAN';

/**
 * StreamParser
 * 
 * A stateful, incremental parser for LLM output streams.
 * Optimized to handle mixed content (text, reasoning, tool calls) 
 * without O(N^2) regex matching on the entire history.
 */
export class StreamParser {
    private buffer: string = '';
    private state: ParserState = 'IDLE';

    // Buffers for specific blocks
    private startTag: string = ''; // Stores which tag started the block
    private bracketCount: number = 0; // 🧠 Bracket Counting for JSON optimization

    process(chunk: string): ParseResult {
        let outputContent = '';
        let outputReasoning = '';
        const outputToolCalls: ToolCall[] = [];
        let outputPlan: any[] | undefined;

        this.buffer += chunk;

        // Process buffer until we can't anymore
        let loopGuard = 0;
        while (loopGuard++ < 1000) {
            // Break if buffer empty
            if (this.buffer.length === 0) break;

            if (this.state === 'IDLE') {
                // Scan for any known start tag
                const tagRegex = /<(think|thought|plan|tool_code|tool_calls|tools|tool_call|call)(\s|>)/i;
                const match = tagRegex.exec(this.buffer);

                if (!match) {
                    const lastOpen = this.buffer.lastIndexOf('<');
                    if (lastOpen !== -1 && lastOpen > this.buffer.length - 15) {
                        outputContent += this.buffer.slice(0, lastOpen);
                        this.buffer = this.buffer.slice(lastOpen);
                    } else {
                        outputContent += this.buffer;
                        this.buffer = '';
                    }
                    break;
                } else {
                    const tagIdx = match.index;
                    if (tagIdx > 0) {
                        outputContent += this.buffer.slice(0, tagIdx);
                        this.buffer = this.buffer.slice(tagIdx);
                    }

                    const tagName = match[1].toLowerCase();
                    this.startTag = tagName;

                    if (tagName === 'think' || tagName === 'thought') {
                        this.state = 'IN_THINK';
                        const closeBracket = this.buffer.indexOf('>');
                        if (closeBracket !== -1) {
                            this.buffer = this.buffer.slice(closeBracket + 1);
                        } else { break; }
                    } else if (tagName === 'plan') {
                        this.state = 'IN_PLAN';
                        const closeBracket = this.buffer.indexOf('>');
                        if (closeBracket !== -1) {
                            this.buffer = this.buffer.slice(closeBracket + 1);
                        } else { break; }
                    } else {
                        this.state = 'IN_TOOL_XML';
                        this.bracketCount = 0; // Reset counter
                        break;
                    }
                }
            } else if (this.state === 'IN_THINK') {
                // ... Existing IN_THINK logic ...
                const endRegex = /<\/(think|thought)>/i;
                const match = endRegex.exec(this.buffer);
                if (match) {
                    outputReasoning += this.buffer.slice(0, match.index);
                    this.buffer = this.buffer.slice(match.index + match[0].length);
                    this.state = 'IDLE';
                } else {
                    const lastOpen = this.buffer.lastIndexOf('<');
                    if (lastOpen !== -1 && lastOpen > this.buffer.length - 10) {
                        outputReasoning += this.buffer.slice(0, lastOpen);
                        this.buffer = this.buffer.slice(lastOpen);
                    } else {
                        outputReasoning += this.buffer;
                        this.buffer = '';
                    }
                    break;
                }
            } else if (this.state === 'IN_PLAN') {
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

                    // 🧠 OPTIMIZATION: Bracket Counting could go here if we were parsing incrementally,
                    // but we are extracting specific blocks. 
                    // However, for generic JSON tools, checking valid JSON structure avoids heavy regex on malformed data.

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
}

/**
 * 流式文本缓冲管理器
 * 
 * 核心职责：
 * 1. 基于结构化边界标记（XML）区分"思考过程"与"正式正文"
 * 2. 处理流式输出的边界抖动与不完整标记
 * 3. 提供统一的文本分离接口
 * 
 * Phase 1 重构：替代 Heuristic 正则方案
 */

// 边界标记常量
export const THINKING_START = '<!-- THINKING_START -->';
export const THINKING_END = '<!-- THINKING_END -->';

export interface ParsedContent {
    thinking: string;      // 思考过程内容
    content: string;       // 正式正文内容
    isComplete: boolean;   // 是否已完成解析（边界闭合）
    rawBuffer: string;     // 原始缓冲区（用于断点续传）
}

export class StreamBufferManager {
    private buffer: string = '';
    private thinkingBuffer: string = '';
    private contentBuffer: string = '';
    private inThinkingBlock: boolean = false;
    private thinkingComplete: boolean = false;

    /**
     * 追加新的流式数据块
     */
    append(chunk: string): ParsedContent {
        this.buffer += chunk;
        return this.parse();
    }

    /**
     * 解析当前缓冲区
     */
    private parse(): ParsedContent {
        let working = this.buffer;

        // 检查是否进入思考块
        if (!this.inThinkingBlock && !this.thinkingComplete) {
            const startIdx = working.indexOf(THINKING_START);
            if (startIdx !== -1) {
                // 思考块之前的内容视为正文
                this.contentBuffer += working.substring(0, startIdx);
                working = working.substring(startIdx + THINKING_START.length);
                this.inThinkingBlock = true;
                this.buffer = working;
            }
        }

        // 如果在思考块内，检查结束标记
        if (this.inThinkingBlock) {
            const endIdx = working.indexOf(THINKING_END);
            if (endIdx !== -1) {
                // 提取思考内容
                this.thinkingBuffer += working.substring(0, endIdx);
                working = working.substring(endIdx + THINKING_END.length);
                this.inThinkingBlock = false;
                this.thinkingComplete = true;
                this.buffer = working;
                // 剩余内容追加到正文
                this.contentBuffer += working;
                this.buffer = '';
            } else {
                // 思考块未闭合，累积到思考缓冲区
                this.thinkingBuffer += working;
                this.buffer = '';
            }
        } else if (this.thinkingComplete) {
            // 思考块已完成，后续全部为正文
            this.contentBuffer += working;
            this.buffer = '';
        } else {
            // 未检测到思考块，全部视为正文
            // 但保留部分缓冲以应对边界抖动（标记可能跨 chunk 分割）
            const potentialStartLength = THINKING_START.length - 1;
            if (working.length > potentialStartLength) {
                const safeContent = working.substring(0, working.length - potentialStartLength);
                const holdback = working.substring(working.length - potentialStartLength);
                this.contentBuffer += safeContent;
                this.buffer = holdback;
            }
            // 如果缓冲区过短，暂不处理，等待更多数据
        }

        return {
            thinking: this.thinkingBuffer,
            content: this.contentBuffer,
            isComplete: !this.inThinkingBlock,
            rawBuffer: this.buffer,
        };
    }

    /**
     * 强制刷新：将所有剩余缓冲区内容作为正文输出
     * 用于流结束时处理未闭合的标记
     */
    flush(): ParsedContent {
        if (this.inThinkingBlock) {
            // 思考块未闭合，将缓冲内容加入思考
            console.warn('[StreamBuffer] Unclosed thinking block detected, flushing as thinking content');
        }
        this.contentBuffer += this.buffer;
        this.buffer = '';

        return {
            thinking: this.thinkingBuffer,
            content: this.contentBuffer,
            isComplete: true,
            rawBuffer: '',
        };
    }

    /**
     * 重置状态
     */
    reset(): void {
        this.buffer = '';
        this.thinkingBuffer = '';
        this.contentBuffer = '';
        this.inThinkingBlock = false;
        this.thinkingComplete = false;
    }

    /**
     * 获取当前状态（用于调试）
     */
    getState(): { inThinking: boolean; thinkingComplete: boolean; bufferLength: number } {
        return {
            inThinking: this.inThinkingBlock,
            thinkingComplete: this.thinkingComplete,
            bufferLength: this.buffer.length,
        };
    }
}

/**
 * 降级方案：基于 Heuristic 的文本分类
 * 用于不支持结构化输出的模型
 */
export function classifyTextHeuristic(
    text: string,
    isTaskComplete: boolean
): { isThinking: boolean; confidence: number } {
    const trimmed = text.trim();

    // 任务完成时，倾向于保留为正文
    if (isTaskComplete) {
        return { isThinking: false, confidence: 0.9 };
    }

    // 长文本通常是正式回复
    if (text.length > 800) {
        return { isThinking: false, confidence: 0.85 };
    }

    // 检查引导词特征
    const thinkingPatterns = [
        /^(我将|正在|首先|现在|已经|让我|接下来)/,
        /^(I will|I'm going to|First|Now|Starting|Next|Let me)/i,
        /\.{3}$/,  // 以省略号结尾
        /：$/,      // 以中文冒号结尾
        /:$/,      // 以英文冒号结尾
    ];

    for (const pattern of thinkingPatterns) {
        if (pattern.test(trimmed)) {
            return { isThinking: true, confidence: 0.7 };
        }
    }

    // 默认：短文本可能是思考
    if (text.length < 300) {
        return { isThinking: true, confidence: 0.5 };
    }

    return { isThinking: false, confidence: 0.6 };
}

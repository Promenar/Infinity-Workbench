/**
 * ThinkingDetector - 统一思考标签检测器
 *
 * 职责：
 * 1. 从 LLM 流式输出中准确分离思考内容（reasoning）和正文内容（content）
 * 2. 处理标签跨 chunk 分割的边界情况
 * 3. 支持多种思考标签格式（<think/>, <thought/>, <!-- THINKING_START/END -->）
 *
 * 替代各 Provider 内联的 isInsideThinkTag 状态机和未使用的 StreamBufferManager。
 */

// 支持的思考开始标签（按优先级排序）
const THINK_OPEN_PATTERNS: { regex: RegExp; tagLength: number }[] = [
  // HTML 注释格式（最长，优先匹配）
  { regex: /<!--\s*THINKING_START\s*-->/i, tagLength: 23 },
  // XML 标签格式（支持属性如 <think type="reasoning">）
  { regex: /<think(?=\s|>)/i, tagLength: 7 },
  { regex: /<thought(?=\s|>)/i, tagLength: 9 },
];

// 支持的思考结束标签
const THINK_CLOSE_PATTERNS: { regex: RegExp; tagLength: number }[] = [
  { regex: /<!--\s*THINKING_END\s*-->/i, tagLength: 21 },
  { regex: /<\/think\s*>/i, tagLength: 8 },
  { regex: /<\/thought\s*>/i, tagLength: 10 },
];

// 可能触发标签的起始字符（用于尾部缓冲判断）
const TAG_TRIGGER_CHARS = ['<', '!'];
const MAX_TAG_PREFIX_LEN = 30; // <!-- THINKING_START --> 的长度 + 余量

export interface ThinkingResult {
  content: string;
  reasoning: string;
}

export class ThinkingDetector {
  private buffer: string = '';
  private state: 'OUTSIDE' | 'INSIDE' = 'OUTSIDE';

  /**
   * 处理一个新的流式 chunk，返回分类后的内容和推理
   *
   * @param chunk 新到达的文本片段
   * @returns 分离后的 { content, reasoning }
   */
  process(chunk: string): ThinkingResult {
    if (!chunk) return { content: '', reasoning: '' };

    this.buffer += chunk;

    let outputContent = '';
    let outputReasoning = '';

    let loopGuard = 0;
    while (loopGuard++ < 500 && this.buffer.length > 0) {
      if (this.state === 'OUTSIDE') {
        // 在 OUTSIDE 状态下，寻找思考开始标签
        const matchResult = this.findThinkOpen(this.buffer);

        if (matchResult.found) {
          // 找到开始标签
          // 标签之前的内容归入 content
          outputContent += this.buffer.substring(0, matchResult.index);
          // 跳过开始标签
          this.buffer = this.buffer.substring(matchResult.index + matchResult.tagLength);
          this.state = 'INSIDE';
          continue;
        }

        // 没找到完整标签，检查尾部是否有不完整的标签前缀
        const safeEnd = this.findSafeOutputEnd(this.buffer);
        if (safeEnd > 0) {
          outputContent += this.buffer.substring(0, safeEnd);
          this.buffer = this.buffer.substring(safeEnd);
        }

        // 如果尾部可能有标签前缀，保留在 buffer 中等待更多数据
        break;
      } else {
        // INSIDE 状态下，寻找思考结束标签
        const matchResult = this.findThinkClose(this.buffer);

        if (matchResult.found) {
          // 标签之前的内容归入 reasoning
          outputReasoning += this.buffer.substring(0, matchResult.index);
          // 跳过结束标签
          this.buffer = this.buffer.substring(matchResult.index + matchResult.tagLength);
          this.state = 'OUTSIDE';
          continue;
        }

        // 没找到完整结束标签，检查尾部是否有不完整的标签前缀
        const safeEnd = this.findSafeOutputEnd(this.buffer);
        if (safeEnd > 0) {
          outputReasoning += this.buffer.substring(0, safeEnd);
          this.buffer = this.buffer.substring(safeEnd);
        }

        // 如果尾部可能有标签前缀，保留在 buffer 中等待更多数据
        break;
      }
    }

    return { content: outputContent, reasoning: outputReasoning };
  }

  /**
   * 强制刷新：将所有缓冲区内容按当前状态输出
   * 用于流结束时的收尾
   */
  flush(): ThinkingResult {
    const result: ThinkingResult = {
      content: '',
      reasoning: '',
    };

    if (this.state === 'INSIDE') {
      // 未关闭的思考块，全部作为 reasoning
      result.reasoning = this.buffer;
    } else {
      result.content = this.buffer;
    }

    this.buffer = '';
    this.state = 'OUTSIDE';
    return result;
  }

  /**
   * 重置检测器状态
   */
  reset(): void {
    this.buffer = '';
    this.state = 'OUTSIDE';
  }

  /**
   * 获取当前状态（用于调试）
   */
  getState(): { state: 'OUTSIDE' | 'INSIDE'; bufferLength: number } {
    return {
      state: this.state,
      bufferLength: this.buffer.length,
    };
  }

  /**
   * 在文本中查找思考开始标签
   */
  private findThinkOpen(text: string): { found: boolean; index: number; tagLength: number } {
    let bestIndex = -1;
    let bestTagLength = 0;

    for (const pattern of THINK_OPEN_PATTERNS) {
      const match = pattern.regex.exec(text);
      if (match && (bestIndex === -1 || match.index < bestIndex)) {
        bestIndex = match.index;
        bestTagLength = pattern.tagLength;
      }
    }

    // 动态计算实际匹配长度（处理 <think attr="val"> 等带属性的标签）
    if (bestIndex !== -1) {
      const closeBracket = text.indexOf('>', bestIndex);
      if (closeBracket !== -1) {
        bestTagLength = closeBracket - bestIndex + 1;
      }
    }

    return {
      found: bestIndex !== -1,
      index: bestIndex,
      tagLength: bestTagLength,
    };
  }

  /**
   * 在文本中查找思考结束标签
   */
  private findThinkClose(text: string): { found: boolean; index: number; tagLength: number } {
    let bestIndex = -1;
    let bestTagLength = 0;

    for (const pattern of THINK_CLOSE_PATTERNS) {
      const match = pattern.regex.exec(text);
      if (match && (bestIndex === -1 || match.index < bestIndex)) {
        bestIndex = match.index;
        bestTagLength = match[0].length; // 使用实际匹配长度
      }
    }

    return {
      found: bestIndex !== -1,
      index: bestIndex,
      tagLength: bestTagLength,
    };
  }

  /**
   * 找到可以安全输出的末尾位置
   * 保留尾部可能形成标签的字符在 buffer 中
   */
  private findSafeOutputEnd(text: string): number {
    if (text.length === 0) return 0;

    // 检查尾部是否有 '<' 或 '<!' 等可能开始标签的字符
    const lastOpenBracket = text.lastIndexOf('<');
    if (lastOpenBracket === -1) {
      // 没有 '<'，全部安全输出
      return text.length;
    }

    // 如果 '<' 在最后 MAX_TAG_PREFIX_LEN 个字符内，保留
    const tailLength = text.length - lastOpenBracket;
    if (tailLength <= MAX_TAG_PREFIX_LEN) {
      // 只输出到 '<' 之前
      return lastOpenBracket;
    }

    // '<' 在很早的位置，可以安全输出到更前面
    // 但仍然保留最后几个字符以防万一
    const safeEnd = text.length - MAX_TAG_PREFIX_LEN;
    return Math.max(0, safeEnd);
  }
}

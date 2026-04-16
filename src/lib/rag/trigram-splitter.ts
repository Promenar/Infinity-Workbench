/**
 * Trigram 文本分词器
 *
 * 专为中文文本设计的分词器，使用 Trigram (三元组) 方法进行分块。
 *
 * 工作原理：
 * 1. 首先按句子分割（中文句号、问号、感叹号等）
 * 2. 对于超过 chunkSize 的句子，使用 Trigram 滑动窗口分块
 * 3. 确保每个块之间有 overlap 重叠，保持语义连贯性
 *
 * 性能优化：当 native TextSplitter (C++) 可用时，自动委托给原生实现，
 * 消除 JS 主线程阻塞和 setTimeout(0) 让出 hack。
 *
 * 优势：
 * - 更适合中文语义边界
 * - 保留更多上下文信息
 * - 减少语义断裂
 */

import { splitTextNative, isNativeSplitterAvailable, estimateChunkCountNative } from '../../native/TextSplitter';

interface TrigramSplitterConfig {
  chunkSize: number; // 块大小（字符数）
  chunkOverlap: number; // 重叠大小（字符数）
}

export class TrigramTextSplitter {
  private chunkSize: number;
  private chunkOverlap: number;

  // 中文句子分隔符
  private sentenceDelimiters = /([。！？；\n])/g;

  constructor(config: TrigramSplitterConfig) {
    this.chunkSize = config.chunkSize;
    this.chunkOverlap = config.chunkOverlap;

    // 验证参数
    if (this.chunkOverlap >= this.chunkSize) {
      throw new Error('chunkOverlap must be smaller than chunkSize');
    }
  }

  /**
   * 分割文本为多个块 (异步非阻塞)
   */
  async splitText(text: string): Promise<string[]> {
    if (!text || text.trim().length === 0) {
      return [];
    }

    // Try native C++ implementation first (runs on background thread, no UI blocking)
    if (isNativeSplitterAvailable()) {
      const nativeResult = await splitTextNative(text, this.chunkSize, this.chunkOverlap);
      if (nativeResult !== null) {
        return nativeResult;
      }
    }

    // Fallback: JS implementation
    return this.splitTextJS(text);
  }

  /**
   * JS fallback implementation (original logic)
   */
  private async splitTextJS(text: string): Promise<string[]> {

    // Step 1: 按句子分割
    const sentences = this.splitIntoSentences(text);

    // Step 2: 合并短句子，拆分长句子
    const chunks: string[] = [];
    let currentChunk = '';

    for (let i = 0; i < sentences.length; i++) {
      const sentence = sentences[i];

      // 每处理 50 个句子让出一次主线程，避免 UI 卡死
      if (i % 50 === 0) {
        await new Promise((resolve) => setTimeout(resolve, 0));
      }

      // 如果单个句子超过 chunkSize，需要进一步拆分
      if (sentence.length > this.chunkSize) {
        // 先保存当前积累的块
        if (currentChunk.trim().length > 0) {
          chunks.push(currentChunk.trim());
          currentChunk = '';
        }

        // 使用 Trigram 方法拆分长句子
        const subChunks = this.splitLongSentence(sentence);
        chunks.push(...subChunks);
      } else {
        // 尝试将句子加入当前块
        const testChunk = currentChunk + sentence;

        if (testChunk.length <= this.chunkSize) {
          // 可以合并
          currentChunk = testChunk;
        } else {
          // 当前块已满，保存并开始新块
          if (currentChunk.trim().length > 0) {
            chunks.push(currentChunk.trim());
          }
          currentChunk = sentence;
        }
      }
    }

    // 保存最后一个块
    if (currentChunk.trim().length > 0) {
      chunks.push(currentChunk.trim());
    }

    // Step 3: 添加重叠（overlap）
    return this.addOverlap(chunks);
  }

  /**
   * 按句子分割文本
   */
  private splitIntoSentences(text: string): string[] {
    // 保留分隔符的分割
    const parts = text.split(this.sentenceDelimiters);
    const sentences: string[] = [];

    for (let i = 0; i < parts.length; i += 2) {
      const content = parts[i] || '';
      const delimiter = parts[i + 1] || '';

      if (content.trim().length > 0) {
        sentences.push(content + delimiter);
      }
    }

    return sentences.filter((s) => s.trim().length > 0);
  }

  /**
   * 使用 Trigram 方法拆分长句子
   *
   * Trigram: 每3个字符作为一个单元，滑动窗口分割
   */
  private splitLongSentence(sentence: string): string[] {
    const chunks: string[] = [];
    let startIndex = 0;

    while (startIndex < sentence.length) {
      // 计算当前块的结束位置
      let endIndex = Math.min(startIndex + this.chunkSize, sentence.length);

      // 如果不是最后一块，尝试在合适的位置断开（避免断词）
      if (endIndex < sentence.length) {
        // 优先在标点符号处断开
        const punctuation = /[，、；：""''（）\s]/;
        let breakPoint = endIndex;

        // 向后查找最近的标点符号（最多向后查10个字符）
        for (let i = endIndex; i < Math.min(endIndex + 10, sentence.length); i++) {
          if (punctuation.test(sentence[i])) {
            breakPoint = i + 1;
            break;
          }
        }

        // 如果向后没找到，向前查找
        if (breakPoint === endIndex) {
          for (let i = endIndex - 1; i > Math.max(endIndex - 10, startIndex); i--) {
            if (punctuation.test(sentence[i])) {
              breakPoint = i + 1;
              break;
            }
          }
        }

        endIndex = breakPoint;
      }

      // 提取块
      const chunk = sentence.substring(startIndex, endIndex).trim();
      if (chunk.length > 0) {
        chunks.push(chunk);
      }

      // 移动起始位置（考虑重叠）
      const nextStartIndex = endIndex - this.chunkOverlap;

      // 🔑 严格防御死循环：确保 startIndex 每次至少前进
      // 如果计算出的 nextStartIndex 导致位置没有前进（可能因为 overlap 较大且 endIndex 被标点缩减），
      // 则强制跳转到 endIndex，牺牲该位置的重叠以保证程序继续。
      if (nextStartIndex <= startIndex) {
        startIndex = endIndex;
      } else {
        startIndex = nextStartIndex;
      }
    }

    return chunks;
  }

  /**
   * 为块添加重叠部分（从前一个块取后缀，加入下一个块前缀）
   */
  private addOverlap(chunks: string[]): string[] {
    if (chunks.length <= 1 || this.chunkOverlap === 0) {
      return chunks;
    }

    const overlappedChunks: string[] = [chunks[0]]; // 第一个块保持不变

    for (let i = 1; i < chunks.length; i++) {
      const prevChunk = chunks[i - 1];
      const currentChunk = chunks[i];

      // 从前一个块取后 overlap 个字符
      const overlapText = prevChunk.slice(-this.chunkOverlap);

      // 如果当前块已经以这个重叠文本开头，跳过
      if (!currentChunk.startsWith(overlapText)) {
        overlappedChunks.push(overlapText + currentChunk);
      } else {
        overlappedChunks.push(currentChunk);
      }
    }

    return overlappedChunks;
  }

  /**
   * 估算文本会被分割成多少块（不实际分割）
   */
  estimateChunkCount(text: string): number {
    // Try native first
    if (isNativeSplitterAvailable()) {
      const nativeResult = estimateChunkCountNative(text, this.chunkSize, this.chunkOverlap);
      if (nativeResult !== null) {
        return nativeResult;
      }
    }

    // Fallback: JS estimate
    const textLength = text.length;
    const effectiveChunkSize = this.chunkSize - this.chunkOverlap;

    if (effectiveChunkSize <= 0) {
      return Math.ceil(textLength / this.chunkSize);
    }

    return Math.max(1, Math.ceil(textLength / effectiveChunkSize));
  }
}

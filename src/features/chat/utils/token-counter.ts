/**
 * NeuralFlow Token Estimation Utility
 *
 * Provides a lightweight method to estimate token counts for both Chinese and English text.
 * Heuristic based on common LLM tokenizer behaviors (GPT-3.5/4, Gemini).
 */

export function estimateTokens(text: string): number {
  if (!text) return 0;

  // 1. Handle CJK (Chinese, Japanese, Korean) characters
  // Most tokenizers treat CJK characters as 1.5 - 2 tokens each.
  // We'll use 1.6 as a conservative average.
  const cjkRegex = /[\u4e00-\u9fa5\u3040-\u309f\u30a0-\u30ff\uac00-\ud7af]/g;
  const cjkMatches = text.match(cjkRegex);
  const cjkCount = cjkMatches ? cjkMatches.length : 0;

  // 2. Handle non-CJK text (English, numbers, symbols)
  // Remove CJK characters to count the rest
  const remainingText = text.replace(cjkRegex, '');

  // For English, a common rule is ~4 characters per token or ~0.75 tokens per word.
  // We'll use word-based for English to be more accurate with punctuation.
  const words = remainingText.trim().split(/\s+/);
  const wordCount = words[0] === '' ? 0 : words.length;

  // Numbers and special symbols often take 1 token each if they stand alone.
  // We'll estimate based on word count + a factor for symbols.
  const englishTokens = Math.ceil(wordCount * 1.3);

  // Total tokens
  const totalTokens = Math.ceil(cjkCount * 1.5) + englishTokens;

  return totalTokens;
}

export function formatTokenCount(tokens: number): string {
  if (tokens < 1000) return tokens.toString();
  if (tokens < 1000000) {
    const k = tokens / 1000;
    return k >= 100 ? Math.round(k).toString() + 'k' : k.toFixed(1) + 'k';
  }
  const m = tokens / 1000000;
  return m >= 100 ? Math.round(m).toString() + 'M' : m.toFixed(1) + 'M';
}

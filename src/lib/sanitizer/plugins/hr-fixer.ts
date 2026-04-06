import { SanitizerPlugin } from '../types';

/**
 * Ensures blank lines around horizontal rules (---)
 */
export const hrFixer: SanitizerPlugin = {
  name: 'hr-fixer',
  phase: 'post-protect',
  process(text) {
    let processed = text;
    // 3b. 分隔符前后确保空行（仅匹配独立成行的 HR，避免破坏 GFM 表格分隔行 `| --- |`）
    processed = processed.replace(/([^\n])\n(^---+\s*$)/gm, '$1\n\n$2');
    processed = processed.replace(/(^---+\s*$)\n([^\n])/gm, '$1\n\n$2');
    return processed;
  }
};

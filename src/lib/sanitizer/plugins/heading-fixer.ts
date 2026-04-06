import { SanitizerPlugin } from '../types';

/**
 * Ensures blank line before headings and fixes malformed headings (missing space after #)
 */
export const headingFixer: SanitizerPlugin = {
  name: 'heading-fixer',
  phase: 'post-protect',
  process(text) {
    let processed = text;
    // 3a. 标题（有空格）前确保空行
    processed = processed.replace(/([^\n#])\n?(#{1,6}\s)/g, '$1\n\n$2');

    // 3a'. 标题（无空格，畸形）: "text###标题" → "text\n\n### 标题"
    processed = processed.replace(/([^\n#])\n?(#{1,6})([^\s#\n])/g, '$1\n\n$2 $3');
    processed = processed.replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2');
    return processed;
  }
};

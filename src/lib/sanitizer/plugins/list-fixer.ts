import { SanitizerPlugin } from '../types';

/**
 * Ensures blank line before ordered/unordered lists and fixes sticking bullets/bold
 */
export const listFixer: SanitizerPlugin = {
  name: 'list-fixer',
  phase: 'post-protect',
  process(text) {
    let processed = text;
    // 3c. 有序列表（数字开头）紧跟正文
    processed = processed.replace(/([^\n\d# ])\n?(\d{1,2}\. )/g, '$1\n$2');

    // 3d'. 修复粘连的 bullet + bold（仅行首场景）
    processed = processed.replace(/^(\*\*\*)((?=[^*])([^*\n]+))\*\*(?!\*)/gm, '\n* **$3**');

    // 3d. 无序列表（符号开头）紧跟正文
    processed = processed.replace(/([^\n*])\n?([-*] )/g, '$1\n$2');
    return processed;
  }
};

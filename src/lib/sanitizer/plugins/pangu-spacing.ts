import { SanitizerPlugin } from '../types';

/**
 * Ensures spacing between Chinese characters and Latin letters/digits
 */
export const panguSpacing: SanitizerPlugin = {
  name: 'pangu-spacing',
  phase: 'post-protect',
  process(text) {
    let processed = text;
    // 中文后紧跟拉丁字母/数字 → 插入空格
    processed = processed.replace(/([\u4e00-\u9fa5\u3400-\u4dbf])([A-Za-z0-9])/g, '$1 $2');
    // 拉丁字母/数字后紧跟中文 → 插入空格
    processed = processed.replace(/([A-Za-z0-9])([\u4e00-\u9fa5\u3400-\u4dbf])/g, '$1 $2');
    return processed;
  }
};

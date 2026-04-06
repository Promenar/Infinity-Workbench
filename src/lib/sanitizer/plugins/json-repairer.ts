import { jsonrepair } from 'jsonrepair';
import { SanitizerPlugin } from '../types';

/**
 * Automatically repairs malformed JSON in code blocks (specifically for echarts and json)
 */
export const jsonRepairer: SanitizerPlugin = {
  name: 'json-repairer',
  phase: 'code-block',
  process(content, context) {
    const block = context.currentBlock;
    // Only target json and echarts blocks
    if (!block || !['json', 'echarts'].includes(block.language || '')) {
      return content;
    }

    try {
      // Attempt to repair the JSON syntax (handles trailing commas, unquoted keys, etc.)
      return jsonrepair(content);
    } catch (err) {
      // If repair fails, fall back to original content
      console.warn(`[Sanitizer] Failed to repair JSON in block:`, err);
      return content;
    }
  }
};

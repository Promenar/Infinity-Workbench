import { SanitizerPlugin } from '../types';

/**
 * Validates SVG content and identifies obvious AI-generated errors 
 * (like [object Object] or undefined strings inside the SVG)
 */
export const svgValidator: SanitizerPlugin = {
  name: 'svg-validator',
  phase: 'code-block',
  process(content, context) {
    const block = context.currentBlock;
    if (!block || block.language !== 'svg') {
      return content;
    }

    // Common indicators of AI failed generation
    const hasObviousErrors = 
      content.includes('undefined') || 
      content.includes('[object Object]') ||
      content.trim().length < 10; // Too short to be a valid SVG

    if (hasObviousErrors) {
      // Mark for UI as error state by changing language
      block.language = 'svg-error';
    }

    return content;
  }
};

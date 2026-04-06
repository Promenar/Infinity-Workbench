import { SanitizerPlugin } from '../types';

/**
 * Normalizes block LaTeX delimiters from $$ $$ to ```latex ``` fences
 */
export const blockMathFence: SanitizerPlugin = {
  name: 'block-math-fence',
  phase: 'post-protect', // Run after protected blocks to avoid double handling if any
  process(text) {
    const blockMathRegex = /\$\$([\s\S]+?)\$\$/g;
    return text.replace(blockMathRegex, (match, formula) => {
      return `\n\`\`\`latex\n${formula.trim()}\n\`\`\`\n`;
    });
  }
};

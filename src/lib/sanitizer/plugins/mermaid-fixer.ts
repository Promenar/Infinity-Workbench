// @ts-ignore - The library might not have types in some environments
import { fixText } from '@probelabs/maid';
import { SanitizerPlugin } from '../types';

/**
 * Automatically repairs malformed Mermaid syntax in code blocks
 */
export const mermaidFixer: SanitizerPlugin = {
  name: 'mermaid-fixer',
  phase: 'code-block',
  process(content, context) {
    const block = context.currentBlock;
    if (!block || block.language !== 'mermaid') {
      return content;
    }

    try {
      // @probelabs/maid specializes in fixing AI-generated mermaid syntax
      const result = fixText(content, { level: 'safe' });
      
      // If result is an object with 'fixed' property (typical for this lib)
      if (result && typeof result === 'object' && 'fixed' in result) {
        return (result as { fixed: string }).fixed;
      }
      
      // Otherwise return result as string or fallback to content
      return (typeof result === 'string' ? result : content) || content;
    } catch (err) {
      console.warn(`[Sanitizer] Failed to repair Mermaid syntax:`, err);
      return content;
    }
  }
};

import { sanitizeAiText } from 'ai-text-sanitizer';
import { SanitizerPlugin } from '../types';

/**
 * Removes AI-generated artifacts like zero-width watermarks, 
 * invisible characters, and citation leftovers.
 */
export const textCleaner: SanitizerPlugin = {
  name: 'text-cleaner',
  phase: 'pre-protect', // Run before anything else to clean raw input
  process(text, context) {
    if (context.options.aiTextClean === false) return text;

    try {
      // sanitizeAiText removes zero-width characters and normalization issues
      const result = (sanitizeAiText as any)(text, { 
        removeWatermarks: true,
      });

      if (typeof result === 'string') {
        return result;
      }
      
      // If result is SanitizeResult object
      if (result && typeof result === 'object' && 'text' in result) {
        return (result as { text: string }).text;
      }

      return text;
    } catch (err) {
      console.warn(`[Sanitizer] Failed to run ai-text-sanitizer:`, err);
      return text;
    }
  }
};

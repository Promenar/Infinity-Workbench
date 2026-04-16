import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * TokenCounter TurboModule Spec
 *
 * Provides high-performance token counting via native C++ implementation.
 * Uses precise Unicode character classification instead of JS regex.
 */
export interface Spec extends TurboModule {
  /**
   * Count tokens for the given text.
   * Returns a precise estimate based on:
   * - CJK characters: ~1.5 tokens each
   * - English words: ~1.3 tokens each
   * - Digits/symbols: independent counting
   */
  countTokens(text: string): number;
}

export default TurboModuleRegistry.getEnforcing<Spec>('TokenCounter');

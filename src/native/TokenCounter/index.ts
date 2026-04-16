import { NativeModules } from 'react-native';

/**
 * TokenCounter Native Module Interface
 */
interface TokenCounterNative {
  countTokens(text: string): number;
}

const TokenCounter = NativeModules.TokenCounter as TokenCounterNative | undefined;

/**
 * Check if the native TokenCounter module is available
 */
export function isNativeTokenCounterAvailable(): boolean {
  return TokenCounter != null && typeof TokenCounter.countTokens === 'function';
}

/**
 * Count tokens using the native C++ implementation.
 * Falls back to null if native module is unavailable.
 *
 * This is synchronous for zero-overhead in hot paths.
 */
export function countTokensNative(text: string): number | null {
  if (!TokenCounter) return null;

  try {
    return TokenCounter.countTokens(text);
  } catch (e) {
    console.warn('[TokenCounter] Native count failed:', e);
    return null;
  }
}

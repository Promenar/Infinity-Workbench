import { NativeModules } from 'react-native';

/**
 * Sanitizer Native Module Interface
 */
interface SanitizerNative {
  processHotPath(
    text: string,
    enableHeadingFix: boolean,
    enablePanguSpacing: boolean,
    enableLineBreak: boolean,
  ): string;
}

const SanitizerModule = NativeModules.Sanitizer as SanitizerNative | undefined;

/**
 * Check if the native Sanitizer module is available
 */
export function isNativeSanitizerAvailable(): boolean {
  return SanitizerModule != null && typeof SanitizerModule.processHotPath === 'function';
}

/**
 * Process text through native hot-path sanitization (synchronous).
 * Falls back to null if native module is unavailable.
 */
export function processHotPathNative(
  text: string,
  options: {
    enableHeadingFix?: boolean;
    enablePanguSpacing?: boolean;
    enableLineBreak?: boolean;
  } = {},
): string | null {
  if (!SanitizerModule) return null;

  try {
    return SanitizerModule.processHotPath(
      text,
      options.enableHeadingFix !== false,
      options.enablePanguSpacing !== false,
      options.enableLineBreak !== false,
    );
  } catch (e) {
    console.warn('[Sanitizer] Native hot-path failed:', e);
    return null;
  }
}

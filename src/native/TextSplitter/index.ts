import { NativeModules } from 'react-native';

/**
 * TextSplitter Native Module Interface
 */
interface TextSplitterNative {
  splitText(text: string, chunkSize: number, chunkOverlap: number): Promise<string[]>;
  estimateChunkCount(text: string, chunkSize: number, chunkOverlap: number): number;
}

const TextSplitter = NativeModules.TextSplitter as TextSplitterNative | undefined;

/**
 * Check if the native TextSplitter module is available
 */
export function isNativeSplitterAvailable(): boolean {
  return TextSplitter != null && typeof TextSplitter.splitText === 'function';
}

/**
 * Split text using the native C++ implementation.
 * Falls back to null if native module is unavailable.
 */
export async function splitTextNative(
  text: string,
  chunkSize: number,
  chunkOverlap: number
): Promise<string[] | null> {
  if (!TextSplitter) return null;

  try {
    return await TextSplitter.splitText(text, chunkSize, chunkOverlap);
  } catch (e) {
    console.warn('[TextSplitter] Native split failed:', e);
    return null;
  }
}

/**
 * Estimate chunk count using the native C++ implementation.
 * Falls back to null if native module is unavailable.
 */
export function estimateChunkCountNative(
  text: string,
  chunkSize: number,
  chunkOverlap: number
): number | null {
  if (!TextSplitter) return null;

  try {
    return TextSplitter.estimateChunkCount(text, chunkSize, chunkOverlap);
  } catch (e) {
    console.warn('[TextSplitter] Native estimate failed:', e);
    return null;
  }
}

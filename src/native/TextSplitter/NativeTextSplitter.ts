import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * TextSplitter TurboModule Spec
 *
 * Provides high-performance text chunking via native C++ implementation.
 * Optimized for CJK (Chinese) text using Trigram sliding window algorithm.
 */
export interface Spec extends TurboModule {
  /**
   * Split text into chunks using Trigram algorithm.
   * Runs entirely on C++ background thread, no JS main thread blocking.
   *
   * @param text - Input text to split
   * @param chunkSize - Maximum chunk size in characters
   * @param chunkOverlap - Overlap size between consecutive chunks
   * @returns Array of text chunks
   */
  splitText(text: string, chunkSize: number, chunkOverlap: number): Promise<string[]>;

  /**
   * Estimate the number of chunks without actually splitting.
   * Synchronous, lightweight calculation.
   */
  estimateChunkCount(text: string, chunkSize: number, chunkOverlap: number): number;
}

export default TurboModuleRegistry.getEnforcing<Spec>('TextSplitter');

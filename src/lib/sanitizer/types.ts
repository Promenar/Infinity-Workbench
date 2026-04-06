/**
 * ContentSanitizer Plugin System Types
 */

export type SanitizerPhase = 'pre-protect' | 'post-protect' | 'code-block' | 'post-restore';

export interface ProtectedBlock {
  placeholder: string;
  content: string;
  language?: string;
}

export interface SanitizerOptions {
  /** Should extract images and remove tags from text? (Default: false) */
  extractImages?: boolean;
  /** Should add line breaks for Chinese text? (Default: true) */
  chineseLineBreaks?: boolean;
  /** Should clean AI artifacts like watermarks/citations? (Default: true) */
  aiTextClean?: boolean;
}

export interface SanitizerContext {
  protectedBlocks: ProtectedBlock[];
  options: SanitizerOptions;
  currentBlock?: ProtectedBlock;
  images: Array<{ src: string; alt: string }>;
}

export interface SanitizerPlugin {
  name: string;
  phase: SanitizerPhase;
  enabled?: boolean;
  process(input: string, context: SanitizerContext): string;
}

export interface SanitizerResult {
  text: string;
  images?: Array<{ src: string; alt: string }>;
}

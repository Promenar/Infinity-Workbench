import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * Sanitizer TurboModule Spec
 *
 * Provides high-performance hot-path markdown sanitization via native C++.
 * Combines 3 regex-heavy plugins (pangu-spacing, heading-fixer, line-breaker)
 * into a single-pass C++ scan for ~5x speedup.
 */
export interface Spec extends TurboModule {
  /**
   * Process text through hot-path sanitization rules in a single native pass.
   *
   * @param text - Input markdown text (after protected block extraction)
   * @param enableHeadingFix - Fix malformed headings and ensure blank lines
   * @param enablePanguSpacing - Insert spaces between CJK and Latin characters
   * @param enableLineBreak - Insert line breaks in long Chinese text
   * @returns Processed text with all selected rules applied
   */
  processHotPath(
    text: string,
    enableHeadingFix: boolean,
    enablePanguSpacing: boolean,
    enableLineBreak: boolean,
  ): string;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Sanitizer');

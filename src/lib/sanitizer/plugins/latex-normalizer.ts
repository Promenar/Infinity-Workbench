import { SanitizerPlugin } from '../types';

/**
 * Normalizes LaTeX delimiters from \[ \] and \( \) to $$ $$ and $ $
 */
export const latexNormalizer: SanitizerPlugin = {
  name: 'latex-normalizer',
  phase: 'pre-protect', // Run before protection to catch all delimiters
  process(text) {
    let processed = text;
    processed = processed.replaceAll('\\[', '$$').replaceAll('\\]', '$$');
    processed = processed.replaceAll('\\(', '$').replaceAll('\\)', '$');
    return processed;
  }
};

/**
 * Mock for ai-text-sanitizer (pure ESM package)
 * Jest cannot CJS-require ESM-only packages, so we provide a minimal mock.
 */

export function sanitizeAiText(text: string): string {
  return text;
}

/**
 * Mock for @probelabs/maid (pure ESM package)
 * Jest cannot CJS-require ESM-only packages, so we provide a minimal mock.
 */

export function fixText(text: string): string {
  return text;
}

export function validate(text: string): { valid: boolean; errors: string[] } {
  return { valid: true, errors: [] };
}

export function extractMermaidBlocks(text: string): string[] {
  return [];
}

export function detectDiagramType(text: string): string | null {
  return null;
}

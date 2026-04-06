import { 
  SanitizerOptions, 
  SanitizerResult, 
  SanitizerPlugin, 
  ProtectedBlock,
  SanitizerContext 
} from './types';

import { latexNormalizer } from './plugins/latex-normalizer';
import { headingFixer } from './plugins/heading-fixer';
import { hrFixer } from './plugins/hr-fixer';
import { listFixer } from './plugins/list-fixer';
import { tableFixer } from './plugins/table-fixer';
import { panguSpacing } from './plugins/pangu-spacing';
import { lineBreaker } from './plugins/line-breaker';
import { imageExtractor } from './plugins/image-extractor';
import { blockMathFence } from './plugins/block-math-fence';
import { jsonRepairer } from './plugins/json-repairer';
import { mermaidFixer } from './plugins/mermaid-fixer';
import { textCleaner } from './plugins/text-cleaner';
import { svgValidator } from './plugins/svg-validator';

const DEFAULT_PLUGINS: SanitizerPlugin[] = [
  textCleaner,       // Phase 0 (raw input cleaning)
  latexNormalizer,   // Phase 2
  blockMathFence,    // Convert $$ to ```latex
  headingFixer,      // Phase 3a
  hrFixer,           // Phase 3b
  listFixer,         // Phase 3c
  tableFixer,        // Phase 3d
  panguSpacing,      // Phase 3e
  jsonRepairer,      // Phase 4a (code-block)
  mermaidFixer,      // Phase 4b (code-block)
  svgValidator,      // Phase 4c (code-block)
  lineBreaker,       // Phase 5
  imageExtractor,    // Phase 6
];

const DEFAULT_OPTIONS: SanitizerOptions = {
  chineseLineBreaks: true,
  extractImages: false,
  aiTextClean: true,
};

/**
 * Main Content Sanitizer Pipeline
 */
export function sanitize(text: string, options?: SanitizerOptions, plugins?: SanitizerPlugin[]): SanitizerResult {
  if (!text) return { text: '' };
  
  const opts = { ...DEFAULT_OPTIONS, ...options };
  const activePlugins = plugins || DEFAULT_PLUGINS;
  
  const context: SanitizerContext = {
    protectedBlocks: [],
    options: opts,
    images: [],
  };

  let processed = text;

  // Phase 0: pre-protect
  for (const plugin of activePlugins.filter(p => p.phase === 'pre-protect')) {
    if (plugin.enabled !== false) {
      processed = plugin.process(processed, context);
    }
  }

  // Phase 1: Protect blocks (Code blocks and inline code)
  const { text: withPlaceholders, blocks } = extractProtectedBlocks(processed);
  context.protectedBlocks = blocks;
  processed = withPlaceholders;

  // Phase 2-3: post-protect (Structure fixing, etc.)
  for (const plugin of activePlugins.filter(p => p.phase === 'post-protect')) {
    if (plugin.enabled !== false) {
      processed = plugin.process(processed, context);
    }
  }

  // Phase 4: code-block (Repairing content inside blocks like JSON/Mermaid)
  for (const plugin of activePlugins.filter(p => p.phase === 'code-block')) {
    if (plugin.enabled !== false) {
      for (const block of context.protectedBlocks) {
        block.content = plugin.process(block.content, { ...context, currentBlock: block });
      }
    }
  }

  // Phase 5-7: Restore blocks + post-restore (Image extraction, etc.)
  processed = restoreProtectedBlocks(processed, context.protectedBlocks);
  
  for (const plugin of activePlugins.filter(p => p.phase === 'post-restore')) {
    if (plugin.enabled !== false) {
      processed = plugin.process(processed, context);
    }
  }

  return { 
    text: processed,
    images: context.images.length > 0 ? context.images : undefined
  };
}

/**
 * Extract code blocks and inline code to prevent them from being modified by regex
 */
function extractProtectedBlocks(text: string): { text: string; blocks: ProtectedBlock[] } {
  const blocks: ProtectedBlock[] = [];
  let processed = text;

  // 1. Fenced code blocks ```lang ... ```
  const fenceRegex = /```(\w*)\n?([\s\S]*?)```/g;
  processed = processed.replace(fenceRegex, (match, lang, content) => {
    const placeholder = `__PB_${blocks.length}__`;
    blocks.push({ placeholder, content, language: lang || undefined });
    return placeholder;
  });

  // 2. Inline code `...`
  const inlineRegex = /`([^`\n]+?)`/g;
  processed = processed.replace(inlineRegex, (match, content) => {
    const placeholder = `__PB_${blocks.length}__`;
    blocks.push({ placeholder, content }); // No language for inline
    return placeholder;
  });

  return { text: processed, blocks };
}

/**
 * Restore protected blocks back to their placeholders
 */
function restoreProtectedBlocks(text: string, blocks: ProtectedBlock[]): string {
  let restored = text;
  // Restore in reverse order to handle nesting if any (though regex here is simple)
  for (let i = blocks.length - 1; i >= 0; i--) {
    const block = blocks[i];
    const original = block.language 
      ? `\`\`\`${block.language}\n${block.content}\`\`\``
      : `\`${block.content}\``;
    restored = restored.replace(block.placeholder, original);
  }
  return restored;
}

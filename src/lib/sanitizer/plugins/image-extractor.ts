import { SanitizerPlugin } from '../types';

/**
 * Extracts ![alt](url) images from markdown and removes them from text if requested
 */
export const imageExtractor: SanitizerPlugin = {
  name: 'image-extractor',
  phase: 'post-restore', // Run after restoration to catch all images
  process(text, context) {
    if (!context.options.extractImages) return text;

    const imageRegex = /!\[([^\]]*)\]\(([^)]+)\)/g;
    const processed = text.replace(imageRegex, (match, alt, src) => {
      context.images.push({
        src: src.trim(),
        alt: alt.trim() || 'Generated Image',
      });
      return ''; // Remove image tag from text
    });

    return processed;
  }
};

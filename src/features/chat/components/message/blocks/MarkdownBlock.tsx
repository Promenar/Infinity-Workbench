import React, { useMemo } from 'react';
import { useMessageContext } from '../MessageContext';
import { StreamingCardList } from '../../StreamingCardList';
import { getMarkdownStyles } from '../styles/markdown-theme';
import { useMarkdownRules } from '../../../hooks/useMarkdownRules';
import { GeneratedImage } from './GeneratedImage';
import { sanitize } from '../../../../../lib/sanitizer';

interface MarkdownBlockProps {
  overrideContent?: string;
}

export const MarkdownBlock: React.FC<MarkdownBlockProps> = React.memo(({ overrideContent }) => {
  const { 
    message, 
    isDark, 
    colors, 
    t,
    onViewImage
  } = useMessageContext();

  const markdownStyles = useMemo(() => getMarkdownStyles(isDark, colors), [isDark, colors]);

  const markdownRules = useMarkdownRules({
    isDark,
    colors,
    t,
    setViewImageUri: (uri: string | null) => { if (onViewImage) onViewImage(uri); },
    GeneratedImage,
  });

  const streamingContent = useMemo(() => {
    let content = overrideContent || message.content || '';

    if (!content) return '';

    // Standard sanitization
    const result = sanitize(content, { extractImages: true });
    return result.text;
  }, [message.content, message.planningTask]);

  if (!streamingContent) return null;

  return (
    <StreamingCardList
      content={streamingContent}
      markdownRules={markdownRules}
      markdownStyles={markdownStyles}
    />
  );
});

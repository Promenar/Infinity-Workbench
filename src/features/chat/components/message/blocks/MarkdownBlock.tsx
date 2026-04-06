import React, { useMemo } from 'react';
import { useMessageContext } from '../MessageContext';
import { StreamingCardList } from '../../StreamingCardList';
import { getMarkdownStyles } from '../styles/markdown-theme';
import { useMarkdownRules } from '../../../hooks/useMarkdownRules';
import { GeneratedImage } from './GeneratedImage';
import { sanitize } from '../../../../../lib/sanitizer';

export const MarkdownBlock: React.FC = React.memo(() => {
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
    let content = message.content || '';

    // If no main content but task summary exists, use it
    if (!content && message.planningTask?.final_summary && message.planningTask?.status === 'completed') {
      content = message.planningTask.final_summary;
    }

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

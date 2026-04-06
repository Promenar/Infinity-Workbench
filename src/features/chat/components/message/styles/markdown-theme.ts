import { StyleSheet } from 'react-native';
import { Colors } from '../../../../../theme/colors';
import { markdownStyles as commonMarkdownStyles } from '../../../../../lib/markdown/markdown-utils';

export const getMarkdownStyles = (isDark: boolean, colors: any) => {
  return {
    body: {
      ...commonMarkdownStyles.body,
      color: isDark ? Colors.dark.textPrimary : '#27272A',
      fontSize: 15,
      lineHeight: 25,
      textAlign: 'left' as const,
      includeFontPadding: false,
    },
    text: {
      color: isDark ? Colors.dark.textPrimary : '#27272A',
      fontSize: 15,
      lineHeight: 25,
      fontWeight: '500' as const,
      includeFontPadding: false,
    },
    paragraph: { 
      ...commonMarkdownStyles.paragraph, 
      textAlign: 'left' as const 
    },
    blockquote: {
      backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
      borderLeftWidth: 3,
      borderLeftColor: colors[500],
      paddingHorizontal: 12,
      paddingVertical: 8,
      borderRadius: 8,
      marginVertical: 8,
    },
    heading1: { 
      ...commonMarkdownStyles.heading1, 
      color: isDark ? '#fff' : '#000' 
    },
    heading2: { 
      ...commonMarkdownStyles.heading2, 
      color: isDark ? '#fff' : '#000' 
    },
    list_item: commonMarkdownStyles.list_item,
    code_inline: {
      backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
      paddingHorizontal: 4,
      borderRadius: 4,
      fontFamily: 'monospace',
    },
    fence: {
      marginVertical: 12,
      borderRadius: 12,
      backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.02)',
      borderWidth: 1,
      borderColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)',
      overflow: 'hidden',
    },
  };
};

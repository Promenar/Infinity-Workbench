import React from 'react';
import { View, StyleSheet } from 'react-native';
import { EnrichedMarkdownText } from 'react-native-enriched-markdown';
import { useTheme } from '../../theme/ThemeProvider';

interface NativeMathRendererProps {
  content: string;
  isBlock?: boolean;
}

/**
 * 原生 LaTeX 数学公式渲染组件 (Phase 5)
 * 核心方案：集成 react-native-enriched-markdown
 * 优势：
 * 1. 零 WebView 开销（由 C++ / Fabric 原生渲染）
 * 2. 完美的流式输出体验（无 DOM 更新闪烁）
 * 3. 内存占用极低（约 0.1MB vs WebView 的 30MB+）
 */
export const NativeMathRenderer: React.FC<NativeMathRendererProps> = React.memo(({ 
  content, 
  isBlock = false 
}) => {
  const { isDark } = useTheme();

  // 确保包含定界符，enriched-markdown 需要通过定界符识别数学公式
  // 如果 content 已经包含 $ 则根据实际情况处理，通常 useMarkdownRules 会传不带 $ 的内容
  const hasDelimiters = content.trim().startsWith('$');
  const formattedContent = hasDelimiters 
    ? content 
    : (isBlock ? `$$\n${content}\n$$` : `$${content}$`);

  // 主题色配置
  const textColor = isDark ? '#e4e4e7' : '#27272a';

  return (
    <View style={isBlock ? styles.blockContainer : styles.inlineContainer}>
      <EnrichedMarkdownText
        markdown={formattedContent}
        flavor={isBlock ? 'github' : 'commonmark'}
        selectable={true}
        markdownStyle={{
          math: {
            fontSize: 16,
            color: textColor,
            textAlign: 'center',
            marginTop: 4,
            marginBottom: 4,
          },
          inlineMath: {
            color: textColor,
          },
          // 确保基础文字颜色与主题一致
          paragraph: {
            color: textColor,
            lineHeight: 24,
          }
        }}
        md4cFlags={{
          latexMath: true
        }}
      />
    </View>
  );
});

const styles = StyleSheet.create({
  inlineContainer: {
    paddingHorizontal: 1,
    justifyContent: 'center',
  },
  blockContainer: {
    width: '100%',
    marginVertical: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

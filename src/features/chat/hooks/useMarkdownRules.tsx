import React from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  Platform,
  Linking,
  StyleSheet,
} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as Haptics from '../../../lib/haptics';
import { Typography } from '../../../components/ui';
import { Copy, ExternalLink } from 'lucide-react-native';
import SyntaxHighlighter from 'react-native-syntax-highlighter';
import { atomOneDark, atomOneLight } from 'react-syntax-highlighter/dist/esm/styles/hljs';
import { NativeMathRenderer } from '../../../components/chat/NativeMathRenderer';
import { LazySVGRenderer } from '../../../components/chat/MathRenderer';
import { MermaidRenderer } from '../../../components/chat/MermaidRenderer';
import { EChartsRenderer } from '../../../components/chat/EChartsRenderer';

interface UseMarkdownRulesProps {
  isDark: boolean;
  colors: any;
  t: any;
  setViewImageUri: (uri: string | null) => void;
  GeneratedImage: React.ComponentType<any>;
}

export const useMarkdownRules = ({
  isDark,
  colors,
  t,
  setViewImageUri,
  GeneratedImage,
}: UseMarkdownRulesProps) => {
  return React.useMemo(
    () => ({
      // 1. Line Break & Paragraph
      softbreak: () => null,
      
      paragraph: (node: any, children: any) => {
        let hasInlineMath = false;
        if (Array.isArray(node.children)) {
          const inlineMathRegex = /\$(?!\d)[^\$]+\$/;
          hasInlineMath = node.children.some((child: any) => {
            if (child.type === 'text' && inlineMathRegex.test(child.content)) return true;
            if (child.children) {
              return child.children.some((grandchild: any) =>
                grandchild.type === 'text' && inlineMathRegex.test(grandchild.content)
              );
            }
            return false;
          });
        }

        if (hasInlineMath) {
          return (
            <View
              key={node.key}
              style={{
                flexDirection: 'row',
                flexWrap: 'wrap',
                alignItems: 'baseline',
                marginBottom: 8,
              }}
            >
              {children}
            </View>
          );
        }

        return (
          <View key={node.key} style={{ marginBottom: 8 }}>
            {children}
          </View>
        );
      },

      // 2. Text & Inline/Block Math
      text: (node: any, children: any, parent: any, styles: any) => {
        const content = node.content;
        if (!content.includes('$')) {
          return <Text key={node.key} style={styles.text}>{content}</Text>;
        }

        // 增强正则：支持 $...$ 和 $$...$$
        const parts = content.split(/(\${1,2}(?!\d)[^\$]+\${1,2})/g);
        
        return (
          <React.Fragment key={node.key}>
            {parts.map((part: string, index: number) => {
              const isDisplayMath = part.startsWith('$$') && part.endsWith('$$');
              const isInlineMath = !isDisplayMath && part.startsWith('$') && part.endsWith('$');
              
              if (isDisplayMath || isInlineMath) {
                const math = isDisplayMath ? part.slice(2, -2) : part.slice(1, -1);
                return (
                  <View
                    key={index}
                    style={isDisplayMath ? {
                      width: '100%',
                      marginVertical: 10,
                      alignItems: 'center',
                    } : {
                      marginHorizontal: 3,
                      flexShrink: 0,
                    }}
                  >
                    <NativeMathRenderer content={math} isBlock={isDisplayMath} />
                  </View>
                );
              }
              if (!part) return null;

              return (
                <Text key={index} style={styles.text}>
                  {part}
                </Text>
              );
            })}
          </React.Fragment>
        );
      },

      // 3. Code Blocks & SVG
      fence: (node: any, children: any, parent: any, styles: any) => {
        const content = node.content || '';
        const language = node.attributes?.lang || node.info || '';

        // 4. SVG Optimization
        if (language === 'svg' || (content.trim().startsWith('<svg') && content.trim().endsWith('</svg>'))) {
          const hasObviousSyntaxErrors = content.includes('undefined') || content.includes('[object Object]');

          if (hasObviousSyntaxErrors) {
            return (
              <View
                key={node.key}
                collapsable={false}
                style={{
                  marginVertical: 12,
                  padding: 16,
                  backgroundColor: isDark ? '#27272a' : '#fff1f2',
                  borderRadius: 12,
                  borderWidth: 1,
                  borderColor: isDark ? '#3f3f46' : '#fecaca',
                }}
              >
                <Typography style={{ color: '#e11d48', fontSize: 13, fontWeight: '700' }}>
                  {t.svgErrorTitle}
                </Typography>
                <Typography variant="caption" style={{ color: isDark ? '#a1a1aa' : '#6b7280', marginTop: 4 }}>
                  {t.svgBlockedDesc}
                </Typography>
              </View>
            );
          }

          return (
            <View key={node.key + '-svg'} collapsable={false} style={{ marginVertical: 12, width: '100%' }}>
              <LazySVGRenderer svgContent={content} isDark={isDark} />
            </View>
          );
        }

        // 5. Mermaid & ECharts
        if (language === 'mermaid') {
          return <MermaidRenderer key={node.key} content={content} />;
        }

        if (language === 'echarts') {
          return <EChartsRenderer key={node.key} content={content} />;
        }

        const handleCopyCode = async () => {
          await Clipboard.setStringAsync(content);
          setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
          }, 10);
        };

        return (
          <View key={node.key} style={[styles.fence, { padding: 0, overflow: 'hidden' }]}>
            <View
              style={{
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
                paddingHorizontal: 12,
                paddingVertical: 8,
                backgroundColor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)',
                borderBottomWidth: StyleSheet.hairlineWidth,
                borderBottomColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)',
              }}
            >
              <Typography variant="caption" style={{ color: isDark ? '#a1a1aa' : '#71717a', fontWeight: '600' }}>
                {language.toUpperCase() || 'CODE'}
              </Typography>
              <TouchableOpacity
                onPress={handleCopyCode}
                hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
              >
                <Copy size={14} color={isDark ? '#a1a1aa' : '#71717a'} />
              </TouchableOpacity>
            </View>
            <View style={{ padding: 0 }}>
              <SyntaxHighlighter
                language={language || 'text'}
                style={isDark ? atomOneDark : atomOneLight}
                highlighter={'hljs'}
                fontSize={13}
                fontFamily={Platform.OS === 'ios' ? 'Menlo' : 'monospace'}
                customStyle={{
                  padding: 12,
                  backgroundColor: 'transparent',
                }}
                CodeTag={Text}
                PreTag={View}
              >
                {content}
              </SyntaxHighlighter>
            </View>
          </View>
        );
      },

      // 4. Images
      image: (node: any) => {
        const { src, alt } = node.attributes;
        return <GeneratedImage key={node.key} src={src} alt={alt} isDark={isDark} t={t} onImagePress={setViewImageUri} />;
      },

      // 5. Tables
      table: (node: any, children: any) => (
        <ScrollView
          key={node.key}
          horizontal
          showsHorizontalScrollIndicator={true}
          style={{ marginVertical: 10 }}
          contentContainerStyle={{ flexGrow: 1 }}
        >
          <View style={{
            borderWidth: 1,
            borderColor: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.15)',
            borderRadius: 6,
            overflow: 'hidden',
            minWidth: '100%',
          }}>
            {children}
          </View>
        </ScrollView>
      ),
      thead: (node: any, children: any) => (
        <View key={node.key}>{children}</View>
      ),
      tbody: (node: any, children: any) => (
        <View key={node.key}>{children}</View>
      ),
      tr: (node: any, children: any) => (
        <View key={node.key} style={{ flexDirection: 'row' }}>
          {children}
        </View>
      ),
      th: (node: any, children: any) => {
        const align = node.attributes?.align;
        return (
          <View key={node.key}
            style={{
              minWidth: 80,
              maxWidth: 200,
              paddingVertical: 6,
              paddingHorizontal: 10,
              backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)',
              borderBottomWidth: 1,
              borderColor: isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.08)',
              alignItems: align === 'center' ? 'center' : align === 'right' ? 'flex-end' : 'flex-start',
            }}
          >
            <Text style={{ fontWeight: 'bold', fontSize: 13, color: isDark ? '#e4e4e7' : '#27272a', includeFontPadding: false }}>{children}</Text>
          </View>
        );
      },
      td: (node: any, children: any) => {
        const align = node.attributes?.align;
        return (
          <View key={node.key}
            style={{
              minWidth: 80,
              maxWidth: 200,
              paddingVertical: 5,
              paddingHorizontal: 8,
              borderBottomWidth: 1,
              borderRightWidth: 1,
              borderColor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.05)',
              alignItems: align === 'center' ? 'center' : align === 'right' ? 'flex-end' : 'flex-start',
            }}
          >
            <Text style={{ fontSize: 13, color: isDark ? '#d4d4d8' : '#3f3f46', lineHeight: 20, includeFontPadding: false }}>{children}</Text>
          </View>
        );
      },

      // 6. Links
      link: (node: any, children: any) => {
        const url = node.attributes?.href || '';
        return (
          <Text
            key={node.key}
            style={{
              color: colors[500] || '#6366f1',
              textDecorationLine: 'underline',
              textDecorationStyle: 'solid',
              textDecorationColor: (colors[500] || '#6366f1') + '60',
            }}
            onPress={() => {
              if (url.startsWith('http://') || url.startsWith('https://')) {
                Linking.openURL(url);
              }
            }}
            onLongPress={async () => {
              await Clipboard.setStringAsync(url);
              setTimeout(() => {
                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
              }, 10);
            }}
          >
            {children}
            <ExternalLink size={12} color={colors[500] || '#6366f1'} style={{ marginLeft: 2 }} />
          </Text>
        );
      },
    }),
    [isDark, colors, t, setViewImageUri, GeneratedImage]
  );
};

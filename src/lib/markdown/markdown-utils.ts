import { sanitize } from '../sanitizer';

/**
 * Markdown 预处理器 — LaTeX 转换 + 通行幂等结构化间距修复 + 中文智能换行
 * （现已升级为插件化架构，此函数保留作为向前兼容的薄包装）
 */
export function preprocessMarkdown(text: string): string {
    return sanitize(text).text;
}


/**
 * Common styles for Markdown rendering
 */
export const markdownStyles = {
    body: {
        fontSize: 16,
        lineHeight: 26,
        color: '#3f3f46',
        includeFontPadding: false,
        textAlignVertical: 'center' as const,
    },
    text: {
        includeFontPadding: false,
        textAlignVertical: 'center' as const,
    },
    heading1: {
        marginTop: 24,
        marginBottom: 16,
        lineHeight: 32,
        fontWeight: '700',
        borderBottomWidth: 0.5,
        borderBottomColor: '#e4e4e7',
    },
    heading2: {
        marginTop: 24,
        marginBottom: 16,
        lineHeight: 28,
        fontWeight: '600',
        borderBottomWidth: 0.5,
        borderBottomColor: '#e4e4e7',
    },
    paragraph: {
        marginTop: 8,
        marginBottom: 8,
        lineHeight: 26,
    },
    list_item: {
        marginTop: 4,
        marginBottom: 4,
    },
};

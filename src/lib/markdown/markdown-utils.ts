import { Platform } from 'react-native';

/**
 * Pre-processes Markdown content to improve rendering specific to local models and CJK languages.
 * Inspired by logic from Cherry Studio.
 */
export function preprocessMarkdown(text: string): string {
    if (!text) return '';

    // 1. LaTeX Protection: Convert \[ \] and \( \) to $$ $$ and $ $
    // This aligns with common math notation if the model outputs LaTeX style brackets.
    let processed = text;
    processed = processed.replaceAll('\\[', '$$').replaceAll('\\]', '$$');
    processed = processed.replaceAll('\\(', '$').replaceAll('\\)', '$');

    return processed;
}

/**
 * Common styles for Markdown rendering
 */
export const markdownStyles = {
    body: {
        fontSize: 16,
        lineHeight: 24, // 1.5 * 16 (Close to Cherry Studio's 1.6)
        color: '#3f3f46', // zinc-700
    },
    heading1: {
        marginTop: 24,
        marginBottom: 16,
        lineHeight: 32,
        fontWeight: '700',
        borderBottomWidth: 0.5,
        borderBottomColor: '#e4e4e7', // zinc-200
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
        lineHeight: 24,
    },
    list_item: {
        marginTop: 4,
        marginBottom: 4,
    },
};

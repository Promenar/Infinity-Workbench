import React, { useMemo } from 'react';
import { View, StyleSheet, Dimensions } from 'react-native';
import Markdown, { MarkdownProps } from 'react-native-markdown-display';
import { useTheme } from '../../../theme/ThemeProvider';
import { Colors } from '../../../theme/colors';
import { LLM_STRUCTURED_BLOCK_REGEX, LLM_TAG_START_REGEX } from '../../../lib/llm/patterns';


interface StreamingCardListProps {
    content: string;
    markdownRules?: any;
    markdownStyles?: any;
}

/**
 * Split content into semantic cards.
 */
function splitContentIntoCards(content: string): string[] {
    if (!content) return [];

    // Split by the structured block regex
    const parts = content.split(LLM_STRUCTURED_BLOCK_REGEX);

    return parts
        .filter(part => {
            const trimmed = part.trim();
            if (!trimmed) return false;

            // Filter out blocks that match the start tags (Thinking, Tools, Plans)
            // We use a broader check to ensure we catch the block even if regex split slightly filtered it
            if (LLM_TAG_START_REGEX.test(trimmed)) {
                return false;
            }

            // Additional Safety: Check for unclosed tags or partial matches if needed?
            // For now, trust the Regex.
            return true;
        })
        .map(part => part.trim());
}

import { Typography } from '../../../components/ui/Typography';



import { StreamCard } from './StreamCard';

export const StreamingCardList: React.FC<StreamingCardListProps> = ({
    content,
    markdownRules,
    markdownStyles,
}) => {
    const cards = useMemo(() => splitContentIntoCards(content), [content]);

    if (cards.length === 0) return null;

    const showIndex = cards.length > 1;

    return (
        <View style={styles.container}>
            {cards.map((item, index) => (
                <StreamCard
                    key={`card-${index}`}
                    content={item}
                    index={index}
                    markdownRules={markdownRules}
                    markdownStyles={markdownStyles}
                    showIndex={showIndex}
                />
            ))}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
        marginTop: 4,
        minHeight: 1, // Ensure visibility
    },
});

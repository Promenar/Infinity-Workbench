import React, { useMemo } from 'react';
import { View, StyleSheet } from 'react-native';
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
 * 
 * Strategy:
 * 1. Use regex to split content by "Structured Blocks" (Thinking, Tools, Plans).
 * 2. Filter OUT the structured blocks (they are noise in the main chat value, handled by Timeline).
 * 3. Preserve everything else (Natural Language) as complete Markdown cards.
 */
function splitContentIntoCards(content: string): string[] {
    if (!content) return [];

    // Split by the structured block regex
    // The capture groups in regex will be included in the result array
    const parts = content.split(LLM_STRUCTURED_BLOCK_REGEX);

    return parts
        .filter(part => {
            const trimmed = part.trim();
            if (!trimmed) return false;

            // Filter out blocks that match the start tags
            // This effectively removes the Thinking/Tool content from this view
            if (trimmed.match(LLM_TAG_START_REGEX)) {
                return false;
            }

            return true;
        })
        .map(part => part.trim());
}

import { Typography } from '../../../components/ui/Typography';

export const StreamingCardList: React.FC<StreamingCardListProps> = ({
    content,
    markdownRules,
    markdownStyles,
}) => {
    const { isDark, colors } = useTheme();
    const cards = useMemo(() => splitContentIntoCards(content), [content]);

    if (cards.length === 0) return null;

    return (
        <View style={styles.container}>
            {cards.map((cardContent, index) => (
                <View
                    key={`card-${index}`}
                    style={styles.cardWrapper}
                >
                    {/* Left Indicator Area: Index + Theme Stripe */}
                    <View style={styles.leftIndicator}>
                        {/* THEME STRIPE: Inspired by RAG reference cards */}
                        <View
                            style={[
                                styles.sideStripe,
                                { backgroundColor: colors[500] }
                            ]}
                        />

                        {/* INDEX LABEL */}
                        <View style={styles.indexCircle}>
                            <Typography
                                style={[
                                    styles.indexText,
                                    { color: colors[500] }
                                ]}
                            >
                                {index + 1}
                            </Typography>
                        </View>
                    </View>

                    {/* MAIN CONTENT AREA */}
                    <View style={styles.contentArea}>
                        <Markdown rules={markdownRules} style={markdownStyles}>
                            {cardContent}
                        </Markdown>
                    </View>
                </View>
            ))}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
        marginTop: 4,
    },
    cardWrapper: {
        flexDirection: 'row',
        marginBottom: 16,
        alignItems: 'flex-start',
    },
    leftIndicator: {
        width: 30, // Adjusted for index display
        alignSelf: 'stretch',
        alignItems: 'center',
        marginRight: 8,
        position: 'relative',
    },
    sideStripe: {
        position: 'absolute',
        left: 0,
        top: 2,
        bottom: 2,
        width: 3,
        borderRadius: 2,
        opacity: 0.8,
    },
    indexCircle: {
        width: 18,
        height: 18,
        borderRadius: 9,
        backgroundColor: 'rgba(0,0,0,0.03)',
        alignItems: 'center',
        justifyContent: 'center',
        marginTop: 4,
        marginLeft: 6, // Offset from stripe
    },
    indexText: {
        fontSize: 10,
        fontWeight: 'bold',
    },
    contentArea: {
        flex: 1,
        paddingTop: 0,
    },
});

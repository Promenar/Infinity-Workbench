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



const CardItem = React.memo(({ item, index, colors, markdownRules, markdownStyles, showIndex }: any) => {
    return (
        <View style={styles.cardWrapper}>
            {/* Left Indicator Area: Only if showIndex is true */}
            {showIndex && (
                <View style={styles.leftIndicator}>
                    <View
                        style={[
                            styles.sideStripe,
                            { backgroundColor: colors[500] }
                        ]}
                    />
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
            )}

            {/* Content Area */}
            <View style={styles.contentArea}>
                <Markdown rules={markdownRules} style={markdownStyles}>
                    {item}
                </Markdown>
            </View>
        </View>
    );
});

export const StreamingCardList: React.FC<StreamingCardListProps> = ({
    content,
    markdownRules,
    markdownStyles,
}) => {
    const { isDark, colors } = useTheme();
    const cards = useMemo(() => splitContentIntoCards(content), [content]);

    if (cards.length === 0) return null;

    const showIndex = cards.length > 1;

    return (
        <View style={styles.container}>
            {cards.map((item, index) => (
                <CardItem
                    key={`card-${index}`}
                    item={item}
                    index={index}
                    colors={colors}
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
    cardWrapper: {
        flexDirection: 'row',
        marginBottom: 16,
        alignItems: 'flex-start',
        paddingRight: 4, // Safety padding
    },
    leftIndicator: {
        width: 30,
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
        marginLeft: 6,
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

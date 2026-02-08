import React from 'react';
import { View, StyleSheet } from 'react-native';
import Markdown from 'react-native-markdown-display';
import { Typography } from '../../../components/ui/Typography';
import { useTheme } from '../../../theme/ThemeProvider';


interface StreamCardProps {
    content: string;
    index?: number;
    showIndex?: boolean;
    /**
     * Override the default accent color (usually theme primary).
     * Used for Error cards (Red) or other special states.
     */
    accentColor?: string;
    /**
     * Override the index number display.
     * e.g., "!" or "ERR"
     */
    indexLabel?: string;

    markdownRules?: any;
    markdownStyles?: any;
    onLinkPress?: (url: string) => boolean;
}

export const StreamCard = React.memo(({
    content,
    index = 0,
    showIndex = true,
    accentColor,
    indexLabel,
    markdownRules,
    markdownStyles,
    onLinkPress
}: StreamCardProps) => {
    const { colors } = useTheme();

    // Default to primary[500] if no accent color provided
    const effectiveAccentColor = accentColor || colors[500];
    const displayLabel = indexLabel || String(index + 1);

    return (
        <View style={styles.cardWrapper}>
            {/* Left Indicator Area */}
            {showIndex && (
                <View style={styles.leftIndicator}>
                    <View
                        style={[
                            styles.sideStripe,
                            { backgroundColor: effectiveAccentColor }
                        ]}
                    />
                    <View style={styles.indexCircle}>
                        <Typography
                            style={[
                                styles.indexText,
                                { color: effectiveAccentColor }
                            ]}
                        >
                            {displayLabel}
                        </Typography>
                    </View>
                </View>
            )}

            {/* Content Area */}
            <View style={styles.contentArea}>
                <Markdown
                    rules={markdownRules}
                    style={markdownStyles}
                    onLinkPress={onLinkPress}

                >
                    {content}
                </Markdown>
            </View>
        </View>
    );
});

const styles = StyleSheet.create({
    cardWrapper: {
        flexDirection: 'row',
        marginBottom: 16,
        alignItems: 'flex-start',
        paddingRight: 4,
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
        width: 20, // Slightly larger to fit text/icons
        height: 20,
        borderRadius: 10,
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

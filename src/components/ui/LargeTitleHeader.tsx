import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Colors } from '../../theme/colors';
import { useTheme } from '../../theme/ThemeProvider';

interface LargeTitleHeaderProps {
    title: string;
    subtitle?: string;
    rightElement?: React.ReactNode;
}

export function LargeTitleHeader({ title, subtitle, rightElement }: LargeTitleHeaderProps) {
    const { isDark } = useTheme();
    const themeColors = isDark ? Colors.dark : Colors.light;

    return (
        <View style={styles.container}>
            <View style={styles.content}>
                <View>
                    <Text style={[
                        styles.title,
                        { color: themeColors.textPrimary }
                    ]}>
                        {title}
                    </Text>
                    {subtitle && (
                        <Text style={[
                            styles.subtitle,
                            { color: themeColors.textSecondary }
                        ]}>
                            {subtitle}
                        </Text>
                    )}
                </View>
                {rightElement && (
                    <View style={styles.rightElement}>
                        {rightElement}
                    </View>
                )}
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        paddingTop: 64,
        paddingBottom: 8,
        paddingHorizontal: 24,
    },
    content: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        height: 56,
        marginBottom: 24,
    },
    title: {
        fontSize: 32,
        fontWeight: '900',
        letterSpacing: -1.5,
        lineHeight: 38,
    },
    subtitle: {
        fontSize: 11,
        fontWeight: 'bold',
        textTransform: 'uppercase',
        letterSpacing: 2,
        marginTop: 4,
        lineHeight: 11,
    },
    rightElement: {
        justifyContent: 'center',
    }
});

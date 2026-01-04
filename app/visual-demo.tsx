import React from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { SilkyGlow } from '../src/components/ui/SilkyGlow';
import { ParticleEnergyGlow } from '../src/components/demo/ParticleGlow';
import { useTheme } from '../src/theme/ThemeProvider';
import { Stack } from 'expo-router';

export default function DemoPage() {
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    const colors = {
        background: isDark ? '#000000' : '#FFFFFF',
        text: isDark ? '#FFFFFF' : '#000000',
        textSecondary: isDark ? '#A1A1AA' : '#52525B',
    };

    return (
        <View style={[styles.container, { backgroundColor: colors.background }]}>
            <Stack.Screen options={{ title: 'Visual Effects Demo', headerBackTitle: 'Settings' }} />
            <ScrollView contentContainerStyle={[styles.scrollContent, { paddingTop: insets.top }]}>
                <Text style={[styles.title, { color: colors.text }]}>Visual Effects</Text>
                <Text style={[styles.subtitle, { color: colors.textSecondary }]}>
                    Hidden Debug & verification Console
                </Text>

                {/* Section 1: SilkyGlow (Isolated) */}
                <View style={styles.section}>
                    <Text style={[styles.sectionTitle, { color: colors.text }]}>1. SilkyGlow (Legacy)</Text>
                    <View style={[styles.demoBox, { borderColor: isDark ? '#333' : '#ddd' }]}>
                        <SilkyGlow color="#8b5cf6" size={160} />
                        <View style={{ position: 'absolute', width: 64, height: 64, borderRadius: 32, backgroundColor: '#8b5cf6', opacity: 0.3 }} />
                    </View>
                </View>

                {/* Section 2: Particle Energy Glow */}
                <View style={styles.section}>
                    <Text style={[styles.sectionTitle, { color: colors.text }]}>2. Particle Energy Glow</Text>
                    <View style={[styles.demoBox, { borderColor: isDark ? '#333' : '#ddd' }]}>
                        <ParticleEnergyGlow size={200} color="#06b6d4" />
                    </View>
                </View>

                {/* Section 3: Clipping Test */}
                <View style={styles.section}>
                    <Text style={[styles.sectionTitle, { color: colors.text }]}>3. Clipping Test (Overflow Visible)</Text>
                    <View style={[styles.demoBox, { borderColor: 'red', borderWidth: 1, overflow: 'visible', width: 100, height: 100 }]}>
                        <ParticleEnergyGlow color="#ef4444" size={160} style={{ position: 'absolute' }} />
                    </View>
                </View>

            </ScrollView>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
    },
    scrollContent: {
        padding: 20,
        paddingBottom: 100,
    },
    title: {
        fontSize: 28,
        fontWeight: 'bold',
        marginBottom: 8,
    },
    subtitle: {
        fontSize: 16,
        marginBottom: 24,
    },
    section: {
        marginBottom: 32,
    },
    sectionTitle: {
        fontSize: 18,
        fontWeight: '600',
        marginBottom: 16,
    },
    demoBox: {
        width: '100%',
        height: 250,
        borderWidth: 1,
        borderRadius: 16,
        alignItems: 'center',
        justifyContent: 'center',
        borderStyle: 'dashed',
    },
});

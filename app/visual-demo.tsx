import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Alert } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { SilkyGlow } from '../src/components/ui/SilkyGlow';
import { ParticleEnergyGlow } from '../src/components/ui';
import { useTheme } from '../src/theme/ThemeProvider';
import { Stack, useRouter } from 'expo-router';
import { useSettingsStore } from '../src/store/settings-store';

export default function DemoPage() {
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const router = useRouter();
    const { setHasLaunched } = useSettingsStore();

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

                {/* Section 3: WebView Renderer Lab */}
                <View style={styles.section}>
                    <Text style={[styles.sectionTitle, { color: colors.text }]}>3. WebView Renderer Lab</Text>
                    <View style={[styles.demoBox, { borderColor: isDark ? '#333' : '#ddd', height: 'auto', padding: 20 }]}>
                        <Text style={{ color: colors.textSecondary, fontSize: 14, marginBottom: 16, textAlign: 'center' }}>
                            单一 WebView 聊天渲染器 POC — Bridge 通信 + Markdown + 主题 + 流式输出
                        </Text>
                        <TouchableOpacity
                            onPress={() => router.push('/webview-renderer-demo')}
                            style={[localStyles.button, { backgroundColor: '#6366f1' }]}
                        >
                            <Text style={localStyles.buttonText}>进入 WebView Renderer Lab</Text>
                        </TouchableOpacity>
                    </View>
                </View>

                {/* Section 4: Welcome Screen Debug */}
                <View style={styles.section}>
                    <Text style={[styles.sectionTitle, { color: colors.text }]}>4. Welcome Screen Debug</Text>
                    <View style={[styles.demoBox, { borderColor: isDark ? '#333' : '#ddd', height: 'auto', padding: 20 }]}>
                        <TouchableOpacity
                            onPress={() => {
                                setHasLaunched(false);
                                Alert.alert('Success', 'HasLaunched status reset to FALSE. Restart app or click below to test.');
                            }}
                            style={[localStyles.button, { backgroundColor: '#ef4444', marginBottom: 12 }]}
                        >
                            <Text style={localStyles.buttonText}>Reset Launch State</Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            onPress={() => router.push('/welcome')}
                            style={[localStyles.button, { backgroundColor: colors.text }]}
                        >
                            <Text style={[localStyles.buttonText, { color: colors.background }]}>Go to Welcome Page</Text>
                        </TouchableOpacity>
                    </View>
                </View>

            </ScrollView>
        </View>
    );
}


const localStyles = StyleSheet.create({
    button: {
        paddingHorizontal: 20,
        paddingVertical: 12,
        borderRadius: 8,
        width: '100%',
        alignItems: 'center',
    },
    buttonText: {
        color: 'white',
        fontWeight: 'bold',
        fontSize: 16,
    }
});

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

import React, { useEffect, useState } from 'react';
import { useRouter } from 'expo-router';
import { View, Text, TouchableOpacity, ScrollView, ActivityIndicator } from 'react-native';
import { PageLayout, LargeTitleHeader, Switch } from '../../src/components/ui';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useWorkbenchStore } from '../../src/store/workbench-store';
import { staticServerService } from '../../src/services/workbench/StaticServerService';
import { commandWebSocketServer } from '../../src/services/workbench/CommandWebSocketServer';
import { Colors } from '../../src/theme/colors';
import { Monitor, Server, Wifi, Smartphone, Users, Globe, Lock, RefreshCw, Copy, ArrowLeft } from 'lucide-react-native';
import * as Clipboard from 'expo-clipboard';
import { useToast } from '../../src/components/ui/Toast';
import * as Haptics from '../../src/lib/haptics';
// import { generateSecureRandom } from 'react-native-securerandom';

// Simple random 6 digit code generator
const generateAccessCode = () => {
    return Math.floor(100000 + Math.random() * 900000).toString();
};

export default function PortableWorkbenchScreen() {
    const router = useRouter();
    const { isDark } = useTheme();
    const { showToast } = useToast();
    const {
        serverStatus,
        serverUrl,
        accessCode,
        connectedClients,
        setAccessCode,
        incrementClients,
        decrementClients
    } = useWorkbenchStore();

    const [loading, setLoading] = useState(false);

    const toggleServer = async (value: boolean) => {
        setLoading(true);
        try {
            if (value) {
                // Determine Access Code
                const newCode = generateAccessCode();
                setAccessCode(newCode);

                // Start Services
                await staticServerService.start();
                commandWebSocketServer.start();

                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                showToast('Workbench Server Started', 'success');
            } else {
                // Stop Services
                await staticServerService.stop();
                commandWebSocketServer.stop();

                Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                showToast('Workbench Server Stopped', 'info');
            }
        } catch (error) {
            console.error(error);
            showToast('Failed to toggle server', 'error');
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        } finally {
            setLoading(false);
        }
    };

    const copyToClipboard = async (text: string | null) => {
        if (!text) return;
        await Clipboard.setStringAsync(text);
        showToast('Copied to Clipboard', 'success');
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    };

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <LargeTitleHeader
                title="Portable Workbench"
                subtitle="Control Nexara from your PC"
                leftAction={{
                    icon: <ArrowLeft size={24} color={isDark ? '#fff' : '#111'} />,
                    onPress: () => router.back()
                }}
            />

            <ScrollView className="flex-1 px-4 pt-4" contentContainerStyle={{ paddingBottom: 100 }}>
                {/* Status Card */}
                <View style={{
                    backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#f3f4f6',
                    borderRadius: 24,
                    padding: 24,
                    marginBottom: 24,
                    alignItems: 'center',
                    justifyContent: 'center',
                    borderWidth: 1,
                    borderColor: isDark ? Colors.dark.borderDefault : '#e5e7eb',
                }}>
                    <View style={{
                        width: 80,
                        height: 80,
                        borderRadius: 40,
                        backgroundColor: serverStatus === 'running'
                            ? (isDark ? 'rgba(34, 197, 94, 0.2)' : '#dcfce7')
                            : (isDark ? 'rgba(107, 114, 128, 0.2)' : '#f3f4f6'),
                        alignItems: 'center',
                        justifyContent: 'center',
                        marginBottom: 16
                    }}>
                        {loading ? (
                            <ActivityIndicator size="large" color={Colors.primary} />
                        ) : (
                            <Monitor
                                size={40}
                                color={serverStatus === 'running' ? '#22c55e' : (isDark ? '#6b7280' : '#9ca3af')}
                            />
                        )}
                    </View>

                    <Text style={{
                        fontSize: 24,
                        fontWeight: 'bold',
                        color: isDark ? '#fff' : '#111',
                        marginBottom: 4
                    }}>
                        {serverStatus === 'running' ? 'Active' : (serverStatus === 'starting' ? 'Starting...' : 'Inactive')}
                    </Text>
                    <Text style={{
                        fontSize: 14,
                        color: isDark ? '#9ca3af' : '#6b7280',
                        textAlign: 'center'
                    }}>
                        {serverStatus === 'running'
                            ? 'Web interface is ready to connect'
                            : 'Start the server to access from PC'}
                    </Text>

                    <View style={{ marginTop: 24, width: '100%' }}>
                        <View style={{
                            flexDirection: 'row',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#fff',
                            padding: 16,
                            borderRadius: 16
                        }}>
                            <Text style={{ color: isDark ? '#fff' : '#111', fontWeight: '600' }}>Enable Server</Text>
                            <Switch value={serverStatus === 'running' || serverStatus === 'starting'} onValueChange={toggleServer} disabled={loading} />
                        </View>
                    </View>
                </View>

                {/* Connection Details */}
                {serverStatus === 'running' && (
                    <View style={{ gap: 16 }}>
                        {/* URL Section */}
                        <View style={{
                            backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#f9fafb',
                            padding: 20,
                            borderRadius: 20,
                            borderWidth: 1,
                            borderColor: isDark ? Colors.dark.borderDefault : '#e5e7eb',
                        }}>
                            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
                                <Globe size={20} color={Colors.primary} style={{ marginRight: 8 }} />
                                <Text style={{ color: isDark ? '#d1d5db' : '#4b5563', fontWeight: '600' }}>
                                    Browser Address
                                </Text>
                            </View>

                            <TouchableOpacity
                                onPress={() => copyToClipboard(serverUrl)}
                                style={{
                                    flexDirection: 'row',
                                    alignItems: 'center',
                                    justifyContent: 'space-between',
                                    backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#fff',
                                    padding: 16,
                                    borderRadius: 12
                                }}
                            >
                                <Text style={{ fontSize: 18, color: Colors.primary, fontWeight: 'bold' }}>
                                    {serverUrl}
                                </Text>
                                <Copy size={18} color={isDark ? '#9ca3af' : '#6b7280'} />
                            </TouchableOpacity>

                            <Text style={{ marginTop: 8, fontSize: 12, color: isDark ? '#6b7280' : '#9ca3af' }}>
                                Type this exactly in your PC browser address bar
                            </Text>
                        </View>

                        {/* Auth Code Section */}
                        <View style={{
                            backgroundColor: isDark ? Colors.dark.surfaceSecondary : '#f9fafb',
                            padding: 20,
                            borderRadius: 20,
                            borderWidth: 1,
                            borderColor: isDark ? Colors.dark.borderDefault : '#e5e7eb',
                        }}>
                            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
                                <Lock size={20} color="#f59e0b" style={{ marginRight: 8 }} />
                                <Text style={{ color: isDark ? '#d1d5db' : '#4b5563', fontWeight: '600' }}>
                                    Access Code
                                </Text>
                            </View>

                            <View style={{
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'space-between'
                            }}>
                                <View style={{
                                    flexDirection: 'row',
                                    gap: 8
                                }}>
                                    {accessCode?.split('').map((digit, i) => (
                                        <View key={i} style={{
                                            width: 40,
                                            height: 48,
                                            backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#fff',
                                            alignItems: 'center',
                                            justifyContent: 'center',
                                            borderRadius: 8,
                                            borderWidth: 1,
                                            borderColor: isDark ? '#4b5563' : '#d1d5db'
                                        }}>
                                            <Text style={{ fontSize: 24, fontWeight: 'bold', color: isDark ? '#fff' : '#111' }}>
                                                {digit}
                                            </Text>
                                        </View>
                                    ))}
                                </View>

                                <TouchableOpacity
                                    onPress={() => {
                                        const newCode = generateAccessCode();
                                        setAccessCode(newCode);
                                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                                    }}
                                    style={{
                                        padding: 12,
                                        backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#fff',
                                        borderRadius: 12
                                    }}
                                >
                                    <RefreshCw size={20} color={isDark ? '#fff' : '#111'} />
                                </TouchableOpacity>
                            </View>

                            <Text style={{ marginTop: 12, fontSize: 12, color: isDark ? '#6b7280' : '#9ca3af' }}>
                                You will need this code to log in on the PC
                            </Text>
                        </View>

                        {/* Clients */}
                        <View style={{
                            flexDirection: 'row',
                            alignItems: 'center',
                            justifyContent: 'center',
                            marginTop: 8
                        }}>
                            <Users size={16} color={connectedClients > 0 ? '#22c55e' : (isDark ? '#6b7280' : '#9ca3af')} style={{ marginRight: 6 }} />
                            <Text style={{ color: isDark ? '#d1d5db' : '#6b7280' }}>
                                {connectedClients} Device{connectedClients !== 1 ? 's' : ''} Connected
                            </Text>
                        </View>

                    </View>
                )}
            </ScrollView>
        </PageLayout>
    );
}

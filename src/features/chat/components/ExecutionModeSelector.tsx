import React, { useRef, useState } from 'react';
import { View, TouchableOpacity, Text, Modal, Animated } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { useChatStore } from '../../../store/chat-store';
import { Typography } from '../../../components/ui/Typography';
import { Settings, Zap, Shield, PlayCircle } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';

interface ExecutionModeSelectorProps {
    sessionId: string;
}

export const ExecutionModeSelector = ({ sessionId }: ExecutionModeSelectorProps) => {
    const { isDark, colors } = useTheme();
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
    const setExecutionMode = useChatStore(s => s.setExecutionMode);

    const [visible, setVisible] = useState(false);

    if (!session) return null;
    const mode = session.executionMode || 'semi';

    const getIcon = (m: string) => {
        switch (m) {
            case 'auto': return <Zap size={16} color="#6366f1" />;
            case 'semi': return <Shield size={16} color="#d97706" />;
            case 'manual': return <PlayCircle size={16} color="#059669" />;
            default: return <Settings size={16} color={isDark ? 'rgba(255,255,255,0.6)' : 'rgba(0,0,0,0.6)'} />;
        }
    };

    const getLabel = (m: string) => {
        switch (m) {
            case 'auto': return 'Auto';
            case 'semi': return 'Semi';
            case 'manual': return 'Manual';
            default: return m;
        }
    };

    const handleSelect = (m: 'auto' | 'semi' | 'manual') => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        setExecutionMode(sessionId, m);
        setVisible(false);
    };

    return (
        <>
            <TouchableOpacity
                onPress={() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    setVisible(true);
                }}
                style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)',
                    paddingHorizontal: 8,
                    paddingVertical: 4,
                    borderRadius: 12,
                    gap: 4
                }}
            >
                {getIcon(mode)}
                <Typography variant="caption" style={{ color: isDark ? 'rgba(255,255,255,0.6)' : 'rgba(0,0,0,0.6)', fontSize: 12, fontWeight: '600' }}>
                    {getLabel(mode)}
                </Typography>
            </TouchableOpacity>

            <Modal
                visible={visible}
                transparent
                animationType="fade"
                onRequestClose={() => setVisible(false)}
            >
                <TouchableOpacity
                    style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'center', alignItems: 'center' }}
                    activeOpacity={1}
                    onPress={() => setVisible(false)}
                >
                    <View style={{
                        width: 280,
                        backgroundColor: isDark ? '#1e1e1e' : '#ffffff',
                        borderRadius: 16,
                        padding: 16,
                        gap: 12,
                        shadowColor: "#000",
                        shadowOffset: { width: 0, height: 4 },
                        shadowOpacity: 0.3,
                        shadowRadius: 8,
                        elevation: 10
                    }}>
                        <Typography variant="h3" style={{ marginBottom: 4 }}>Execution Mode</Typography>

                        <TouchableOpacity onPress={() => handleSelect('auto')} style={{ flexDirection: 'row', alignItems: 'center', padding: 12, borderRadius: 12, backgroundColor: mode === 'auto' ? (isDark ? 'rgba(255,255,255,0.1)' : '#f3f4f6') : 'transparent', gap: 12 }}>
                            <View style={{ padding: 8, borderRadius: 8, backgroundColor: 'rgba(99, 102, 241, 0.1)' }}>
                                <Zap size={20} color="#6366f1" />
                            </View>
                            <View style={{ flex: 1 }}>
                                <Typography variant="body" style={{ fontWeight: '600' }}>Auto (Default)</Typography>
                                <Typography variant="caption" color="secondary">Run continuously. Best for research.</Typography>
                            </View>
                        </TouchableOpacity>

                        <TouchableOpacity onPress={() => handleSelect('semi')} style={{ flexDirection: 'row', alignItems: 'center', padding: 12, borderRadius: 12, backgroundColor: mode === 'semi' ? (isDark ? 'rgba(255,255,255,0.1)' : '#f3f4f6') : 'transparent', gap: 12 }}>
                            <View style={{ padding: 8, borderRadius: 8, backgroundColor: 'rgba(217, 119, 6, 0.1)' }}>
                                <Shield size={20} color="#d97706" />
                            </View>
                            <View style={{ flex: 1 }}>
                                <Typography variant="body" style={{ fontWeight: '600' }}>Semi-Auto</Typography>
                                <Typography variant="caption" color="secondary">Pause on high-risk actions (write/run).</Typography>
                            </View>
                        </TouchableOpacity>

                        <TouchableOpacity onPress={() => handleSelect('manual')} style={{ flexDirection: 'row', alignItems: 'center', padding: 12, borderRadius: 12, backgroundColor: mode === 'manual' ? (isDark ? 'rgba(255,255,255,0.1)' : '#f3f4f6') : 'transparent', gap: 12 }}>
                            <View style={{ padding: 8, borderRadius: 8, backgroundColor: 'rgba(16, 185, 129, 0.1)' }}>
                                <PlayCircle size={20} color="#059669" />
                            </View>
                            <View style={{ flex: 1 }}>
                                <Typography variant="body" style={{ fontWeight: '600' }}>Manual</Typography>
                                <Typography variant="caption" color="secondary">Ask approval for every step.</Typography>
                            </View>
                        </TouchableOpacity>

                    </View>
                </TouchableOpacity>
            </Modal>
        </>
    );
};

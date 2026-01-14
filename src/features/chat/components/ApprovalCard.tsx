import React from 'react';
import { View, TouchableOpacity, ViewStyle } from 'react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { useChatStore } from '../../../store/chat-store';
import { Typography } from '../../../components/ui/Typography';
import { Play, XCircle, AlertTriangle } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';

interface ApprovalCardProps {
    sessionId: string;
    containerStyle?: ViewStyle;
}

export const ApprovalCard = ({ sessionId, containerStyle }: ApprovalCardProps) => {
    const { isDark, colors } = useTheme();
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
    const resumeGeneration = useChatStore(s => s.resumeGeneration);

    if (!session || session.loopStatus !== 'waiting_for_approval' || !session.approvalRequest) return null;

    const { toolName, args, reason } = session.approvalRequest;

    const handleApprove = () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        resumeGeneration(sessionId, true);
    };

    const handleReject = () => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        resumeGeneration(sessionId, false);
    };

    return (
        <View style={[{
            marginTop: 8,
            marginBottom: 8,
            padding: 12,
            borderRadius: 12,
            backgroundColor: isDark ? 'rgba(217, 119, 6, 0.1)' : '#fffbeb', // Amber tint
            borderWidth: 1,
            borderColor: isDark ? 'rgba(217, 119, 6, 0.3)' : '#fcd34d',
        }, containerStyle]}>

            {/* Header */}
            <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 8, gap: 8 }}>
                <AlertTriangle size={18} color="#d97706" />
                <Typography variant="body" style={{ fontWeight: '700', color: '#d97706' }}>
                    Action Approval Required
                </Typography>
            </View>

            {/* Content */}
            <View style={{ marginBottom: 12 }}>
                <Typography variant="caption" color="secondary" style={{ marginBottom: 4 }}>
                    Reason: {reason || 'High-risk action detected.'}
                </Typography>
                <View style={{
                    backgroundColor: isDark ? 'rgba(0,0,0,0.3)' : '#fff',
                    padding: 8,
                    borderRadius: 8,
                    borderWidth: 1,
                    borderColor: isDark ? 'rgba(255,255,255,0.1)' : '#e5e7eb'
                }}>
                    <Typography variant="body" style={{ fontSize: 13, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' }}>
                        Tool: {toolName}
                    </Typography>
                    {args && args.length > 0 && (
                        <Typography variant="body" style={{ fontSize: 12, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', opacity: 0.8, marginTop: 4 }}>
                            {JSON.stringify(args, null, 2).slice(0, 100) + (JSON.stringify(args).length > 100 ? '...' : '')}
                        </Typography>
                    )}
                </View>
            </View>

            {/* Actions */}
            <View style={{ flexDirection: 'row', gap: 12 }}>
                <TouchableOpacity
                    onPress={handleReject}
                    style={{
                        flex: 1,
                        flexDirection: 'row',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: 10,
                        borderRadius: 8,
                        backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#fff',
                        borderWidth: 1,
                        borderColor: isDark ? 'rgba(255,255,255,0.1)' : '#e5e7eb',
                        gap: 6
                    }}
                >
                    <XCircle size={16} color={isDark ? 'rgba(255,255,255,0.6)' : 'rgba(0,0,0,0.6)'} />
                    <Typography variant="body" color="secondary" style={{ fontWeight: '600' }}>Reject</Typography>
                </TouchableOpacity>

                <TouchableOpacity
                    onPress={handleApprove}
                    style={{
                        flex: 1,
                        flexDirection: 'row',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: 10,
                        borderRadius: 8,
                        backgroundColor: '#d97706',
                        gap: 6
                    }}
                >
                    <Play size={16} color="#fff" fill="#fff" />
                    <Typography variant="body" style={{ fontWeight: '700', color: '#fff' }}>Approve & Run</Typography>
                </TouchableOpacity>
            </View>
        </View>
    );
};

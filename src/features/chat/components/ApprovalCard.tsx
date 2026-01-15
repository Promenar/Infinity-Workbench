import React, { useState } from 'react';
import { View, TouchableOpacity, TextInput, ViewStyle, Platform } from 'react-native';
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

    // 介入指令状态
    const [interventionText, setInterventionText] = useState('');

    if (!session || session.loopStatus !== 'waiting_for_approval' || !session.approvalRequest) return null;

    const { toolName, args, reason } = session.approvalRequest;

    const handleApprove = () => {
        // 原生桥接延迟防护
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        }, 10);
        resumeGeneration(sessionId, true, interventionText.trim() || undefined);
    };

    const handleReject = () => {
        // 原生桥接延迟防护
        setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        }, 10);
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

            {/* 介入输入框 */}
            <View style={{ marginBottom: 12 }}>
                <Typography variant="caption" color="secondary" style={{ marginBottom: 6 }}>
                    可选：提供修改指令以调整执行行为
                </Typography>
                <TextInput
                    value={interventionText}
                    onChangeText={setInterventionText}
                    placeholder="例如: '仅写入 /tmp 目录' 或 '使用安全模式'"
                    placeholderTextColor={isDark ? 'rgba(255,255,255,0.4)' : 'rgba(0,0,0,0.4)'}
                    multiline
                    numberOfLines={2}
                    style={{
                        backgroundColor: isDark ? 'rgba(0,0,0,0.3)' : '#fff',
                        borderRadius: 8,
                        padding: 10,
                        borderWidth: 1,
                        borderColor: isDark ? 'rgba(255,255,255,0.15)' : '#e5e7eb',
                        color: isDark ? '#ffffff' : '#18181b',
                        fontSize: 14,
                        lineHeight: 20,
                        minHeight: 44,
                        textAlignVertical: 'top'
                    }}
                />
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
                    <Typography variant="body" style={{ fontWeight: '700', color: '#fff' }}>
                        {interventionText.trim() ? '携带指令批准' : '批准并执行'}
                    </Typography>
                </TouchableOpacity>
            </View>
        </View>
    );
};

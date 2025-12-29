import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ActivityIndicator, Platform } from 'react-native';
import { Brain, ChevronDown, ChevronUp, Database, FileText, Check } from 'lucide-react-native';
import { useRagStore } from '../../../store/rag-store';
import { useTheme } from '../../../theme/ThemeProvider';
import Animated, { FadeInUp, FadeOutUp, LinearTransition } from 'react-native-reanimated';

interface ProcessingIndicatorChipProps {
    sessionId?: string;
    messageId: string; // ID of the current message
    isDark: boolean;
    expanded: boolean;
    onToggle: () => void;
    // Added to force update when store changes
    status: string;
}

/**
 * 记忆处理状态胶囊 - 仅显示入口
 */
export const ProcessingIndicatorChip: React.FC<ProcessingIndicatorChipProps> = ({
    sessionId: propSessionId,
    messageId,
    isDark,
    expanded,
    onToggle,
    status
}) => {
    const { processingState, processingHistory } = useRagStore();
    const { sessionId } = processingState;

    // Check if this specific message has completed processing
    const messageHistory = processingHistory[messageId];
    // Active processing only shows if message ID matches the active task in store
    const isActiveProcessing = status !== 'idle' && propSessionId === sessionId && messageId === processingState.activeMessageId;

    // Show chip if actively processing this session OR this message has history
    if (!isActiveProcessing && !messageHistory) return null;

    // Determine display mode
    const showCompleted = messageHistory && !isActiveProcessing;
    const isSummarized = messageHistory?.type === 'summarized';

    // Display settings to match ActionBar buttons
    const iconSize = 16;
    const btnStyle = "p-2 mx-1"; // This is a string, not directly applied as style
    const iconColor = showCompleted
        ? (isSummarized ? (isDark ? '#60a5fa' : '#3b82f6') : (isDark ? '#34d399' : '#10b981'))
        : (status === 'completed' ? (isDark ? '#34d399' : '#10b981') : (isDark ? '#38bdf8' : '#0ea5e9'));

    // Color scheme (some of these might be redundant after new iconColor)
    const completedColor = isSummarized ? '#3b82f6' : '#10b981'; // Blue for summarized, Green for archived
    const completedColorDark = isSummarized ? '#60a5fa' : '#34d399';

    const bgColor = showCompleted
        ? (isDark ? 'rgba(255, 255, 255, 0.03)' : 'rgba(0, 0, 0, 0.02)')
        : (isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)');

    const baseColor = showCompleted ? completedColor : (status === 'completed' ? '#10b981' : '#0ea5e9');
    const darkBaseColor = showCompleted ? completedColorDark : (status === 'completed' ? '#34d399' : '#38bdf8');

    const borderColor = expanded || showCompleted
        ? (isDark ? `rgba(${showCompleted ? (isSummarized ? '96, 165, 250' : '52, 211, 153') : '52, 211, 153'}, 0.4)` : baseColor)
        : (isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0,0,0,0.05)');

    const textColor = expanded || showCompleted
        ? (isDark ? darkBaseColor : baseColor)
        : (isDark ? '#a1a1aa' : '#64748b');

    const subTextColor = isDark ? '#71717a' : '#94a3b8';

    const getStatusText = () => {
        if (showCompleted) {
            if (isSummarized) return '已完成记忆压缩';
            return '已完成背景归档'; // Default for 'archived' type
        }
        switch (status) {
            case 'chunking': return '正在归档上下文...';
            case 'summarizing': return '正在压缩长期记忆...';
            case 'completed': return '已完成背景归档';
            default: return '记忆处理中...';
        }
    };

    // console.log(`[ProcessingIndicator] Render ${messageId}: active=${isActiveProcessing}, history=${messageHistory?.type}, status=${status}`);

    if (showCompleted) {
        return (
            <TouchableOpacity
                activeOpacity={0.7}
                onPress={onToggle} // Allow expansion to see summary
                style={{ padding: 8, marginHorizontal: 4 }}
            >
                {isSummarized ? (
                    <Brain size={iconSize} color={iconColor} strokeWidth={2.5} />
                ) : (
                    <Check size={iconSize} color={iconColor} strokeWidth={2.5} />
                )}
            </TouchableOpacity>
        );
    }

    return (
        <TouchableOpacity
            activeOpacity={0.7}
            onPress={onToggle}
            style={[
                styles.capsule,
                {
                    backgroundColor: bgColor,
                    borderColor: borderColor,
                },
            ]}
        >
            <View style={styles.header}>
                {status === 'completed' ? (
                    <Check size={iconSize} color={iconColor} strokeWidth={2.5} />
                ) : (
                    <ActivityIndicator size="small" color={iconColor} />
                )}
                <Text style={[styles.statusText, { color: textColor }]}>
                    {getStatusText()}
                </Text>
                {expanded ? (
                    <ChevronUp size={iconSize} color={textColor} />
                ) : (
                    <ChevronDown size={iconSize} color={textColor} />
                )}
            </View>
            {/* Details view is handled by ChatBubble separately to maintain layout consistency */}
        </TouchableOpacity>
    );
};

interface ProcessingIndicatorDetailsProps {
    isDark: boolean;
    status: string;
    messageId: string;
}

/**
 * 记忆处理详情列表 - 风格对齐 RagReferencesList
 */
export const ProcessingIndicatorDetails: React.FC<ProcessingIndicatorDetailsProps> = ({ isDark, status, messageId }) => {
    const { processingState, processingHistory } = useRagStore();
    const { chunks, summary: activeSummary } = processingState;

    // Check history for this message
    const history = processingHistory[messageId];
    const summary = activeSummary || history?.summary;

    // Only show if there's active data OR historical summary OR chunkCount
    if (status === 'idle' && !history?.summary && !history?.chunkCount) return null;

    const textColor = isDark ? '#e5e5e5' : '#18181b';
    const subTextColor = isDark ? '#a1a1aa' : '#64748b';

    return (
        <Animated.View
            entering={FadeInUp.duration(300)}
            exiting={FadeOutUp.duration(200)}
            style={{ marginTop: 12, width: '100%' }}
        >
            {/* Summary Preview */}
            {summary && (
                <View style={[styles.detailsBlock, {
                    backgroundColor: isDark ? 'rgba(59, 130, 246, 0.1)' : '#f0f7ff',
                    borderLeftColor: '#3b82f6'
                }]}>
                    <View style={styles.sectionHeader}>
                        <Brain size={12} color="#3b82f6" />
                        <Text style={[styles.sectionTitle, { color: '#3b82f6' }]}>
                            {history?.type === 'summarized' ? '核心摘要' : '摘要生成中...'}
                        </Text>
                    </View>
                    <Text style={[styles.chunkContent, { color: textColor }]}>
                        {summary}
                    </Text>
                </View>
            )}

            {/* Archival Status (Green Checkmark Details) */}
            {history?.type === 'archived' && !activeSummary && (
                <View style={[styles.detailsBlock, {
                    backgroundColor: isDark ? 'rgba(16, 185, 129, 0.1)' : '#f0fdf4',
                    borderLeftColor: '#10b981'
                }]}>
                    <View style={styles.sectionHeader}>
                        <Database size={12} color="#10b981" />
                        <Text style={[styles.sectionTitle, { color: '#10b981' }]}>背景归档已完成</Text>
                    </View>
                    <Text style={[styles.chunkContent, { color: textColor, fontSize: 12 }]}>
                        消息已成功切片 ({history.chunkCount || 0} 个切片) 并存入向量数据库，以便后续检索参考。
                    </Text>
                </View>
            )}

            {/* Chunks Preview - Only for active processing of this specific message */}
            {status !== 'idle' && chunks.length > 0 && processingState.activeMessageId === messageId && (
                <View style={[styles.detailsBlock, {
                    backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : '#f9fafb',
                    borderLeftColor: isDark ? '#10b981' : '#059669'
                }]}>
                    <View style={styles.sectionHeader}>
                        <FileText size={12} color={subTextColor} />
                        <Text style={[styles.sectionTitle, { color: subTextColor }]}>最近切片 ({chunks.length})</Text>
                    </View>
                    {chunks.map((chunk, i) => (
                        <View key={i} style={styles.chunkItem}>
                            <Text style={[styles.chunkIndex, { color: '#94a3b8' }]}>#{i + 1}</Text>
                            <Text style={[styles.chunkContent, { color: textColor }]} numberOfLines={3}>{chunk}</Text>
                        </View>
                    ))}
                </View>
            )}
        </Animated.View>
    );
};

const styles = StyleSheet.create({
    wrapper: {
        alignItems: 'center',
        marginVertical: 8,
        paddingHorizontal: 16,
    },
    capsule: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 5,
        paddingHorizontal: 10,
        borderRadius: 20,
        borderWidth: 1,
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
    },
    statusText: {
        fontSize: 11,
        fontWeight: '600',
    },
    detailsBlock: {
        padding: 12,
        borderRadius: 12,
        borderLeftWidth: 3,
        borderWidth: Platform.OS === 'android' ? 1 : 0,
        borderColor: 'rgba(0,0,0,0.05)',
    },
    section: {
        marginBottom: 12,
    },
    sectionHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 8,
        gap: 6,
    },
    sectionTitle: {
        fontSize: 10,
        fontWeight: 'bold',
        textTransform: 'uppercase',
    },
    chunkItem: {
        flexDirection: 'row',
        marginBottom: 4,
        gap: 6,
    },
    chunkIndex: {
        fontSize: 10,
        fontFamily: 'monospace',
        marginTop: 2,
    },
    chunkContent: {
        fontSize: 11,
        flex: 1,
        lineHeight: 16,
    },
    summaryText: {
        fontSize: 12,
        lineHeight: 18,
        fontStyle: 'italic',
    }
});

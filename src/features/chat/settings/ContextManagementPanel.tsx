import React, { useEffect, useState } from 'react';
import { View, Text, TouchableOpacity, ScrollView, StyleSheet, ActivityIndicator } from 'react-native';
import { RefreshCw, Archive, FileText, Brain, AlertCircle, Check, X } from 'lucide-react-native';
import { useChatStore } from '../../../store/chat-store';
import { ContextManager, ContextSummary } from '../utils/ContextManager';
import { db } from '../../../lib/db';
import { useTheme } from '../../../theme/ThemeProvider';

interface ContextManagementPanelProps {
    sessionId: string;
}

export const ContextManagementPanel: React.FC<ContextManagementPanelProps> = ({ sessionId }) => {
    const session = useChatStore(s => s.getSession(sessionId));
    const [summaries, setSummaries] = useState<ContextSummary[]>([]);
    const [loading, setLoading] = useState(false);
    const [stats, setStats] = useState({
        totalTokens: 0,
        activeMessages: 0,
        archivedMessages: 0,
        summarizedCount: 0
    });

    const [lastError, setLastError] = useState<string | null>(null);

    const loadData = async () => {
        if (!session) return;
        setLoading(true);
        setLastError(null);
        try {
            // Fetch summaries
            const result = await db.execute(
                'SELECT * FROM context_summaries WHERE session_id = ? ORDER BY created_at DESC',
                [sessionId]
            );

            const loadedSummaries: ContextSummary[] = [];

            // Robust result parsing for op-sqlite / expo-sqlite compatibility
            if (result && result.rows) {
                // Check if rows is directly an array
                if (Array.isArray(result.rows)) {
                    (result.rows as any[]).forEach(item => {
                        loadedSummaries.push(mapRowToSummary(item));
                    });
                } else if (Array.isArray((result.rows as any)._array)) {
                    // Expo SQLite style
                    (result.rows as any)._array.forEach((item: any) => {
                        loadedSummaries.push(mapRowToSummary(item));
                    });
                } else if (typeof (result.rows as any).length === 'number') {
                    // Standard WebSQL style
                    const len = (result.rows as any).length;
                    for (let i = 0; i < len; i++) {
                        const item = (result.rows as any).item(i);
                        loadedSummaries.push(mapRowToSummary(item));
                    }
                }
            }

            setSummaries(loadedSummaries);

            // Calculate stats
            // This is estimation. In real app, we might query count(messages)
            setStats({
                totalTokens: session.stats?.totalTokens || 0,
                activeMessages: session.messages.filter(m => m.role !== 'system').length,
                archivedMessages: loadedSummaries.length * 20, // Rough estimate
                summarizedCount: loadedSummaries.length
            });

        } catch (e) {
            console.error('Failed to load context data', e);
            setLastError('加载数据失败: ' + (e as Error).message);
        } finally {
            setLoading(false);
        }
    };

    const mapRowToSummary = (item: any): ContextSummary => ({
        id: item.id,
        sessionId: item.session_id,
        startMessageId: item.start_message_id,
        endMessageId: item.end_message_id,
        summaryContent: item.summary_content,
        createdAt: item.created_at,
        tokenUsage: item.token_usage
    });

    useEffect(() => {
        loadData();
    }, [sessionId, session?.messages.length]); // Reload when messages change

    const handleManualSummarize = async () => {
        if (!session) return;
        setLoading(true);
        setLastError(null);
        try {
            await ContextManager.checkAndSummarize(sessionId, session.messages, {
                maxMessages: 20,
                summarizeThreshold: 0 // Force summarization of any pending messages
            });
            await loadData();
        } catch (e) {
            console.error('Manual summarization failed', e);
            setLastError((e as Error).message || '摘要生成失败');
        } finally {
            setLoading(false);
        }
    };

    const [visibleCount, setVisibleCount] = useState(3);

    const handleLoadMore = () => {
        setVisibleCount(prev => prev + 5);
    };

    const handleShowLess = () => {
        setVisibleCount(3);
    };

    const handleDeleteSummary = async (id: string) => {
        // In a real app, show confirmation dialog
        try {
            await ContextManager.deleteSummary(id);
            await loadData(); // Reload
        } catch (e) {
            console.error('Failed to delete summary', e);
            setLastError('删除失败');
        }
    };

    const { isDark } = useTheme();

    if (!session) return null;

    const displayedSummaries = summaries.slice(0, visibleCount);

    // Theme-based colors (simplified match to Tailwind tokens)
    const grayBg = isDark ? '#18181b' : '#f9fafb';       // zinc-900 / gray-50
    const grayBorder = isDark ? '#27272a' : '#f3f4f6';   // zinc-800 / gray-100
    const innerBorder = isDark ? 'rgba(63, 63, 70, 0.5)' : '#f3f4f6'; // zinc-700/50 / gray-100 (matching InferenceSettings)
    const cardBg = isDark ? '#000000' : '#ffffff';
    const textColor = isDark ? '#f1f5f9' : '#1e293b';
    const subTextColor = isDark ? '#94a3b8' : '#64748b';

    return (
        <View style={styles.container}>
            {/* Section Label */}
            <View style={styles.sectionLabelContainer}>
                <Brain size={12} color="#94a3b8" style={{ marginRight: 6 }} />
                <Text style={styles.sectionLabel}>高级上下文管理 (Advanced Context)</Text>

                {/* Refresh Button moved to Label row (optional) or keep inside? 
                    Let's put Refresh inside the gray box for better encapsulation like the "Settings" icon in header.
                    Actually, let's put it top-right of the label row for cleaner look.
                */}
                <TouchableOpacity onPress={loadData} disabled={loading} style={{ marginLeft: 'auto' }}>
                    <RefreshCw size={14} color="#94a3b8" />
                </TouchableOpacity>
            </View>

            {/* Main Gray Container */}
            <View style={[styles.grayContainer, { backgroundColor: grayBg, borderColor: grayBorder }]}>

                {lastError && (
                    <View style={styles.errorContainer}>
                        <AlertCircle size={16} color="#dc2626" style={{ marginRight: 6 }} />
                        <Text style={styles.errorText}>{lastError}</Text>
                    </View>
                )}

                {/* Card 1: Token Stats */}
                <View style={[styles.innerCard, { backgroundColor: cardBg, borderColor: innerBorder, borderWidth: 1 }]}>
                    <View style={styles.cardHeaderRow}>
                        <Text style={[styles.cardTitle, { color: textColor }]}>Token 消耗概览</Text>
                        <TouchableOpacity
                            onPress={handleManualSummarize}
                            disabled={loading}
                            style={[styles.actionButton, loading && styles.actionButtonDisabled]}
                        >
                            {loading ? <ActivityIndicator size="small" color="#fff" /> : <Brain size={12} color="#fff" />}
                            <Text style={styles.actionButtonText}>立即摘要</Text>
                        </TouchableOpacity>
                    </View>

                    <View style={styles.statsRow}>
                        <View style={styles.statItem}>
                            <Text style={[styles.statValue, { color: textColor }]}>{stats.activeMessages}</Text>
                            <Text style={styles.statLabel}>活跃消息</Text>
                        </View>
                        <View style={styles.statDivider} />
                        <View style={styles.statItem}>
                            <Text style={[styles.statValue, { color: textColor }]}>{stats.summarizedCount}</Text>
                            <Text style={styles.statLabel}>已归档</Text>
                        </View>
                        <View style={styles.statDivider} />
                        <View style={styles.statItem}>
                            <Text style={[styles.statValue, { color: textColor }]}>{(stats.totalTokens / 1000).toFixed(1)}k</Text>
                            <Text style={styles.statLabel}>Token</Text>
                        </View>
                    </View>

                    {/* Bar & Legend */}
                    <View style={styles.barContainer}>
                        <View style={[styles.barSegment, { flex: 2, backgroundColor: '#3b82f6' }]} />
                        <View style={[styles.barSegment, { flex: 1, backgroundColor: '#10b981' }]} />
                        <View style={[styles.barSegment, { flex: 1, backgroundColor: '#f59e0b' }]} />
                    </View>
                </View>

                {/* Card 2: Memory Archives */}
                <View style={[styles.innerCard, { backgroundColor: cardBg, marginTop: 12, borderColor: innerBorder, borderWidth: 1 }]}>
                    <View style={styles.cardHeaderRow}>
                        <Text style={[styles.cardTitle, { color: textColor }]}>记忆归档 (Archives)</Text>
                        <View style={styles.countBadge}>
                            <Text style={styles.countBadgeText}>{summaries.length}</Text>
                        </View>
                    </View>

                    {summaries.length === 0 ? (
                        <View style={styles.emptyState}>
                            <Archive size={24} color="#cbd5e1" />
                            <Text style={styles.emptyText}>暂无摘要记录</Text>
                        </View>
                    ) : (
                        <>
                            {displayedSummaries.map((summary, index) => (
                                <View key={summary.id} style={[
                                    styles.summaryItem,
                                    index !== displayedSummaries.length - 1 && styles.summaryItemBorder
                                ]}>
                                    <View style={styles.summaryHeader}>
                                        <View style={styles.summaryBadge}>
                                            <Text style={styles.summaryBadgeText}>SUMMARY</Text>
                                        </View>
                                        <Text style={styles.summaryDate}>{new Date(summary.createdAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</Text>
                                        <TouchableOpacity
                                            style={styles.deleteButton}
                                            onPress={() => handleDeleteSummary(summary.id)}
                                            hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                                        >
                                            <X size={14} color="#94a3b8" />
                                        </TouchableOpacity>
                                    </View>
                                    <Text style={[styles.summaryContent, { color: isDark ? '#cbd5e1' : '#475569' }]} numberOfLines={3}>{summary.summaryContent}</Text>
                                </View>
                            ))}

                            {summaries.length > 3 && (
                                <View style={styles.paginationContainer}>
                                    {visibleCount < summaries.length ? (
                                        <TouchableOpacity onPress={handleLoadMore} style={styles.paginationTextBtn}>
                                            <Text style={styles.paginationTextLink}>展开更多 ({summaries.length - visibleCount})</Text>
                                        </TouchableOpacity>
                                    ) : (
                                        <TouchableOpacity onPress={handleShowLess} style={styles.paginationTextBtn}>
                                            <Text style={styles.paginationTextLink}>收起列表</Text>
                                        </TouchableOpacity>
                                    )}
                                </View>
                            )}
                        </>
                    )}
                </View>

            </View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        marginBottom: 24, // Outer margin to separate from next section
    },
    sectionLabelContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 12,
        paddingHorizontal: 4,
    },
    sectionLabel: {
        color: '#94a3b8', // text-gray-400
        fontWeight: '700',
        fontSize: 10,
        textTransform: 'uppercase',
        letterSpacing: 1, // tracking-widest
    },
    grayContainer: {
        borderRadius: 24, // rounded-3xl
        padding: 20, // p-5
        borderWidth: 1,
    },
    innerCard: {
        borderRadius: 16, // rounded-2xl? or xl? settings uses rounded-xl usually inside
        padding: 16,
        // No shadow for inner cards usually in this design style, just flat white
    },
    cardHeaderRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16,
    },
    cardTitle: {
        fontSize: 14,
        fontWeight: '700',
    },
    actionButton: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#4f46e5',
        paddingHorizontal: 10,
        paddingVertical: 5,
        borderRadius: 16,
    },
    actionButtonDisabled: { opacity: 0.7 },
    actionButtonText: {
        fontSize: 11,
        fontWeight: '600',
        color: '#ffffff',
        marginLeft: 4,
    },
    statsRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16,
    },
    statItem: {
        alignItems: 'center',
        flex: 1,
    },
    statDivider: {
        width: 1,
        height: 20,
        backgroundColor: '#e2e8f0',
    },
    statValue: {
        fontSize: 20,
        fontWeight: '800',
        marginBottom: 2,
    },
    statLabel: {
        fontSize: 10,
        color: '#94a3b8',
    },
    barContainer: {
        height: 6,
        flexDirection: 'row',
        borderRadius: 3,
        overflow: 'hidden',
        backgroundColor: '#f1f5f9',
    },
    barSegment: {},

    // ... Archives styles
    countBadge: {
        backgroundColor: '#f1f5f9',
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 12,
    },
    countBadgeText: {
        fontSize: 10,
        fontWeight: 'bold',
        color: '#64748b'
    },
    emptyState: {
        alignItems: 'center',
        padding: 20,
    },
    emptyText: {
        color: '#94a3b8',
        fontSize: 12,
        marginTop: 8,
    },
    summaryItem: {
        paddingVertical: 12,
    },
    summaryItemBorder: {
        borderBottomWidth: 1,
        borderBottomColor: '#f1f5f9',
    },
    summaryHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 6,
    },
    summaryBadge: {
        backgroundColor: '#f3e8ff',
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 12,
        marginRight: 8,
    },
    summaryBadgeText: {
        color: '#7c3aed',
        fontSize: 9,
        fontWeight: '700',
    },
    summaryDate: {
        fontSize: 11,
        color: '#94a3b8',
        flex: 1,
        marginTop: 1,
    },
    deleteButton: {
        padding: 6,
        backgroundColor: '#f8fafc', // Subtle bg for delete button
        borderRadius: 12,
    },
    summaryContent: {
        fontSize: 13,
        lineHeight: 20,
    },
    paginationContainer: {
        alignItems: 'center',
        paddingTop: 12,
    },
    paginationTextBtn: {
        paddingVertical: 4,
    },
    paginationTextLink: {
        color: '#6366f1',
        fontSize: 12,
        fontWeight: '600',
    },
    // Errors
    errorContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#fef2f2',
        borderRadius: 12,
        padding: 10,
        marginBottom: 12,
    },
    errorText: { color: '#b91c1c', fontSize: 12 },
});

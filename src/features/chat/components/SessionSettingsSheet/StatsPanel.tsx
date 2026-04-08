import React from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { Hash, MessageSquare, Clock, Database, FileText, Cpu } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { useChatStore } from '../../../../store/chat-store';
import { Spacing } from '../../../../theme/glass';
import { useContextTokens } from '../../hooks/useContextTokens';

interface StatsPanelProps {
  sessionId: string;
}

export const StatsPanel: React.FC<StatsPanelProps> = ({ sessionId }) => {
  const { isDark } = useTheme();
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const messages = session?.messages || [];

  const contextInfo = useContextTokens(sessionId);

  const messageCount = messages.length;
  const userMessages = messages.filter((m: any) => m.role === 'user').length;
  const assistantMessages = messages.filter((m: any) => m.role === 'assistant').length;

  const formatNum = (n: number): string => {
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
    return n.toString();
  };

  const getProgressColor = (percent: number): string => {
    if (percent >= 90) return '#ef4444';
    if (percent >= 70) return '#f59e0b';
    return '#22c55e';
  };

  const styles = StyleSheet.create({
    container: { flex: 1 },
    section: { marginBottom: Spacing[4] },
    sectionTitle: {
      fontSize: 13,
      fontWeight: '600',
      color: isDark ? '#a1a1aa' : '#71717a',
      marginBottom: Spacing[2],
      textTransform: 'uppercase',
      letterSpacing: 0.5,
    },
    card: {
      backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
      borderRadius: 12,
      padding: Spacing[3],
      marginBottom: Spacing[2],
    },
    contextOverview: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: Spacing[3],
    },
    contextMainValue: {
      fontSize: 24,
      fontWeight: '700',
      color: isDark ? '#ffffff' : '#18181b',
    },
    contextSubValue: {
      fontSize: 12,
      color: isDark ? '#a1a1aa' : '#71717a',
      marginTop: 2,
    },
    progressContainer: { marginTop: Spacing[2] },
    progressBar: {
      height: 6,
      backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.08)',
      borderRadius: 3,
      overflow: 'hidden',
    },
    progressFill: { height: '100%', borderRadius: 3 },
    progressLabel: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      marginTop: Spacing[1],
    },
    progressLabelText: { fontSize: 11, color: isDark ? '#71717a' : '#a1a1aa' },
    detailRow: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingVertical: Spacing[2],
      borderBottomWidth: 1,
      borderBottomColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)',
    },
    detailIcon: {
      width: 32,
      height: 32,
      borderRadius: 8,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: Spacing[3],
    },
    detailContent: { flex: 1 },
    detailLabel: {
      fontSize: 14,
      fontWeight: '500',
      color: isDark ? '#ffffff' : '#18181b',
    },
    detailSublabel: {
      fontSize: 11,
      color: isDark ? '#71717a' : '#a1a1aa',
      marginTop: 1,
    },
    detailValue: {
      fontSize: 14,
      fontWeight: '600',
      color: isDark ? '#a1a1aa' : '#71717a',
    },
    statGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      marginHorizontal: -Spacing[1],
    },
    statItem: { width: '33.33%', padding: Spacing[1] },
    statCard: {
      backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
      borderRadius: 10,
      padding: Spacing[3],
      alignItems: 'center',
    },
    statIcon: {
      width: 36,
      height: 36,
      borderRadius: 10,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: Spacing[2],
    },
    statValue: {
      fontSize: 20,
      fontWeight: '700',
      color: isDark ? '#ffffff' : '#18181b',
    },
    statLabel: {
      fontSize: 11,
      color: isDark ? '#71717a' : '#a1a1aa',
      marginTop: 2,
    },
  });

  const basicStats = [
    { icon: Hash, label: '消息总数', value: messageCount, color: '#6366f1' },
    { icon: MessageSquare, label: '用户消息', value: userMessages, color: '#f59e0b' },
    { icon: Clock, label: '助手回复', value: assistantMessages, color: '#ec4899' },
  ];

  return (
    <ScrollView style={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.section}>
        <Typography style={styles.sectionTitle}>上下文使用</Typography>
        <View style={styles.card}>
          <View style={styles.contextOverview}>
            <View>
              <Typography style={styles.contextMainValue}>{contextInfo.display}</Typography>
              <Typography style={styles.contextSubValue}>使用率 {contextInfo.usagePercent.toFixed(1)}%</Typography>
            </View>
            <View style={{ alignItems: 'flex-end' }}>
              <Typography style={{ fontSize: 12, color: isDark ? '#71717a' : '#a1a1aa' }}>上下文限制</Typography>
              <Typography style={{ fontSize: 14, fontWeight: '600', color: isDark ? '#a1a1aa' : '#71717a' }}>
                {formatNum(contextInfo.contextLimit)} tokens
              </Typography>
            </View>
          </View>
          <View style={styles.progressContainer}>
            <View style={styles.progressBar}>
              <View
                style={[
                  styles.progressFill,
                  {
                    width: `${Math.min(contextInfo.usagePercent, 100)}%`,
                    backgroundColor: getProgressColor(contextInfo.usagePercent),
                  },
                ]}
              />
            </View>
            <View style={styles.progressLabel}>
              <Typography style={styles.progressLabelText}>0</Typography>
              <Typography style={styles.progressLabelText}>{formatNum(contextInfo.contextLimit)}</Typography>
            </View>
          </View>
        </View>
      </View>

      <View style={styles.section}>
        <Typography style={styles.sectionTitle}>上下文构成</Typography>
        <View style={styles.card}>
          <View style={styles.detailRow}>
            <View style={[styles.detailIcon, { backgroundColor: 'rgba(99, 102, 241, 0.15)' }]}>
              <MessageSquare size={16} color="#6366f1" />
            </View>
            <View style={styles.detailContent}>
              <Typography style={styles.detailLabel}>消息历史</Typography>
              <Typography style={styles.detailSublabel}>会话中的所有消息</Typography>
            </View>
            <Typography style={styles.detailValue}>{formatNum(contextInfo.details.messagesTokens)}</Typography>
          </View>
          <View style={styles.detailRow}>
            <View style={[styles.detailIcon, { backgroundColor: 'rgba(245, 158, 11, 0.15)' }]}>
              <FileText size={16} color="#f59e0b" />
            </View>
            <View style={styles.detailContent}>
              <Typography style={styles.detailLabel}>系统提示词</Typography>
              <Typography style={styles.detailSublabel}>Agent 系统提示</Typography>
            </View>
            <Typography style={styles.detailValue}>{formatNum(contextInfo.details.systemPromptTokens)}</Typography>
          </View>
          <View style={[styles.detailRow, { borderBottomWidth: 0 }]}>
            <View style={[styles.detailIcon, { backgroundColor: 'rgba(34, 197, 94, 0.15)' }]}>
              <Database size={16} color="#22c55e" />
            </View>
            <View style={styles.detailContent}>
              <Typography style={styles.detailLabel}>RAG 检索内容</Typography>
              <Typography style={styles.detailSublabel}>知识库检索增强</Typography>
            </View>
            <Typography style={styles.detailValue}>{formatNum(contextInfo.details.ragTokens)}</Typography>
          </View>
        </View>
      </View>

      <View style={styles.section}>
        <Typography style={styles.sectionTitle}>基本统计</Typography>
        <View style={styles.statGrid}>
          {basicStats.map((stat, index) => (
            <View key={index} style={styles.statItem}>
              <View style={styles.statCard}>
                <View style={[styles.statIcon, { backgroundColor: stat.color + '20' }]}>
                  <stat.icon size={18} color={stat.color} />
                </View>
                <Typography style={styles.statValue}>{stat.value}</Typography>
                <Typography style={styles.statLabel}>{stat.label}</Typography>
              </View>
            </View>
          ))}
        </View>
      </View>
    </ScrollView>
  );
};

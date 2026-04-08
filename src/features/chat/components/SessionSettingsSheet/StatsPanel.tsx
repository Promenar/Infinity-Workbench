import React from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { BarChart3, Clock, Hash, MessageSquare } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { useChatStore } from '../../../../store/chat-store';
import { Spacing } from '../../../../theme/glass';

interface StatsPanelProps {
  sessionId: string;
}

export const StatsPanel: React.FC<StatsPanelProps> = ({ sessionId }) => {
  const { isDark } = useTheme();
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const messages = session?.messages || [];

  const totalTokens = messages.reduce((sum: number, m: any) => {
    return sum + (m.tokens?.input || 0) + (m.tokens?.output || 0);
  }, 0);

  const messageCount = messages.length;
  const userMessages = messages.filter((m: any) => m.role === 'user').length;
  const assistantMessages = messages.filter((m: any) => m.role === 'assistant').length;

  const stats = [
    { icon: Hash, label: '消息总数', value: messageCount, color: '#6366f1' },
    { icon: BarChart3, label: 'Token 使用', value: totalTokens > 1000 ? `${(totalTokens / 1000).toFixed(1)}K` : totalTokens, color: '#22c55e' },
    { icon: MessageSquare, label: '用户消息', value: userMessages, color: '#f59e0b' },
    { icon: Clock, label: '助手消息', value: assistantMessages, color: '#ec4899' },
  ];

  return (
    <ScrollView style={styles.container} showsVerticalScrollIndicator={false} contentContainerStyle={styles.contentContainer}>
      <View style={styles.statsGrid}>
        {stats.map((stat, index) => {
          const Icon = stat.icon;
          return (
            <View
              key={index}
              style={[
                styles.statCard,
                {
                  backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fafafa',
                  borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                },
              ]}
            >
              <View style={[styles.statIcon, { backgroundColor: `${stat.color}12` }]}>
                <Icon size={18} color={stat.color} />
              </View>
              <Typography style={{ fontSize: 22, fontWeight: '700', marginTop: 12, color: isDark ? '#fff' : '#111' }}>
                {stat.value}
              </Typography>
              <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginTop: 4 }}>
                {stat.label}
              </Typography>
            </View>
          );
        })}
      </View>

      <View style={styles.section}>
        <Typography style={[styles.sectionTitle, { color: isDark ? '#a1a1aa' : '#6b7280' }]}>
          详细统计
        </Typography>

        <View style={[styles.detailCard, { backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fafafa', borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)' }]}>
          <View style={styles.detailRow}>
            <Typography style={{ fontSize: 13, color: isDark ? '#a1a1aa' : '#6b7280' }}>用户消息</Typography>
            <Typography style={{ fontSize: 13, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>{userMessages}</Typography>
          </View>
          <View style={[styles.divider, { backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.04)' }]} />
          <View style={styles.detailRow}>
            <Typography style={{ fontSize: 13, color: isDark ? '#a1a1aa' : '#6b7280' }}>助手消息</Typography>
            <Typography style={{ fontSize: 13, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>{assistantMessages}</Typography>
          </View>
          <View style={[styles.divider, { backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.04)' }]} />
          <View style={styles.detailRow}>
            <Typography style={{ fontSize: 13, color: isDark ? '#a1a1aa' : '#6b7280' }}>总 Token</Typography>
            <Typography style={{ fontSize: 13, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>{totalTokens.toLocaleString()}</Typography>
          </View>
        </View>
      </View>

      <View style={{ height: 20 }} />
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  contentContainer: {
    paddingBottom: Spacing[6],
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    padding: Spacing[4],
    gap: 10,
  },
  statCard: {
    width: '47%',
    padding: Spacing[4],
    borderRadius: 16,
    borderWidth: 1,
    alignItems: 'center',
  },
  statIcon: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  section: {
    paddingHorizontal: Spacing[4],
    paddingBottom: Spacing[4],
  },
  sectionTitle: {
    fontSize: 11,
    fontWeight: '600',
    marginBottom: Spacing[3],
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  detailCard: {
    borderRadius: 16,
    borderWidth: 1,
    padding: Spacing[3],
  },
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: Spacing[2],
  },
  divider: {
    height: 1,
  },
});

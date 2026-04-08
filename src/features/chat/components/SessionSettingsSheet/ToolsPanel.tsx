import React from 'react';
import { View, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { Zap, Shield, Bot, Check, Server, Circle } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Switch } from '../../../../components/ui/Switch';
import { useChatStore } from '../../../../store/chat-store';
import { useMcpStore } from '../../../../store/mcp-store';
import { Spacing } from '../../../../theme/glass';
import { impactAsync, ImpactFeedbackStyle } from 'expo-haptics';

interface ToolsPanelProps {
  sessionId: string;
}

const MODES = [
  { id: 'auto', label: '自动', description: '模型自主执行所有步骤', icon: Zap },
  { id: 'semi', label: '半自动', description: '关键操作需要确认', icon: Shield },
  { id: 'manual', label: '手动', description: '每步都需要确认', icon: Bot },
];

const STATUS_COLORS: Record<string, string> = {
  connected: '#22c55e',
  disconnected: '#71717a',
  error: '#ef4444',
  loading: '#f59e0b',
};

export const ToolsPanel: React.FC<ToolsPanelProps> = ({ sessionId }) => {
  const { isDark, colors } = useTheme();
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const updateSession = useChatStore((s) => s.updateSession);
  const { servers } = useMcpStore();

  const currentMode = session?.executionMode || 'auto';
  const toolsEnabled = session?.options?.toolsEnabled !== false;
  const activeMcpServerIds = session?.activeMcpServerIds || [];

  const handleSelectMode = (mode: string) => {
    impactAsync(ImpactFeedbackStyle.Light);
    updateSession(sessionId, { executionMode: mode as any } as any);
  };

  const handleToggleTools = (value: boolean) => {
    updateSession(sessionId, {
      options: { ...session?.options, toolsEnabled: value },
    } as any);
  };

  const handleToggleMcpServer = (serverId: string, enabled: boolean) => {
    impactAsync(ImpactFeedbackStyle.Light);
    const newActiveIds = enabled
      ? [...activeMcpServerIds, serverId]
      : activeMcpServerIds.filter((id) => id !== serverId);
    updateSession(sessionId, { activeMcpServerIds: newActiveIds } as any);
  };

  const enabledServers = servers.filter((s) => s.enabled);

  return (
    <ScrollView style={styles.container} showsVerticalScrollIndicator={false} contentContainerStyle={styles.contentContainer}>
      <View style={styles.section}>
        <Typography style={[styles.sectionTitle, { color: isDark ? '#a1a1aa' : '#6b7280' }]}>
          执行模式
        </Typography>

        {MODES.map((mode) => {
          const isSelected = mode.id === currentMode;
          const Icon = mode.icon;

          return (
            <TouchableOpacity
              key={mode.id}
              onPress={() => handleSelectMode(mode.id)}
              activeOpacity={0.7}
              style={[
                styles.modeItem,
                {
                  backgroundColor: isSelected
                    ? colors.opacity20
                    : isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.01)',
                  borderColor: isSelected
                    ? colors.opacity30
                    : isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                },
              ]}
            >
              <View style={[styles.modeIcon, { backgroundColor: isSelected ? colors.opacity20 : isDark ? 'rgba(255,255,255,0.05)' : '#f3f4f6' }]}>
                <Icon size={18} color={isSelected ? colors[500] : isDark ? '#71717a' : '#9ca3af'} />
              </View>
              <View style={styles.modeInfo}>
                <Typography style={{ fontSize: 14, fontWeight: isSelected ? '600' : '500', color: isDark ? '#fff' : '#111' }}>
                  {mode.label}
                </Typography>
                <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginTop: 1 }}>
                  {mode.description}
                </Typography>
              </View>
              {isSelected && (
                <View style={[styles.checkBadge, { backgroundColor: colors[500] }]}>
                  <Check size={12} color="#fff" />
                </View>
              )}
            </TouchableOpacity>
          );
        })}
      </View>

      <View style={styles.section}>
        <Typography style={[styles.sectionTitle, { color: isDark ? '#a1a1aa' : '#6b7280' }]}>
          工具调用
        </Typography>

        <View style={[styles.toggleCard, { backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#f9fafb', borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)' }]}>
          <View style={styles.toggleInfo}>
            <Typography style={{ fontSize: 14, fontWeight: '500', color: isDark ? '#fff' : '#111' }}>
              启用工具调用
            </Typography>
            <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginTop: 2 }}>
              允许模型调用外部工具和 API
            </Typography>
          </View>
          <Switch value={toolsEnabled} onValueChange={handleToggleTools} />
        </View>
      </View>

      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <Server size={14} color={isDark ? '#a1a1aa' : '#6b7280'} />
          <Typography style={[styles.sectionTitle, { color: isDark ? '#a1a1aa' : '#6b7280', marginLeft: 6 }]}>
            MCP 服务器
          </Typography>
        </View>

        {enabledServers.length === 0 ? (
          <View style={[styles.emptyCard, { backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#f9fafb', borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)' }]}>
            <Typography style={{ fontSize: 13, color: isDark ? '#71717a' : '#6b7280', textAlign: 'center' }}>
              暂无已启用的 MCP 服务器{'\n'}请在设置中添加
            </Typography>
          </View>
        ) : (
          enabledServers.map((server) => {
            const isActive = activeMcpServerIds.includes(server.id);
            const statusColor = STATUS_COLORS[server.status] || '#71717a';

            return (
              <View
                key={server.id}
                style={[
                  styles.serverItem,
                  {
                    backgroundColor: isActive
                      ? colors.opacity10
                      : isDark ? 'rgba(255,255,255,0.02)' : '#f9fafb',
                    borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                  },
                ]}
              >
                <View style={styles.serverInfo}>
                  <View style={styles.serverHeader}>
                    <Circle size={8} fill={statusColor} color={statusColor} />
                    <Typography style={{ fontSize: 13, fontWeight: '500', color: isDark ? '#fff' : '#111', marginLeft: 8 }}>
                      {server.name}
                    </Typography>
                  </View>
                  <Typography style={{ fontSize: 10, color: isDark ? '#52525b' : '#9ca3af', marginTop: 2, marginLeft: 16 }} numberOfLines={1}>
                    {server.url}
                  </Typography>
                </View>
                <Switch value={isActive} onValueChange={(v) => handleToggleMcpServer(server.id, v)} />
              </View>
            );
          })
        )}
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
  section: {
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  sectionTitle: {
    fontSize: 11,
    fontWeight: '600',
    marginBottom: Spacing[3],
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  modeItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[3],
    paddingVertical: Spacing[3],
    marginBottom: Spacing[2],
    borderRadius: 14,
    borderWidth: 1,
  },
  modeIcon: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: Spacing[3],
  },
  modeInfo: {
    flex: 1,
  },
  checkBadge: {
    width: 20,
    height: 20,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  toggleCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing[3],
    paddingVertical: Spacing[3],
    borderRadius: 14,
    borderWidth: 1,
  },
  toggleInfo: {
    flex: 1,
    marginRight: Spacing[3],
  },
  emptyCard: {
    padding: Spacing[4],
    borderRadius: 14,
    borderWidth: 1,
    alignItems: 'center',
  },
  serverItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing[3],
    paddingVertical: Spacing[3],
    marginBottom: Spacing[2],
    borderRadius: 14,
    borderWidth: 1,
  },
  serverInfo: {
    flex: 1,
    marginRight: Spacing[3],
  },
  serverHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
});

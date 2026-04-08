import React from 'react';
import { View, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { Zap, Shield, Bot, Wrench, Check } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Switch } from '../../../../components/ui/Switch';
import { useChatStore } from '../../../../store/chat-store';
import { Spacing } from '../../../../theme/glass';
import { impactAsync, ImpactFeedbackStyle } from 'expo-haptics';

interface ExecutionModePanelProps {
  sessionId: string;
}

const MODES = [
  { id: 'auto', label: '自动', description: '模型自主执行所有步骤', icon: Zap },
  { id: 'semi', label: '半自动', description: '关键操作需要确认', icon: Shield },
  { id: 'manual', label: '手动', description: '每步都需要确认', icon: Bot },
];

export const ExecutionModePanel: React.FC<ExecutionModePanelProps> = ({ sessionId }) => {
  const { isDark, colors } = useTheme();
  const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));
  const updateSession = useChatStore(s => s.updateSession);

  const currentMode = session?.executionMode || 'auto';
  const toolsEnabled = session?.options?.toolsEnabled !== false;

  const handleSelectMode = (mode: string) => {
    impactAsync(ImpactFeedbackStyle.Light);
    updateSession(sessionId, { executionMode: mode as any } as any);
  };

  const handleToggleTools = (value: boolean) => {
    impactAsync(ImpactFeedbackStyle.Light);
    updateSession(sessionId, {
      options: { ...session?.options, toolsEnabled: value },
    } as any);
  };

  return (
    <ScrollView style={styles.container} showsVerticalScrollIndicator={false} contentContainerStyle={styles.contentContainer}>
      <View style={styles.header}>
        <Zap size={18} color={colors[500]} />
        <Typography style={{ fontSize: 14, fontWeight: '600', marginLeft: 8, color: isDark ? '#fff' : '#111' }}>
          执行模式
        </Typography>
      </View>

      {MODES.map(mode => {
        const isSelected = mode.id === currentMode;
        const Icon = mode.icon;

        return (
          <TouchableOpacity
            key={mode.id}
            onPress={() => handleSelectMode(mode.id)}
            activeOpacity={0.7}
            style={[
              styles.modeItem,
              isSelected && { backgroundColor: colors.opacity20 },
            ]}
          >
            <View style={[styles.modeIcon, { backgroundColor: isSelected ? colors.opacity20 : isDark ? 'rgba(255,255,255,0.05)' : '#f3f4f6' }]}>
              <Icon size={18} color={isSelected ? colors[500] : isDark ? '#a1a1aa' : '#6b7280'} />
            </View>
            <View style={styles.modeInfo}>
              <Typography style={{ fontSize: 14, fontWeight: isSelected ? '600' : '400', color: isDark ? '#fff' : '#111' }}>
                {mode.label}
              </Typography>
              <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginTop: 2 }}>
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

      <View style={[styles.divider, { backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }]} />

      <View style={styles.header}>
        <Wrench size={18} color={colors[500]} />
        <Typography style={{ fontSize: 14, fontWeight: '600', marginLeft: 8, color: isDark ? '#fff' : '#111' }}>
          工具调用
        </Typography>
      </View>

      <View style={[styles.toggleItem, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f9fafb' }]}>
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
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
  },
  modeItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
    marginHorizontal: Spacing[3],
    marginBottom: Spacing[2],
    borderRadius: 14,
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
  divider: {
    height: 1,
    marginVertical: Spacing[3],
    marginHorizontal: Spacing[3],
  },
  toggleItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
    marginHorizontal: Spacing[3],
    borderRadius: 14,
  },
  toggleInfo: {
    flex: 1,
    marginRight: Spacing[3],
  },
});

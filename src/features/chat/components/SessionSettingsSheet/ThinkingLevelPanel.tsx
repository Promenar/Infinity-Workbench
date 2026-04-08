import React from 'react';
import { View, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { Brain, Check } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { useChatStore } from '../../../../store/chat-store';
import { Spacing } from '../../../../theme/glass';
import { impactAsync, ImpactFeedbackStyle } from 'expo-haptics';

interface ThinkingLevelPanelProps {
  sessionId: string;
}

const LEVELS = [
  { id: 'minimal', label: '最小', description: '最快响应，无深度思考' },
  { id: 'low', label: '低', description: '轻度思考，适合简单任务' },
  { id: 'medium', label: '中', description: '平衡模式，适合大多数场景' },
  { id: 'high', label: '高', description: '深度思考，适合复杂推理' },
];

export const ThinkingLevelPanel: React.FC<ThinkingLevelPanelProps> = ({ sessionId }) => {
  const { isDark } = useTheme();
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const updateSession = useChatStore((s) => s.updateSession);

  const currentLevel = session?.options?.thinkingLevel || 'medium';

  const handleSelectLevel = (level: string) => {
    impactAsync(ImpactFeedbackStyle.Light);
    updateSession(sessionId, {
      options: { ...session?.options, thinkingLevel: level as any },
    } as any);
  };

  return (
    <ScrollView style={styles.container} showsVerticalScrollIndicator={false} contentContainerStyle={styles.contentContainer}>
      <View style={styles.header}>
        <View style={[styles.headerIcon, { backgroundColor: isDark ? 'rgba(99, 102, 241, 0.12)' : 'rgba(99, 102, 241, 0.08)' }]}>
          <Brain size={18} color="#6366f1" />
        </View>
        <Typography style={{ fontSize: 14, fontWeight: '600', marginLeft: 10, color: isDark ? '#fff' : '#111' }}>
          思考深度
        </Typography>
      </View>

      <Typography style={{ fontSize: 12, color: isDark ? '#71717a' : '#6b7280', paddingHorizontal: Spacing[4], marginBottom: Spacing[4], lineHeight: 18 }}>
        仅适用于 Gemini 2.0 Thinking 模型。调整模型的思考深度以平衡响应速度和推理质量。
      </Typography>

      {LEVELS.map((level) => {
        const isSelected = level.id === currentLevel;

        return (
          <TouchableOpacity
            key={level.id}
            onPress={() => handleSelectLevel(level.id)}
            style={[
              styles.levelItem,
              {
                backgroundColor: isSelected
                  ? isDark ? 'rgba(99, 102, 241, 0.12)' : '#EEF2FF'
                  : isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.01)',
                borderColor: isSelected
                  ? isDark ? 'rgba(99, 102, 241, 0.25)' : 'rgba(99, 102, 241, 0.15)'
                  : isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
              },
            ]}
          >
            <View style={styles.levelInfo}>
              <Typography style={{ fontSize: 14, fontWeight: isSelected ? '600' : '500', color: isDark ? '#fff' : '#111' }}>
                {level.label}
              </Typography>
              <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginTop: 2 }}>
                {level.description}
              </Typography>
            </View>
            {isSelected && (
              <View style={[styles.checkIcon, { backgroundColor: '#6366f1' }]}>
                <Check size={12} color="#fff" />
              </View>
            )}
          </TouchableOpacity>
        );
      })}

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
  headerIcon: {
    width: 32,
    height: 32,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  levelItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
    marginHorizontal: Spacing[3],
    marginBottom: Spacing[2],
    borderRadius: 14,
    borderWidth: 1,
  },
  levelInfo: {
    flex: 1,
  },
  checkIcon: {
    width: 20,
    height: 20,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

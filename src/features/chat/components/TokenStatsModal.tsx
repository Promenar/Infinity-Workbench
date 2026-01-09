import React, { useMemo } from 'react';
import { useI18n } from '../../../lib/i18n';
import { View, Modal, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { BlurView } from 'expo-blur';
import Animated, {
  FadeIn,
  FadeOut,
  SlideInDown,
  SlideOutDown,
  Easing,
} from 'react-native-reanimated';
import { X, Zap, Database, RotateCcw, MessageSquare, Cpu } from 'lucide-react-native';
import { Typography } from '../../../components/ui/Typography';
import { useTheme } from '../../../theme/ThemeProvider';
import { Session, BillingUsage } from '../../../types/chat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useChatStore } from '../../../store/chat-store';
import * as Haptics from '../../../lib/haptics';

interface TokenStatsModalProps {
  visible: boolean;
  onClose: () => void;
  session: Session;
}

export const TokenStatsModal: React.FC<TokenStatsModalProps> = ({ visible, onClose, session }) => {
  const { isDark } = useTheme();
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const updateSession = useChatStore((state) => state.updateSession);

  // Extract Billing Stats or Fallback
  const stats: BillingUsage = useMemo(() => {
    if (session.stats?.billing) {
      return session.stats.billing;
    }
    // Fallback for legacy sessions
    const total = session.stats?.totalTokens || 0;
    return {
      chatInput: { count: 0, isEstimated: true },
      chatOutput: { count: 0, isEstimated: true },
      ragSystem: { count: 0, isEstimated: true },
      total,
      costUSD: 0,
    };
  }, [session.stats]);

  // Calculate Percentages
  const total = stats.total || 1; // Avoid div by zero
  const inputPct = (stats.chatInput.count / total) * 100;
  const outputPct = (stats.chatOutput.count / total) * 100;
  const ragPct = (stats.ragSystem.count / total) * 100;

  const handleReset = () => {
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    // Clear session stats by creating a new empty billing object
    const emptyBilling: BillingUsage = {
      chatInput: { count: 0, isEstimated: false },
      chatOutput: { count: 0, isEstimated: false },
      ragSystem: { count: 0, isEstimated: false },
      total: 0,
      costUSD: 0,
    };
    updateSession(session.id, { stats: { totalTokens: 0, billing: emptyBilling } });
  };

  if (!visible) return null;

  const MetricRow = ({ label, count, pct, color, icon: Icon, isEstimated }: any) => (
    <View style={styles.metricRow}>
      <View style={[styles.iconBox, { backgroundColor: isDark ? `${color}20` : `${color}10` }]}>
        <Icon size={16} color={color} />
      </View>
      <View style={{ flex: 1, marginLeft: 12 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 }}>
          <Typography className="text-sm font-bold" style={{ color: isDark ? '#EEE' : '#333' }}>
            {label}
          </Typography>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            {isEstimated && (
              <Typography className="text-xs font-medium mr-1 text-amber-500">≈</Typography>
            )}
            <Typography className="text-sm font-black" style={{ color: isDark ? '#FFF' : '#000' }}>
              {count.toLocaleString()}
            </Typography>
          </View>
        </View>
        <View
          style={[
            styles.barBg,
            { backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' },
          ]}
        >
          <View style={[styles.barFill, { width: `${pct}%`, backgroundColor: color }]} />
        </View>
      </View>
    </View>
  );

  return (
    <Modal visible={visible} transparent animationType="none" onRequestClose={onClose}>
      <View style={[styles.overlay, { paddingBottom: Math.max(insets.bottom, 20) }]}>
        <Animated.View
          entering={FadeIn.duration(300)}
          exiting={FadeOut.duration(200)}
          style={[styles.backdrop]}
        >
          <TouchableOpacity style={styles.fill} onPress={onClose} activeOpacity={1} />
        </Animated.View>

        <Animated.View
          entering={SlideInDown.duration(350).easing(Easing.out(Easing.quad))}
          exiting={SlideOutDown.duration(250).easing(Easing.in(Easing.quad))}
          style={[
            styles.floatContainer,
            {
              backgroundColor: isDark ? 'rgba(24, 24, 27, 0.96)' : 'rgba(255, 255, 255, 0.98)',
              borderColor: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
            },
          ]}
        >
          <BlurView
            intensity={isDark ? 50 : 80}
            tint={isDark ? 'dark' : 'light'}
            style={styles.blurContent}
          >
            {/* Header */}
            <View style={styles.header}>
              <View>
                <Typography
                  className="text-2xl font-black text-black dark:text-white"
                  style={{ letterSpacing: -0.5 }}
                >
                  {t.settings.tokenStats.title}
                </Typography>
                <Typography className="text-[10px] text-gray-500 dark:text-gray-400 font-bold uppercase tracking-[1px] mt-0.5">
                  {t.settings.tokenStats.subtitle}
                </Typography>
              </View>
              <TouchableOpacity
                onPress={onClose}
                style={[
                  styles.closeBtn,
                  { backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' },
                ]}
              >
                <X size={18} color={isDark ? '#fff' : '#000'} />
              </TouchableOpacity>
            </View>

            {/* Total Big Number */}
            <View style={styles.totalSection}>
              <View
                style={[
                  styles.ring,
                  { borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' },
                ]}
              >
                <Typography
                  className="text-4xl font-black text-black dark:text-white"
                  style={{ letterSpacing: -1 }}
                >
                  {stats.total.toLocaleString()}
                </Typography>
                <Typography className="text-[10px] text-gray-400 font-bold mt-1 uppercase">
                  {t.settings.tokenStats.totalToken}
                </Typography>
              </View>
            </View>

            {/* Breakdown */}
            <View style={styles.breakdown}>
              <MetricRow
                label={t.settings.tokenStats.prompt}
                count={stats.chatInput.count}
                pct={inputPct}
                color="#8b5cf6"
                icon={MessageSquare}
                isEstimated={stats.chatInput.isEstimated}
              />
              <MetricRow
                label={t.settings.tokenStats.completion}
                count={stats.chatOutput.count}
                pct={outputPct}
                color="#f59e0b"
                icon={Zap}
                isEstimated={stats.chatOutput.isEstimated}
              />
              <MetricRow
                label={t.settings.tokenStats.ragSystem}
                count={stats.ragSystem.count}
                pct={ragPct}
                color="#10b981"
                icon={Database}
                isEstimated={stats.ragSystem.isEstimated}
              />
            </View>

            {/* Actions */}
            <View style={styles.footer}>
              <TouchableOpacity
                onPress={handleReset}
                activeOpacity={0.7}
                style={[
                  styles.resetBtn,
                  {
                    backgroundColor: isDark ? 'rgba(239, 68, 68, 0.15)' : 'rgba(239, 68, 68, 0.1)',
                  },
                ]}
              >
                <RotateCcw size={14} color="#ef4444" />
                <Typography className="text-xs font-bold text-red-500 ml-2">
                  {t.settings.tokenStats.reset}
                </Typography>
              </TouchableOpacity>

              <Typography className="text-[10px] text-gray-400 text-center mt-3">
                {t.settings.tokenStats.estimated}
              </Typography>
            </View>
          </BlurView>
        </Animated.View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  fill: { flex: 1 },
  floatContainer: {
    marginHorizontal: 16,
    marginBottom: 10,
    borderRadius: 28,
    overflow: 'hidden',
    borderWidth: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 12 },
    shadowOpacity: 0.3,
    shadowRadius: 24,
    elevation: 12,
  },
  blurContent: {
    padding: 24,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 28,
  },
  closeBtn: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  totalSection: {
    alignItems: 'center',
    marginBottom: 32,
  },
  ring: {
    width: 160,
    height: 160,
    borderRadius: 80,
    borderWidth: 4,
    alignItems: 'center',
    justifyContent: 'center',
    borderStyle: 'dashed',
  },
  breakdown: {
    gap: 20,
    marginBottom: 32,
  },
  metricRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  iconBox: {
    width: 36,
    height: 36,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  barBg: {
    height: 6,
    borderRadius: 3,
    width: '100%',
    overflow: 'hidden',
  },
  barFill: {
    height: '100%',
    borderRadius: 3,
  },
  footer: {
    alignItems: 'center',
    borderTopWidth: 1,
    borderTopColor: 'rgba(128,128,128,0.1)',
    paddingTop: 20,
  },
  resetBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 20,
  },
});

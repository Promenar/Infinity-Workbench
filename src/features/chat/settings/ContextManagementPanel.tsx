import React, { useEffect, useState } from 'react';
import {
  View,
  TouchableOpacity,
  ActivityIndicator,
  ScrollView,
} from 'react-native';
import { RefreshCw, Archive, Brain, AlertCircle, X, Download } from 'lucide-react-native';
import { useChatStore } from '../../../store/chat-store';
import { ContextManager, ContextSummary } from '../utils/ContextManager';
import { db } from '../../../lib/db';
import { useTheme } from '../../../theme/ThemeProvider';
import { useI18n } from '../../../lib/i18n';
import { Typography } from '../../../components/ui/Typography';
import { clsx } from 'clsx';
import * as Haptics from '../../../lib/haptics';
import { Card } from '../../../components/ui/Card';

interface ContextManagementPanelProps {
  sessionId: string;
}

// 装饰性的小标题组件
const SectionHeader: React.FC<{ title: string; mt?: number }> = ({ title, mt = 12 }) => {
  const { colors } = useTheme();
  return (
    <View
      style={{
        marginTop: mt,
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 8,
        paddingHorizontal: 4,
      }}
    >
      <View
        style={{
          width: 4,
          height: 12,
          borderRadius: 999,
          marginRight: 8,
          backgroundColor: colors[500],
        }}
      />
      <Typography className="text-xs font-bold uppercase tracking-widest text-gray-900 dark:text-white">
        {title}
      </Typography>
    </View>
  );
};

export const ContextManagementPanel: React.FC<ContextManagementPanelProps> = ({ sessionId }) => {
  const { t } = useI18n();
  const session = useChatStore((s) => s.getSession(sessionId));
  const [summaries, setSummaries] = useState<ContextSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [stats, setStats] = useState({
    totalTokens: 0,
    activeMessages: 0,
    archivedMessages: 0,
    summarizedCount: 0,
  });

  const [lastError, setLastError] = useState<string | null>(null);

  const loadData = async () => {
    if (!session) return;
    setLoading(true);
    setLastError(null);
    try {
      const result = await db.execute(
        'SELECT * FROM context_summaries WHERE session_id = ? ORDER BY created_at DESC',
        [sessionId],
      );

      const loadedSummaries: ContextSummary[] = [];

      if (result && result.rows) {
        if (Array.isArray(result.rows)) {
          (result.rows as any[]).forEach((item) => {
            loadedSummaries.push(mapRowToSummary(item));
          });
        } else if (Array.isArray((result.rows as any)._array)) {
          (result.rows as any)._array.forEach((item: any) => {
            loadedSummaries.push(mapRowToSummary(item));
          });
        } else if (typeof (result.rows as any).length === 'number') {
          const len = (result.rows as any).length;
          for (let i = 0; i < len; i++) {
            const item = (result.rows as any)[i];
            loadedSummaries.push(mapRowToSummary(item));
          }
        }
      }

      setSummaries(loadedSummaries);

      setStats({
        totalTokens: session.stats?.totalTokens || 0,
        activeMessages: session.messages.filter((m) => m.role !== 'system').length,
        archivedMessages: loadedSummaries.length * 20,
        summarizedCount: loadedSummaries.length,
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
    tokenUsage: item.token_usage,
  });

  useEffect(() => {
    loadData();
  }, [sessionId, session?.messages.length]);

  const handleManualSummarize = async () => {
    if (!session) return;
    setLoading(true);
    setLastError(null);
    try {
      setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium), 10);
      await ContextManager.checkAndSummarize(sessionId, session.messages, undefined, {
        maxMessages: 20,
        summarizeThreshold: 0,
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
    setVisibleCount((prev) => prev + 5);
  };

  const handleShowLess = () => {
    setVisibleCount(3);
  };

  const handleDeleteSummary = async (id: string) => {
    try {
      setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
      await ContextManager.deleteSummary(id);
      await loadData();
    } catch (e) {
      console.error('Failed to delete summary', e);
      setLastError('删除失败');
    }
  };

  const { isDark, colors } = useTheme();

  if (!session) return null;

  const displayedSummaries = summaries; // Show all summaries in scroll view

  return (
    <View>
      {lastError && (
        <View className="flex-row items-center bg-red-50 dark:bg-red-900/10 rounded-2xl p-2.5 mb-3 border border-red-100 dark:border-red-900/20">
          <AlertCircle size={16} color="#ef4444" className="mr-2" />
          <Typography className="text-red-600 dark:text-red-400 text-xs flex-1">
            {lastError}
          </Typography>
        </View>
      )}

      {/* Token Stats (Condensed) - No Card Background */}
      <View className="mb-6 mt-2">
        <View className="flex-row justify-between items-start mb-4">
          <View>
            <Typography className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-1">
              {t.rag.activeMsg} / {t.rag.archived}
            </Typography>
            <View className="flex-row items-baseline">
              <Typography className="text-2xl font-extrabold text-gray-900 dark:text-white mr-1">
                {stats.activeMessages}
              </Typography>
              <Typography className="text-sm font-medium text-gray-400 mr-3">
                / {stats.summarizedCount}
              </Typography>

              {/* Mini Refresh Btn */}
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                  loadData();
                }}
                disabled={loading}
                hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
              >
                <RefreshCw size={12} color={isDark ? "#52525b" : "#cbd5e1"} className={clsx(loading && "animate-spin")} />
              </TouchableOpacity>
            </View>
          </View>

          {/* Right: Actions */}
          <View className="items-end">
            <TouchableOpacity
              onPress={handleManualSummarize}
              disabled={loading}
              style={{ backgroundColor: colors[600] }}
              className="flex-row items-center px-3 py-1.5 rounded-full shadow-sm"
            >
              {loading ? (
                <ActivityIndicator size="small" color="#fff" style={{ transform: [{ scale: 0.7 }] }} />
              ) : (
                <Brain size={12} color="#fff" className="mr-1.5" />
              )}
              <Typography className="text-[10px] font-bold text-white">
                {t.rag.summarizeNow}
              </Typography>
            </TouchableOpacity>
            <Typography className="text-[10px] text-gray-400 mt-2 font-medium">
              Total Usage: <Typography className="text-gray-900 dark:text-white font-bold">{(stats.totalTokens / 1000).toFixed(1)}k</Typography>
            </Typography>
          </View>
        </View>

        {/* Visual Bar */}
        <View className="h-1.5 flex-row rounded-full overflow-hidden bg-gray-100 dark:bg-zinc-800/50">
          <View className="flex-[2] bg-indigo-500" />
          <View className="flex-[1] bg-emerald-500" />
          <View className="flex-[1] bg-amber-500" />
        </View>
      </View>

      {/* Memory Archives (Timeline) - No Card Background */}
      <View className="flex-row justify-between items-center mb-4 border-t border-gray-100 dark:border-zinc-800 pt-6">
        <Typography className="text-xs font-bold text-gray-900 dark:text-white">
          {t.rag.memoryArchives}
        </Typography>
        <View className="bg-gray-100 dark:bg-zinc-800 px-2 py-0.5 rounded text-xs">
          <Typography className="text-[10px] font-bold text-gray-500 dark:text-gray-400">
            {summaries.length} RECODS
          </Typography>
        </View>
      </View>

      <View style={{ minHeight: 200 }}>
        {summaries.length === 0 ? (
          <View className="items-center justify-center py-12">
            <View className="w-12 h-12 rounded-full bg-gray-50 dark:bg-zinc-800/50 items-center justify-center mb-3">
              <Archive size={20} color={isDark ? '#52525b' : '#94a3b8'} />
            </View>
            <Typography className="text-gray-400 text-xs font-medium">{t.rag.noArchives}</Typography>
          </View>
        ) : (
          <View style={{ height: 320 }}>{/* Fixed Height Container */}
            <ScrollView
              nestedScrollEnabled
              showsVerticalScrollIndicator={true}
              contentContainerStyle={{ paddingLeft: 8 }}
              indicatorStyle={isDark ? 'white' : 'black'}
            >
              <View className="relative pl-4">
                {/* Vertical Line */}
                <View
                  className="absolute left-[5.5px] top-2 bottom-0 w-[1.5px] bg-gray-100 dark:bg-zinc-800"
                  style={{ bottom: 20 }} // Stop before last item ends
                />

                {summaries.map((summary, index) => {
                  const date = new Date(summary.createdAt);
                  return (
                    <View key={summary.id} className="flex-row mb-6 relative">
                      {/* Dot */}
                      <View
                        className="absolute left-[-14px] top-1.5 w-3 h-3 rounded-full border-2 border-white dark:border-zinc-900 z-10"
                        style={{ backgroundColor: colors[500] }}
                      />

                      {/* Content */}
                      <View className="flex-1 ml-2">
                        <View className="flex-row justify-between items-center mb-1.5">
                          <Typography className="text-[10px] font-bold text-gray-400 uppercase tracking-wide">
                            {date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })}
                            {' · '}
                            {date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
                          </Typography>
                          <TouchableOpacity
                            onPress={() => handleDeleteSummary(summary.id)}
                            hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                          >
                            <X size={12} color={isDark ? '#52525b' : '#cbd5e1'} />
                          </TouchableOpacity>
                        </View>

                        <View className="bg-gray-50 dark:bg-zinc-800/40 rounded-xl p-3 border border-gray-100 dark:border-zinc-800">
                          <Typography className="text-xs text-gray-600 dark:text-gray-300 leading-5">
                            {summary.summaryContent}
                          </Typography>
                          <View className="mt-2 flex-row items-center border-t border-gray-200/50 dark:border-zinc-700/30 pt-2">
                            <Download size={10} color="#9ca3af" className="mr-1" />
                            <Typography className="text-[10px] text-gray-400 font-medium">
                              Saved Context
                            </Typography>
                          </View>
                        </View>
                      </View>
                    </View>
                  );
                })}
              </View>
              {/* End Spacer */}
              <View className="h-8" />
            </ScrollView>
          </View>
        )}
      </View>
    </View>
  );
};


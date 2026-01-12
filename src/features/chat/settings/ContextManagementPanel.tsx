import React, { useEffect, useState } from 'react';
import {
  View,
  TouchableOpacity,
  ActivityIndicator,
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

interface ContextManagementPanelProps {
  sessionId: string;
}

const SectionHeader = ({ title }: { title: string }) => {
  const { colors } = useTheme();
  return (
    <View className="flex-row items-center mb-4 mt-2">
      <View style={{ backgroundColor: colors[500] }} className="w-1 h-4 rounded-full mr-2" />
      <Typography className="text-base font-bold text-gray-900 dark:text-gray-100">
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

  const displayedSummaries = summaries.slice(0, visibleCount);

  return (
    <View className="mb-8">
      <SectionHeader title={t.rag.contextManagement} />

      <View className="bg-gray-50 dark:bg-zinc-900 rounded-[32px] p-5 border border-indigo-50 dark:border-indigo-500/10">
        {lastError && (
          <View className="flex-row items-center bg-red-50 dark:bg-red-900/10 rounded-2xl p-3 mb-4 border border-red-100 dark:border-red-900/20">
            <AlertCircle size={16} color="#ef4444" className="mr-2" />
            <Typography className="text-red-600 dark:text-red-400 text-xs flex-1">
              {lastError}
            </Typography>
          </View>
        )}

        {/* Card 1: Token Stats */}
        <View className="bg-white dark:bg-black p-4 rounded-2xl border border-indigo-50 dark:border-indigo-500/10 shadow-sm">
          <View className="flex-row justify-between items-center mb-4">
            <View className="flex-row items-center">
              <Typography className="text-sm font-bold text-gray-900 dark:text-white mr-2">
                {t.rag.tokenStats}
              </Typography>
              <TouchableOpacity
                onPress={() => {
                  setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                  loadData();
                }}
                disabled={loading}
              >
                <RefreshCw size={12} color="#94a3b8" className={clsx(loading && "animate-spin")} />
              </TouchableOpacity>
            </View>
            <TouchableOpacity
              onPress={handleManualSummarize}
              disabled={loading}
              style={loading ? { opacity: 0.5 } : { backgroundColor: colors[600] }}
              className="flex-row items-center px-3 py-1.5 rounded-full"
            >
              {loading ? (
                <ActivityIndicator size="small" color="#fff" />
              ) : (
                <Brain size={12} color="#fff" className="mr-1.5" />
              )}
              <Typography className="text-[11px] font-bold text-white">
                {t.rag.summarizeNow}
              </Typography>
            </TouchableOpacity>
          </View>

          <View className="flex-row justify-between items-center mb-5">
            <View className="items-center flex-1">
              <Typography className="text-xl font-extrabold text-gray-900 dark:text-white">
                {stats.activeMessages}
              </Typography>
              <Typography className="text-[10px] text-gray-400">{t.rag.activeMsg}</Typography>
            </View>
            <View className="w-[1px] h-5 bg-gray-100 dark:bg-zinc-800" />
            <View className="items-center flex-1">
              <Typography className="text-xl font-extrabold text-gray-900 dark:text-white">
                {stats.summarizedCount}
              </Typography>
              <Typography className="text-[10px] text-gray-400">{t.rag.archived}</Typography>
            </View>
            <View className="w-[1px] h-5 bg-gray-100 dark:bg-zinc-800" />
            <View className="items-center flex-1">
              <Typography className="text-xl font-extrabold text-gray-900 dark:text-white">
                {(stats.totalTokens / 1000).toFixed(1)}k
              </Typography>
              <Typography className="text-[10px] text-gray-400">Token</Typography>
            </View>
          </View>

          <View className="h-1.5 flex-row rounded-full overflow-hidden bg-gray-100 dark:bg-zinc-800">
            <View className="flex-[2] bg-blue-500" />
            <View className="flex-[1] bg-emerald-500" />
            <View className="flex-[1] bg-amber-500" />
          </View>
        </View>

        {/* Card 2: Memory Archives */}
        <View className="bg-white dark:bg-black p-4 rounded-2xl border border-indigo-50 dark:border-indigo-500/10 shadow-sm mt-4">
          <View className="flex-row justify-between items-center mb-4">
            <Typography className="text-sm font-bold text-gray-900 dark:text-white">
              {t.rag.memoryArchives}
            </Typography>
            <View className="bg-gray-100 dark:bg-zinc-800 px-2 py-0.5 rounded-full">
              <Typography className="text-[10px] font-bold text-gray-500 dark:text-gray-400">
                {summaries.length}
              </Typography>
            </View>
          </View>

          {summaries.length === 0 ? (
            <View className="items-center py-6">
              <Archive size={24} color={isDark ? '#3f3f46' : '#cbd5e1'} />
              <Typography className="text-gray-400 text-xs mt-2">{t.rag.noArchives}</Typography>
            </View>
          ) : (
            <View>
              {displayedSummaries.map((summary, index) => (
                <View
                  key={summary.id}
                  className={clsx(
                    "py-3",
                    index !== displayedSummaries.length - 1 && "border-b border-gray-50 dark:border-zinc-800/50"
                  )}
                >
                  <View className="flex-row items-center mb-2">
                    <View
                      style={{ backgroundColor: colors.opacity10 }}
                      className="px-2 py-0.5 rounded-full mr-2"
                    >
                      <Typography style={{ color: colors[600] }} className="text-[9px] font-bold">
                        SUMMARY
                      </Typography>
                    </View>
                    <Typography className="text-[11px] text-gray-400 flex-1">
                      {new Date(summary.createdAt).toLocaleString('zh-CN', {
                        month: 'numeric',
                        day: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </Typography>
                    <TouchableOpacity
                      onPress={() => handleDeleteSummary(summary.id)}
                      className="p-1.5 bg-gray-50 dark:bg-zinc-800/50 rounded-full"
                    >
                      <X size={12} color="#94a3b8" />
                    </TouchableOpacity>
                  </View>
                  <Typography
                    className="text-xs text-gray-600 dark:text-gray-300 leading-5"
                    numberOfLines={3}
                  >
                    {summary.summaryContent}
                  </Typography>
                </View>
              ))}

              {summaries.length > 3 && (
                <View className="items-center pt-3 border-t border-gray-50 dark:border-zinc-800/50 mt-1">
                  {visibleCount < summaries.length ? (
                    <TouchableOpacity onPress={handleLoadMore}>
                      <Typography style={{ color: colors[600] }} className="text-xs font-bold">
                        展开更多 ({summaries.length - visibleCount})
                      </Typography>
                    </TouchableOpacity>
                  ) : (
                    <TouchableOpacity onPress={handleShowLess}>
                      <Typography style={{ color: colors[600] }} className="text-xs font-bold">
                        收起列表
                      </Typography>
                    </TouchableOpacity>
                  )}
                </View>
              )}
            </View>
          )}
        </View>
      </View>
    </View>
  );
};


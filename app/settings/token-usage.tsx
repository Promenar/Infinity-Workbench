import React, { useState, useMemo } from 'react';
import { View, ScrollView, TouchableOpacity, Alert } from 'react-native';
import { PageLayout, Typography, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { useTokenStatsStore, ProviderStats, ProviderModelStats } from '../../src/store/token-stats-store';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ChevronLeft, Trash2, BarChart2, MessageSquare, Zap, Database, ChevronDown, ChevronRight, Filter } from 'lucide-react-native';
import * as Haptics from '../../src/lib/haptics';
import { BillingUsage, TokenMetric } from '../../src/types/chat';
import { useI18n } from '../../src/lib/i18n';
import { useApiStore } from '../../src/store/api-store';

const PROVIDER_DISPLAY_NAMES: Record<string, string> = {
  openai: 'OpenAI',
  anthropic: 'Anthropic',
  google: 'Google (VertexAI)',
  gemini: 'Google Gemini',
  deepseek: 'DeepSeek',
  moonshot: 'Moonshot (Kimi)',
  zhipu: 'ZhiPu',
  siliconflow: 'SiliconFlow',
  github: 'GitHub Models',
  cloudflare: 'Cloudflare Workers AI',
  'github-copilot': 'GitHub Copilot',
  'openai-compatible': 'OpenAI Compatible',
  local: 'Local Model',
};

export default function TokenUsageScreen() {
  const router = useRouter();
  const { isDark } = useTheme();
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const { globalTotal, byModel, byProvider, resetGlobalStats } = useTokenStatsStore();
  const { providers } = useApiStore();
  const [expandedProviders, setExpandedProviders] = useState<Set<string>>(new Set());
  const [viewMode, setViewMode] = useState<'provider' | 'model'>('provider');

  const getProviderDisplayName = (providerId: string) => {
    const providerStats = byProvider[providerId];
    if (providerStats?.displayName) return providerStats.displayName;
    return PROVIDER_DISPLAY_NAMES[providerId] || providerId;
  };

  const getModelName = (id: string) => {
    for (const provider of providers) {
      const model = provider.models.find((m) => m.id === id || m.uuid === id);
      if (model) return model.name;
    }
    return id;
  };

  const toggleProviderExpand = (providerId: string) => {
    setExpandedProviders((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(providerId)) newSet.delete(providerId);
      else newSet.add(providerId);
      return newSet;
    });
  };

  const providerIds = Object.keys(byProvider);

  const handleResetAll = () => {
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    Alert.alert(
      t.settings.tokenUsageDetails.resetConfirmTitle || 'Reset Statistics',
      t.settings.tokenUsageDetails.resetConfirmMessage || 'Are you sure?',
      [
        { text: t.settings.tokenUsageDetails.resetCancel || 'Cancel', style: 'cancel' },
        {
          text: t.settings.tokenUsageDetails.resetConfirm || 'Reset',
          style: 'destructive',
          onPress: () => {
            resetGlobalStats();
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
          },
        },
      ],
    );
  };

  const MetricCard = ({ label, metric, color, icon: Icon }: { label: string; metric: TokenMetric; color: string; icon: any }) => {
    const match = label.match(/^(.*?)\s*(\(.*\))$/);
    const title = match ? match[1] : label;
    const subtitle = match ? match[2] : null;
    return (
      <View style={{ flex: 1, backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : '#f3f4f6', padding: 16, borderRadius: 16 }}>
        <View style={{ marginBottom: 8 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <Icon size={14} color={color} style={{ marginRight: 6 }} />
            <Typography className="text-xs font-bold text-gray-500 uppercase">{title}</Typography>
          </View>
          {subtitle && <Typography className="text-[10px] font-bold text-gray-400 uppercase ml-[20px] mt-0.5">{subtitle}</Typography>}
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'baseline' }}>
          {metric.isEstimated && <Typography className="text-xs text-amber-500 mr-1">~</Typography>}
          <Typography className="text-xl font-black text-black dark:text-white">{metric.count.toLocaleString()}</Typography>
        </View>
      </View>
    );
  };

  const ModelSimpleRow = ({ modelId, stats }: { modelId: string; stats: ProviderModelStats }) => (
    <View style={{ paddingVertical: 10, paddingHorizontal: 12, backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)', borderRadius: 8, marginBottom: 6, borderLeftWidth: 3, borderLeftColor: '#8b5cf6' }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <View style={{ flex: 1, marginRight: 8 }}>
          <Typography className="font-semibold text-sm text-black dark:text-white" numberOfLines={1}>{getModelName(modelId)}</Typography>
        </View>
        <Typography className="font-bold text-sm text-gray-600 dark:text-gray-300">{stats.total.toLocaleString()}</Typography>
      </View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 }}>
        <Typography className="text-[10px] text-gray-400">Input: {stats.input.toLocaleString()}</Typography>
        <Typography className="text-[10px] text-gray-400">Output: {stats.output.toLocaleString()}</Typography>
      </View>
    </View>
  );

  const ProviderRow = ({ providerId, stats }: { providerId: string; stats: ProviderStats }) => {
    const isExpanded = expandedProviders.has(providerId);
    const modelCount = Object.keys(stats.models).length;
    return (
      <View style={{ marginBottom: 12, backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#f9fafb', borderRadius: 16, borderWidth: 1, borderColor: isDark ? 'rgba(255,255,255,0.08)' : '#e5e7eb', overflow: 'hidden' }}>
        <TouchableOpacity onPress={() => toggleProviderExpand(providerId)} style={{ padding: 16 }}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <View style={{ flex: 1, marginRight: 8 }}>
              <Typography className="font-bold text-base text-black dark:text-white">{getProviderDisplayName(providerId)}</Typography>
              <Typography className="text-[10px] text-gray-400">{modelCount} model{modelCount !== 1 ? 's' : ''}</Typography>
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <Typography className="font-black text-gray-500 dark:text-gray-400 mr-2">{stats.total.total.toLocaleString()}</Typography>
              {isExpanded ? <ChevronDown size={18} color={isDark ? '#888' : '#666'} /> : <ChevronRight size={18} color={isDark ? '#888' : '#666'} />}
            </View>
          </View>
          <View style={{ flexDirection: 'row', height: 4, borderRadius: 2, overflow: 'hidden', backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : '#e5e7eb', marginTop: 8 }}>
            <View style={{ flex: Math.max(stats.total.input, 1), backgroundColor: '#8b5cf6' }} />
            <View style={{ flex: Math.max(stats.total.output, 1), backgroundColor: '#f59e0b' }} />
          </View>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 }}>
            <Typography className="text-[10px] text-gray-400">Input: {stats.total.input.toLocaleString()}</Typography>
            <Typography className="text-[10px] text-gray-400">Output: {stats.total.output.toLocaleString()}</Typography>
          </View>
        </TouchableOpacity>
        {isExpanded && (
          <View style={{ paddingHorizontal: 12, paddingBottom: 12 }}>
            {Object.entries(stats.models).map(([modelId, modelStats]) => (
              <ModelSimpleRow key={modelId} modelId={modelId} stats={modelStats} />
            ))}
          </View>
        )}
      </View>
    );
  };

  const ModelRow = ({ modelId, usage }: { modelId: string; usage: BillingUsage }) => (
    <View style={{ marginBottom: 12, padding: 16, backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : '#f9fafb', borderRadius: 16, borderWidth: 1, borderColor: isDark ? 'rgba(255,255,255,0.05)' : '#e5e7eb' }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <View style={{ flex: 1, marginRight: 8 }}>
          <Typography className="font-bold text-base text-black dark:text-white" numberOfLines={1}>{getModelName(modelId)}</Typography>
          {getModelName(modelId) !== modelId && <Typography className="text-[10px] text-gray-400" numberOfLines={1}>{modelId}</Typography>}
        </View>
        <Typography className="font-black text-gray-500 dark:text-gray-400">{usage.total.toLocaleString()}</Typography>
      </View>
      <View style={{ flexDirection: 'row', height: 6, borderRadius: 3, overflow: 'hidden', backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : '#e5e7eb' }}>
        <View style={{ flex: Math.max(usage.chatInput.count, 0.001), backgroundColor: '#8b5cf6' }} />
        <View style={{ flex: Math.max(usage.chatOutput.count, 0.001), backgroundColor: '#f59e0b' }} />
        <View style={{ flex: Math.max(usage.ragSystem.count, 0.001), backgroundColor: '#10b981' }} />
      </View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 8 }}>
        <Typography className="text-[10px] text-gray-400">Input: {usage.chatInput.count.toLocaleString()}</Typography>
        <Typography className="text-[10px] text-gray-400">Output: {usage.chatOutput.count.toLocaleString()}</Typography>
        <Typography className="text-[10px] text-gray-400">System: {usage.ragSystem.count.toLocaleString()}</Typography>
      </View>
    </View>
  );

  return (
    <PageLayout className="bg-white dark:bg-black" safeArea={false}>
      <Stack.Screen options={{ headerShown: false }} />
      <GlassHeader
        title={t.settings.tokenUsageDetails.title || 'Token Usage'}
        subtitle={t.settings.tokenUsageDetails.globalBreakdown || 'Global Statistics Breakdown'}
        leftAction={{ icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />, onPress: () => router.back(), label: t.common.back }}
      />
      <ScrollView contentContainerStyle={{ paddingHorizontal: 24, paddingBottom: 100, paddingTop: insets.top + 80 }} showsVerticalScrollIndicator={false}>
        {/* Global Summary */}
        <View style={{ alignItems: 'center', marginBottom: 32 }}>
          <View style={{ width: 200, height: 200, borderRadius: 100, borderWidth: 4, borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)', borderStyle: 'dashed', alignItems: 'center', justifyContent: 'center', marginBottom: 24 }}>
            <Typography className="text-5xl font-black text-black dark:text-white" style={{ letterSpacing: -2 }}>{(globalTotal.total / 1000).toFixed(1)}k</Typography>
            <Typography className="text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-widest mt-2">{t.settings.tokenUsageDetails.totalTokens || 'Total Tokens'}</Typography>
          </View>
          <View style={{ flexDirection: 'row', gap: 12, width: '100%' }}>
            <MetricCard label={t.settings.tokenUsageDetails.input || 'Input'} metric={globalTotal.chatInput} color="#8b5cf6" icon={MessageSquare} />
            <MetricCard label={t.settings.tokenUsageDetails.output || 'Output'} metric={globalTotal.chatOutput} color="#f59e0b" icon={Zap} />
            <MetricCard label={t.settings.tokenUsageDetails.system || 'System'} metric={globalTotal.ragSystem} color="#10b981" icon={Database} />
          </View>
        </View>

        {/* View Mode Toggle */}
        {providerIds.length > 0 && (
          <View style={{ flexDirection: 'row', marginBottom: 16, backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#f3f4f6', borderRadius: 12, padding: 4 }}>
            <TouchableOpacity onPress={() => setViewMode('provider')} style={{ flex: 1, paddingVertical: 10, alignItems: 'center', borderRadius: 10, backgroundColor: viewMode === 'provider' ? (isDark ? 'rgba(255,255,255,0.1)' : '#fff') : 'transparent' }}>
              <Typography className={viewMode === 'provider' ? 'font-bold text-sm text-black dark:text-white' : 'font-medium text-sm text-gray-500'}>By Provider</Typography>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => setViewMode('model')} style={{ flex: 1, paddingVertical: 10, alignItems: 'center', borderRadius: 10, backgroundColor: viewMode === 'model' ? (isDark ? 'rgba(255,255,255,0.1)' : '#fff') : 'transparent' }}>
              <Typography className={viewMode === 'model' ? 'font-bold text-sm text-black dark:text-white' : 'font-medium text-sm text-gray-500'}>By Model</Typography>
            </TouchableOpacity>
          </View>
        )}

        {/* By Provider View */}
        {viewMode === 'provider' && (
          <View style={{ marginBottom: 40 }}>
            <Typography className="text-lg font-bold mb-4 ml-1">By Provider</Typography>
            {providerIds.length > 0 ? (
              providerIds.map((providerId) => (
                <ProviderRow key={providerId} providerId={providerId} stats={byProvider[providerId]} />
              ))
            ) : (
              <Typography className="text-gray-400 text-center py-8">{t.settings.tokenUsageDetails.noHistory || 'No usage history found.'}</Typography>
            )}
          </View>
        )}

        {/* By Model View */}
        {viewMode === 'model' && (
          <View style={{ marginBottom: 40 }}>
            <Typography className="text-lg font-bold mb-4 ml-1">{t.settings.tokenUsageDetails.breakdownByModel || 'Breakdown by Model'}</Typography>
            {Object.entries(byModel).map(([modelId, usage]) => (
              <ModelRow key={modelId} modelId={modelId} usage={usage} />
            ))}
            {Object.keys(byModel).length === 0 && (
              <Typography className="text-gray-400 text-center py-8">{t.settings.tokenUsageDetails.noHistory || 'No usage history found.'}</Typography>
            )}
          </View>
        )}

        {/* Danger Zone */}
        <TouchableOpacity onPress={handleResetAll} style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: 16, borderRadius: 16, backgroundColor: isDark ? 'rgba(239, 68, 68, 0.1)' : '#fee2e2' }}>
          <Trash2 size={18} color="#ef4444" style={{ marginRight: 8 }} />
          <Typography className="font-bold text-red-500 dark:text-red-400">{t.settings.tokenUsageDetails.reset || 'Reset All Statistics'}</Typography>
        </TouchableOpacity>
        <Typography className="text-[10px] text-gray-400 text-center mt-6">{t.settings.tokenUsageDetails.localDataWarning || 'Stats are stored locally on your device.'}</Typography>
      </ScrollView>
    </PageLayout>
  );
}

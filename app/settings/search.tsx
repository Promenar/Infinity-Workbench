import React, { useState, useEffect } from 'react';
import { View, TextInput, TouchableOpacity, ScrollView, Linking } from 'react-native';
import { PageLayout, Typography, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { ChevronLeft, Layers, ExternalLink } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useApiStore } from '../../src/store/api-store';
import { useI18n } from '../../src/lib/i18n';
import * as Haptics from '../../src/lib/haptics';
import Slider from '@react-native-community/slider';
import { SettingsSection } from '../../src/features/settings/components/SettingsSection';
import { Card } from '../../src/components/ui/Card';

const PROVIDERS = [
  { id: 'google', name: 'Google' },
  { id: 'tavily', name: 'Tavily' },
  { id: 'bing', name: 'Bing' },
  { id: 'bocha', name: 'Bocha' },
  { id: 'searxng', name: 'SearXNG' },
] as const;

export default function SearchSettingsScreen() {
  const { isDark, colors } = useTheme();
  const router = useRouter();
  const { t } = useI18n();
  const { searchConfig, setSearchConfig } = useApiStore();

  const [provider, setProvider] = useState(searchConfig.provider);
  const [maxResults, setMaxResults] = useState(searchConfig.maxResults);

  const [googleKey, setGoogleKey] = useState(searchConfig.google?.apiKey || '');
  const [googleCx, setGoogleCx] = useState(searchConfig.google?.cx || '');
  const [tavilyKey, setTavilyKey] = useState(searchConfig.tavily?.apiKey || '');
  const [bingKey, setBingKey] = useState(searchConfig.bing?.apiKey || '');
  const [bochaKey, setBochaKey] = useState(searchConfig.bocha?.apiKey || '');
  const [searxngUrl, setSearxngUrl] = useState(searchConfig.searxng?.baseUrl || '');

  const [hasChanges, setHasChanges] = useState(false);

  useEffect(() => {
    const isChanged =
      provider !== searchConfig.provider ||
      maxResults !== searchConfig.maxResults ||
      googleKey !== (searchConfig.google?.apiKey || '') ||
      googleCx !== (searchConfig.google?.cx || '') ||
      tavilyKey !== (searchConfig.tavily?.apiKey || '') ||
      bingKey !== (searchConfig.bing?.apiKey || '') ||
      bochaKey !== (searchConfig.bocha?.apiKey || '') ||
      searxngUrl !== (searchConfig.searxng?.baseUrl || '');

    setHasChanges(isChanged);
  }, [provider, maxResults, googleKey, googleCx, tavilyKey, bingKey, bochaKey, searxngUrl, searchConfig]);

  const handleSave = () => {
    setSearchConfig({
      provider,
      maxResults,
      google: { apiKey: googleKey.trim(), cx: googleCx.trim() },
      tavily: { apiKey: tavilyKey.trim() },
      bing: { apiKey: bingKey.trim() },
      bocha: { apiKey: bochaKey.trim() },
      searxng: { baseUrl: searxngUrl.trim() },
    });
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    setHasChanges(false);
    router.back();
  };

  const renderConfigSection = () => {
    let content;
    switch (provider) {
      case 'google':
        content = (
          <View className="gap-4">
            <View>
              <Typography className="text-xs font-semibold text-gray-500 dark:text-zinc-500 mb-2 uppercase tracking-wider">{t.settings.google.apiKey}</Typography>
              <TextInput
                value={googleKey}
                onChangeText={setGoogleKey}
                placeholder="AIza..."
                className="p-3.5 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 text-gray-900 dark:text-white"
                autoCapitalize="none"
              />
            </View>
            <View>
              <Typography className="text-xs font-semibold text-gray-500 dark:text-zinc-500 mb-2 uppercase tracking-wider">{t.settings.google.cx}</Typography>
              <TextInput
                value={googleCx}
                onChangeText={setGoogleCx}
                placeholder="0123..."
                className="p-3.5 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 text-gray-900 dark:text-white"
                autoCapitalize="none"
              />
            </View>
          </View>
        );
        break;
      case 'tavily':
        content = (
          <View className="gap-4">
            <View>
              <Typography className="text-xs font-semibold text-gray-500 dark:text-zinc-500 mb-2 uppercase tracking-wider">{t.settings.tavily.apiKey}</Typography>
              <TextInput
                value={tavilyKey}
                onChangeText={setTavilyKey}
                placeholder="tvly-..."
                className="p-3.5 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 text-gray-900 dark:text-white"
                autoCapitalize="none"
              />
            </View>
            <TouchableOpacity onPress={() => Linking.openURL('https://tavily.com/')} className="flex-row items-center">
              <ExternalLink size={12} color={colors[500]} className="mr-1" />
              <Typography className="text-xs text-blue-500">{t.settings.tavily.getApiKey}</Typography>
            </TouchableOpacity>
          </View>
        );
        break;
      case 'bing':
        content = (
          <View>
            <Typography className="text-xs font-semibold text-gray-500 dark:text-zinc-500 mb-2 uppercase tracking-wider">{t.settings.bing.apiKey}</Typography>
            <TextInput
              value={bingKey}
              onChangeText={setBingKey}
              placeholder="Bing API Key"
              className="p-3.5 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 text-gray-900 dark:text-white"
              autoCapitalize="none"
            />
          </View>
        );
        break;
      case 'bocha':
        content = (
          <View className="gap-4">
            <View>
              <Typography className="text-xs font-semibold text-gray-500 dark:text-zinc-500 mb-2 uppercase tracking-wider">{t.settings.bocha.apiKey}</Typography>
              <TextInput
                value={bochaKey}
                onChangeText={setBochaKey}
                placeholder="Bocha API Key"
                className="p-3.5 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 text-gray-900 dark:text-white"
                autoCapitalize="none"
              />
            </View>
            <TouchableOpacity onPress={() => Linking.openURL('https://open.bochaai.com/')} className="flex-row items-center">
              <ExternalLink size={12} color={colors[500]} className="mr-1" />
              <Typography className="text-xs text-blue-500">{t.settings.bocha.getApiKey}</Typography>
            </TouchableOpacity>
          </View>
        );
        break;
      case 'searxng':
        content = (
          <View>
            <Typography className="text-xs font-semibold text-gray-500 dark:text-zinc-500 mb-2 uppercase tracking-wider">{t.settings.searxng.baseUrl}</Typography>
            <TextInput
              value={searxngUrl}
              onChangeText={setSearxngUrl}
              placeholder="https://searxng.instance.com"
              className="p-3.5 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-100 dark:border-zinc-800 text-gray-900 dark:text-white"
              autoCapitalize="none"
            />
          </View>
        );
        break;
    }

    return (
      <View>
        <Typography className="text-xs text-gray-400 dark:text-zinc-500 mb-4 leading-5">
          {t.settings[provider].description}
        </Typography>
        {content}
      </View>
    );
  };

  return (
    <PageLayout className="bg-white dark:bg-black" safeArea={false}>
      <Stack.Screen options={{ headerShown: false }} />
      <GlassHeader
        title={t.settings.webSearchConfig}
        subtitle={t.settings.webSearchConfigDesc}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
        }}
      />

      <ScrollView
        className="flex-1"
        contentContainerStyle={{ paddingTop: 110, paddingBottom: 120, paddingHorizontal: 16 }}
        showsVerticalScrollIndicator={false}
      >
        {/* Engine Selector */}
        <SettingsSection title={t.settings.searchEngine}>
          <Card variant="glass" className="p-1">
            <View className="flex-row items-center justify-between">
              {PROVIDERS.map((p) => (
                <TouchableOpacity
                  key={p.id}
                  onPress={() => {
                    setProvider(p.id as any);
                    Haptics.selectionAsync();
                  }}
                  className={`flex-1 px-1 py-2.5 rounded-xl items-center justify-center ${provider === p.id
                    ? 'bg-blue-600 dark:bg-blue-600'
                    : 'bg-transparent'
                    }`}
                >
                  <Typography
                    numberOfLines={1}
                    adjustsFontSizeToFit
                    className={`text-[11px] font-bold ${provider === p.id ? 'text-white' : 'text-gray-500 dark:text-zinc-500'}`}
                  >
                    {p.name}
                  </Typography>
                </TouchableOpacity>
              ))}
            </View>
          </Card>
        </SettingsSection>

        {/* Max Results */}
        <SettingsSection title={t.settings.maxResults}>
          <Card variant="glass" className="p-5">
            <View className="flex-row items-center justify-between mb-2">
              <View className="flex-row items-center">
                <Layers size={16} color={colors[500]} className="mr-2" />
                <Typography className="text-sm font-semibold text-gray-800 dark:text-zinc-200">{t.settings.maxResults}</Typography>
              </View>
              <Typography className="text-blue-500 font-bold tabular-nums">{maxResults}</Typography>
            </View>
            <Slider
              style={{ width: '100%', height: 40 }}
              minimumValue={1}
              maximumValue={10}
              step={1}
              value={maxResults}
              onValueChange={setMaxResults}
              minimumTrackTintColor="#3b82f6"
              maximumTrackTintColor={isDark ? '#27272a' : '#f3f4f6'}
              thumbTintColor="#3b82f6"
            />
            <View className="flex-row justify-between">
              <Typography variant="label" className="text-gray-400">1</Typography>
              <Typography variant="label" className="text-gray-400">10</Typography>
            </View>
          </Card>
        </SettingsSection>

        {/* Provider Specific Config */}
        <SettingsSection title={`${PROVIDERS.find(p => p.id === provider)?.name} ${t.common.settings}`}>
          <Card variant="glass" className="p-5">
            {renderConfigSection()}
          </Card>
        </SettingsSection>

        {/* Save Button */}
        <TouchableOpacity
          onPress={handleSave}
          disabled={!hasChanges}
          activeOpacity={0.8}
          className={`mt-6 py-4 rounded-2xl items-center justify-center shadow-sm ${hasChanges
            ? 'bg-blue-600'
            : 'bg-gray-100 dark:bg-zinc-800 opacity-50'
            }`}
        >
          <Typography className={`font-bold text-base ${hasChanges ? 'text-white' : 'text-gray-400 dark:text-zinc-600'}`}>
            {t.settings.google.save}
          </Typography>
        </TouchableOpacity>
      </ScrollView>
    </PageLayout>
  );
}

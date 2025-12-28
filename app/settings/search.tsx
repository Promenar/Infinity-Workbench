import React, { useState, useEffect } from 'react';
import { View, TextInput, TouchableOpacity, ScrollView, Linking } from 'react-native';
import { PageLayout, Typography } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { ChevronLeft, Info, Key, Globe, Search } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useApiStore } from '../../src/store/api-store';
import { useI18n } from '../../src/lib/i18n';
import { GlassHeader } from '../../src/components/ui/GlassHeader';
import * as Haptics from 'expo-haptics';

export default function SearchSettingsScreen() {
    const { isDark } = useTheme();
    const router = useRouter();
    const insets = useSafeAreaInsets();
    const { t } = useI18n();

    const { googleSearchConfig, setGoogleSearchConfig } = useApiStore();

    const [apiKey, setApiKey] = useState(googleSearchConfig?.apiKey || '');
    const [cx, setCx] = useState(googleSearchConfig?.cx || '');
    const [hasChanges, setHasChanges] = useState(false);

    useEffect(() => {
        setHasChanges(
            apiKey !== (googleSearchConfig?.apiKey || '') ||
            cx !== (googleSearchConfig?.cx || '')
        );
    }, [apiKey, cx, googleSearchConfig]);

    const handleSave = () => {
        setGoogleSearchConfig({ apiKey: apiKey.trim(), cx: cx.trim() });
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        setHasChanges(false);
        router.back();
    };

    return (
        <PageLayout>
            <Stack.Screen
                options={{
                    headerShown: false,
                }}
            />
            <GlassHeader
                title={t.settings.webSearchConfig}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back()
                }}
                intensity={isDark ? 40 : 60}
            />

            <ScrollView
                className="flex-1 px-4"
                contentContainerStyle={{ paddingTop: 64 + insets.top + 20, paddingBottom: 40 }}
                showsVerticalScrollIndicator={false}
            >

                {/* Intro Card */}
                <View className="p-4 mb-6 bg-blue-50 dark:bg-blue-900/20 rounded-2xl border border-blue-100 dark:border-blue-800">
                    <View className="flex-row items-start">
                        <Info size={20} color="#3b82f6" style={{ marginTop: 2, marginRight: 12 }} />
                        <View className="flex-1">
                            <Typography className="text-sm text-blue-800 dark:text-blue-100 font-medium mb-1">
                                Google Custom Search
                            </Typography>
                            <Typography className="text-xs text-blue-600 dark:text-blue-300 leading-5">
                                配置您的 Google Search API Key 和 Search Engine ID (CX)，以启用非 Google 原生模型的联网搜索能力（如 DeepSeek, OpenAI 等）。
                            </Typography>
                        </View>
                    </View>
                </View>

                {/* Form */}
                <View className="space-y-6">
                    <View>
                        <View className="flex-row items-center mb-2 space-x-2" style={{ gap: 6 }}>
                            <Key size={16} color={isDark ? '#a1a1aa' : '#52525b'} />
                            <Typography className="font-medium text-gray-700 dark:text-gray-300">
                                Google API Key
                            </Typography>
                        </View>
                        <TextInput
                            value={apiKey}
                            onChangeText={setApiKey}
                            placeholder="AIzaSy..."
                            placeholderTextColor="#9ca3af"
                            className="p-4 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-200 dark:border-zinc-800 text-gray-900 dark:text-white font-mono text-sm"
                            autoCapitalize="none"
                        />
                        <TouchableOpacity onPress={() => Linking.openURL('https://developers.google.com/custom-search/v1/introduction')}>
                            <Typography className="text-xs text-blue-500 mt-2">
                                获取 API Key -&gt;
                            </Typography>
                        </TouchableOpacity>
                    </View>

                    <View>
                        <View className="flex-row items-center mb-2 space-x-2" style={{ gap: 6 }}>
                            <Search size={16} color={isDark ? '#a1a1aa' : '#52525b'} />
                            <Typography className="font-medium text-gray-700 dark:text-gray-300">
                                Search Engine ID (CX)
                            </Typography>
                        </View>
                        <TextInput
                            value={cx}
                            onChangeText={setCx}
                            placeholder="0123456789..."
                            placeholderTextColor="#9ca3af"
                            className="p-4 bg-gray-50 dark:bg-zinc-900 rounded-xl border border-gray-200 dark:border-zinc-800 text-gray-900 dark:text-white font-mono text-sm"
                            autoCapitalize="none"
                        />
                        <TouchableOpacity onPress={() => Linking.openURL('https://programmablesearchengine.google.com/controlpanel/create')}>
                            <Typography className="text-xs text-blue-500 mt-2">
                                获取 Search Engine ID -&gt;
                            </Typography>
                        </TouchableOpacity>
                    </View>
                </View>

                {/* Save Button */}
                <TouchableOpacity
                    onPress={handleSave}
                    disabled={!hasChanges}
                    className={`mt-10 py-4 rounded-xl items-center justify-center shadow-sm ${hasChanges
                        ? 'bg-blue-600 active:bg-blue-700'
                        : 'bg-gray-200 dark:bg-zinc-800 opacity-50'
                        }`}
                >
                    <Typography className={`font-bold text-lg ${hasChanges ? 'text-white' : 'text-gray-400 dark:text-gray-500'}`}>
                        保存配置
                    </Typography>
                </TouchableOpacity>

            </ScrollView>
        </PageLayout>
    );
}

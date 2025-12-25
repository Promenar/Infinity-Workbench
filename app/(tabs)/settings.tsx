import React, { useState } from 'react';
import { View, ScrollView, Switch, TouchableOpacity, Text } from 'react-native';
import { PageLayout, Typography } from '../../src/components/ui';
import { Moon, Database, Shield, ChevronRight, Bell, Settings as SettingsIcon, Server, Lock, Globe, Info } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useToast } from '../../src/components/ui/Toast';
import { clsx } from 'clsx';
import { Stack } from 'expo-router';
import * as Haptics from 'expo-haptics';
import Animated, { FadeIn } from 'react-native-reanimated';
import { useSettingsStore, Language } from '../../src/store/settings-store';
import { useI18n } from '../../src/lib/i18n';

// Lumina-style Section Header
function SettingsGroup({ title, children }: { title?: string, children: React.ReactNode }) {
    return (
        <View className="mb-6">
            {title && <Typography variant="sectionHeader" className="ml-1 mb-2 text-[12px] text-gray-400 dark:text-gray-500 font-bold uppercase tracking-widest">{title}</Typography>}
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl overflow-hidden border border-gray-100 dark:border-zinc-800">
                {children}
            </View>
        </View>
    );
}

// Lumina-style List Item (Monochrome, no colored square)
function SettingsItem({
    icon,
    label,
    subtitle,
    value,
    onPress,
    isFirst,
    isLast,
    showChevron = true
}: {
    icon: any,
    label: string,
    subtitle?: string,
    value?: React.ReactNode,
    onPress?: () => void,
    isFirst?: boolean,
    isLast?: boolean,
    showChevron?: boolean
}) {
    const content = (
        <View className={clsx(
            "flex-row items-center px-5 py-5",
            isLast ? "" : "border-b border-gray-100 dark:border-zinc-800/50"
        )}>
            {/* Monochrome Icon */}
            <View className="mr-4">
                {React.cloneElement(icon as React.ReactElement<any>, { size: 22, color: '#64748b', strokeWidth: 1.5 })}
            </View>

            {/* Content */}
            <View className="flex-1 flex-row items-center justify-between">
                <View className="flex-1 mr-2">
                    <Typography variant="body" className="font-bold text-[17px] text-gray-800 dark:text-gray-100" numberOfLines={1}>{label}</Typography>
                    {subtitle && <Typography variant="caption" className="text-gray-400 text-[12px] mt-0.5" numberOfLines={1}>{subtitle}</Typography>}
                </View>
                <View className="flex-row items-center">
                    {value}
                    {showChevron && <ChevronRight size={18} color="#cbd5e1" className="ml-2" />}
                </View>
            </View>
        </View>
    );

    if (onPress) {
        return (
            <TouchableOpacity
                onPress={() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    onPress();
                }}
                activeOpacity={0.7}
            >
                {content}
            </TouchableOpacity>
        );
    }

    return content;
}

export default function SettingsScreen() {
    const { theme, setTheme } = useTheme();
    const { showToast } = useToast();
    const { language, setLanguage } = useSettingsStore();
    const { t, hasHydrated } = useI18n();
    const [activeTab, setActiveTab] = useState<'app' | 'account'>('app');

    // Handle language change toast
    React.useEffect(() => {
        if (hasHydrated) {
            const timer = setTimeout(() => {
                showToast(t.common.languageChanged, 'success');
            }, 300); // 稍微延迟，等待布局稳定
            return () => clearTimeout(timer);
        }
    }, [language]);

    const toggleLanguage = () => {
        const nextLang: Language = language === 'zh' ? 'en' : 'zh';
        setLanguage(nextLang);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        // Note: The toast will show the NEW language's message because components re-render
        // However, it's safer to use the constant here or trigger it inside an effect if needed.
        // For simplicity, we use the next value's translation via a manual check if needed,
        // or just rely on the re-render.
    };

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />

            {/* 页面头部 */}
            <View className="pt-16 px-6 pb-2">
                <View className="h-14 justify-center">
                    <Typography variant="h1" className="text-[32px] font-black text-gray-900 dark:text-white tracking-tight leading-none">{t.settings.title}</Typography>
                    <Typography variant="body" className="text-gray-400 font-bold uppercase text-[11px] tracking-widest mt-1 leading-none">{t.settings.subtitle}</Typography>
                </View>
            </View>

            <ScrollView className="flex-1 px-6" contentContainerStyle={{ paddingBottom: 100 }}>

                {/* 分段式标签切换器 (Lumina 风格) */}
                <View className="flex-row mb-8 bg-gray-100 dark:bg-zinc-900 p-1 rounded-2xl">
                    <TouchableOpacity
                        onPress={() => { Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light); setActiveTab('app'); }}
                        className={`flex-1 py-3 rounded-xl items-center flex-row justify-center gap-2 ${activeTab === 'app' ? 'bg-white dark:bg-zinc-800 shadow-sm' : ''}`}
                    >
                        <SettingsIcon size={16} color={activeTab === 'app' ? '#6366f1' : '#9ca3af'} />
                        <Text className={`font-bold ${activeTab === 'app' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>{t.settings.appSettings}</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                        onPress={() => { Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light); setActiveTab('account'); }}
                        className={`flex-1 py-3 rounded-xl items-center flex-row justify-center gap-2 ${activeTab === 'account' ? 'bg-white dark:bg-zinc-800 shadow-sm' : ''}`}
                    >
                        <Server size={16} color={activeTab === 'account' ? '#6366f1' : '#9ca3af'} />
                        <Text className={`font-bold ${activeTab === 'account' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>{t.settings.serverSettings}</Text>
                    </TouchableOpacity>
                </View>

                {activeTab === 'app' ? (
                    <Animated.View entering={FadeIn.duration(150)}>
                        <SettingsGroup>
                            <SettingsItem
                                icon={<Globe />}
                                label={t.settings.language}
                                subtitle={language === 'zh' ? '简体中文' : 'English'}
                                showChevron={false}
                                value={
                                    <View className="flex-row bg-gray-200 dark:bg-zinc-800 rounded-full p-1">
                                        <TouchableOpacity
                                            onPress={() => setLanguage('zh')}
                                            className={clsx("px-3 py-1 rounded-full", language === 'zh' ? "bg-white dark:bg-zinc-700 shadow-sm" : "")}
                                        >
                                            <Text className={clsx("text-[12px] font-bold", language === 'zh' ? "text-indigo-600" : "text-gray-400")}>中</Text>
                                        </TouchableOpacity>
                                        <TouchableOpacity
                                            onPress={() => setLanguage('en')}
                                            className={clsx("px-3 py-1 rounded-full", language === 'en' ? "bg-white dark:bg-zinc-700 shadow-sm" : "")}
                                        >
                                            <Text className={clsx("text-[12px] font-bold", language === 'en' ? "text-indigo-600" : "text-gray-400")}>EN</Text>
                                        </TouchableOpacity>
                                    </View>
                                }
                            />
                            <SettingsItem
                                icon={<Bell />}
                                label={t.settings.notifications}
                                subtitle={t.settings.notificationsDesc}
                                isLast
                                onPress={() => showToast(t.settings.notifications, 'info')}
                            />
                        </SettingsGroup>

                        <SettingsGroup title={t.settings.appearance}>
                            <SettingsItem
                                icon={<Moon />}
                                label={t.settings.appearance}
                                subtitle={t.settings.themeDark}
                                showChevron={false}
                                isLast
                                value={
                                    <Switch
                                        value={theme === 'dark'}
                                        onValueChange={(v) => {
                                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                                            setTheme(v ? 'dark' : 'light');
                                        }}
                                        trackColor={{ false: '#e2e8f0', true: '#818cf8' }}
                                        thumbColor={'#ffffff'}
                                    />
                                }
                            />
                        </SettingsGroup>
                    </Animated.View>
                ) : (
                    <Animated.View entering={FadeIn.duration(150)}>
                        <SettingsGroup>
                            <SettingsItem
                                icon={<Database />}
                                label={t.settings.dataStorage}
                                subtitle={t.settings.dataStorageDesc}
                                onPress={() => showToast(t.settings.dataStorage, 'info')}
                            />
                            <SettingsItem
                                icon={<Lock />}
                                label={t.settings.privacy}
                                subtitle={t.settings.privacyDesc}
                                onPress={() => showToast(t.settings.privacy, 'info')}
                            />
                            <SettingsItem
                                icon={<Info />}
                                label={t.settings.about}
                                subtitle={`${t.settings.version} v1.0.0 (Build 42)`}
                                isLast
                                onPress={() => showToast(t.settings.about, 'info')}
                            />
                        </SettingsGroup>
                    </Animated.View>
                )}

                <View className="mt-4 items-center">
                    <Typography className="text-gray-300 text-[12px] font-bold tracking-widest uppercase">NeuralFlow AI</Typography>
                </View>
            </ScrollView>
        </PageLayout>
    );
}

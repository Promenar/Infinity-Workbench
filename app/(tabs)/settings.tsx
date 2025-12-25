import React, { useState } from 'react';
import { View, ScrollView, Switch, TouchableOpacity, Text } from 'react-native';
import { PageLayout, Typography } from '../../src/components/ui';
import { Moon, Bell, Settings as SettingsIcon, Server, Lock, Globe, Info } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useToast } from '../../src/components/ui/Toast';
import { clsx } from 'clsx';
import * as Haptics from 'expo-haptics';
import { useSettingsStore } from '../../src/store/settings-store';
import { useI18n } from '../../src/lib/i18n';

// SettingsGroup 组件
function SettingsGroup({ title, children }: { title?: string, children: React.ReactNode }) {
    return (
        <View className="mb-6">
            {title && <Typography variant="sectionHeader" className="ml-1 mb-2 text-xs text-gray-400 dark:text-gray-500 font-bold uppercase tracking-widest">{title}</Typography>}
            <View className="bg-gray-50 dark:bg-zinc-900 rounded-3xl overflow-hidden border border-gray-100 dark:border-zinc-800">
                {children}
            </View>
        </View>
    );
}

// SettingsItem 组件
function SettingsItem({
    icon,
    label,
    subtitle,
    onPress,
    value,
    isLast,
    showChevron = true
}: {
    icon: any,
    label: string,
    subtitle?: string,
    onPress?: () => void,
    value?: React.ReactNode,
    isLast?: boolean,
    showChevron?: boolean
}) {
    const content = (
        <View className={clsx(
            "flex-row items-center px-5 py-5",
            isLast ? "" : "border-b border-gray-100 dark:border-zinc-800/50"
        )}>
            <View className="mr-4">
                {React.isValidElement(icon) ? (
                    React.cloneElement(icon as React.ReactElement<any>, {
                        size: 22,
                        color: '#64748b',
                        strokeWidth: 1.5
                    })
                ) : (
                    <View className="w-[22px] h-[22px]" />
                )}
            </View>
            <View className="flex-1 flex-row items-center justify-between">
                <View className="flex-1 mr-2">
                    <Typography variant="body" className="font-bold text-base text-gray-800 dark:text-gray-100" numberOfLines={1}>{label}</Typography>
                    {subtitle && <Typography variant="caption" className="text-gray-400 text-xs mt-0.5" numberOfLines={1}>{subtitle}</Typography>}
                </View>
                <View className="flex-row items-center">
                    {value}
                </View>
            </View>
        </View>
    );

    if (onPress) {
        return (
            <TouchableOpacity
                onPress={() => {
                    setTimeout(() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                    }, 10);
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

/**
 * 设置页 - 带安全语言切换器
 */
export default function SettingsScreen() {
    const { theme, setTheme } = useTheme();
    const { showToast } = useToast();
    const { language, setLanguage } = useSettingsStore();
    const { t } = useI18n();
    const [activeTab, setActiveTab] = useState<'app' | 'providers'>('app');

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <View className="pt-16 px-6 pb-2">
                <View className="h-14 justify-center">
                    <Typography variant="h1" className="text-3xl font-black text-gray-900 dark:text-white tracking-tight leading-none">{t.settings.title}</Typography>
                    <Typography variant="body" className="text-gray-400 font-bold uppercase text-xs tracking-widest mt-1 leading-none">{t.settings.subtitle}</Typography>
                </View>
            </View>

            <ScrollView className="flex-1 px-6" contentContainerStyle={{ paddingBottom: 100 }}>
                <View className="flex-row mb-8 bg-gray-100 dark:bg-zinc-900 p-1 rounded-2xl">
                    <TouchableOpacity
                        onPress={() => {
                            setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                            setActiveTab('app');
                        }}
                        className={`flex-1 py-3 rounded-xl items-center ${activeTab === 'app' ? 'bg-white dark:bg-zinc-800' : ''}`}>
                        <Text className={`font-bold ${activeTab === 'app' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>应用设置</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                        onPress={() => {
                            setTimeout(() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light), 10);
                            setActiveTab('providers');
                        }}
                        className={`flex-1 py-3 rounded-xl items-center ${activeTab === 'providers' ? 'bg-white dark:bg-zinc-800' : ''}`}>
                        <Text className={`font-bold ${activeTab === 'providers' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>供应商</Text>
                    </TouchableOpacity>
                </View>

                {activeTab === 'app' ? (
                    <SettingsGroup>
                        <SettingsItem
                            icon={<Globe />}
                            label={t.settings.language}
                            subtitle={language === 'zh' ? '简体中文' : 'English'}
                            showChevron={false}
                            value={
                                <View className="flex-row bg-gray-200 dark:bg-zinc-800 rounded-full p-1">
                                    <TouchableOpacity
                                        onPress={() => {
                                            // 死锁防御：延迟触感和状态变更
                                            setTimeout(() => {
                                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                                setLanguage('zh');
                                            }, 10);
                                        }}
                                        className={clsx("px-3 py-1 rounded-full", language === 'zh' ? "bg-white dark:bg-zinc-700 shadow-sm" : "")}>
                                        <Text className={clsx("text-xs font-bold", language === 'zh' ? "text-indigo-600" : "text-gray-400")}>中</Text>
                                    </TouchableOpacity>
                                    <TouchableOpacity
                                        onPress={() => {
                                            // 死锁防御：延迟触感和状态变更
                                            setTimeout(() => {
                                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                                                setLanguage('en');
                                            }, 10);
                                        }}
                                        className={clsx("px-3 py-1 rounded-full", language === 'en' ? "bg-white dark:bg-zinc-700 shadow-sm" : "")}>
                                        <Text className={clsx("text-xs font-bold", language === 'en' ? "text-indigo-600" : "text-gray-400")}>EN</Text>
                                    </TouchableOpacity>
                                </View>
                            }
                        />
                        <SettingsItem
                            icon={<Bell />}
                            label={t.settings.notifications}
                            subtitle={t.settings.notificationsDesc}
                            onPress={() => showToast(t.settings.notifications, 'info')}
                        />
                        <SettingsItem
                            icon={<Moon />}
                            label={t.settings.appearance}
                            subtitle={t.settings.themeDark}
                            showChevron={false}
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
                        <SettingsItem
                            icon={<Info />}
                            label={t.settings.about}
                            subtitle="v1.0.0"
                            isLast
                            onPress={() => showToast(t.settings.about, 'info')}
                        />
                    </SettingsGroup>
                ) : (
                    <View>
                        <SettingsGroup title="API & MODELS">
                            <SettingsItem
                                icon={<Server />}
                                label="AI Providers"
                                subtitle="Manage API Keys"
                                isLast
                                onPress={() => showToast('功能开发中...', 'info')}
                            />
                        </SettingsGroup>

                        <SettingsGroup title="SYSTEM">
                            <SettingsItem
                                icon={<Lock />}
                                label={t.settings.privacy}
                                subtitle={t.settings.privacyDesc}
                                isLast
                                onPress={() => showToast(t.settings.privacy, 'info')}
                            />
                        </SettingsGroup>
                    </View>
                )}

                <View className="mt-4 items-center">
                    <Typography className="text-gray-300 text-xs font-bold tracking-widest uppercase">NeuralFlow AI</Typography>
                </View>
            </ScrollView>
        </PageLayout>
    );
}

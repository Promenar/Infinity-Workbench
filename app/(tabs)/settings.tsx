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
    const [activeTab, setActiveTab] = useState<'app' | 'account'>('app');

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />

            {/* 页面头部 */}
            <View className="pt-16 px-6 pb-4">
                <Typography variant="h1" className="text-[32px] font-black text-gray-900 dark:text-white tracking-tight">Settings</Typography>
                <Typography variant="body" className="text-gray-400 font-bold uppercase text-[11px] tracking-widest mt-1">Configure your experience</Typography>
            </View>

            <ScrollView className="flex-1 px-6" contentContainerStyle={{ paddingBottom: 100 }}>

                {/* 分段式标签切换器 (Lumina 风格) */}
                <View className="flex-row mb-8 bg-gray-100 dark:bg-zinc-900 p-1 rounded-2xl">
                    <TouchableOpacity
                        onPress={() => { Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light); setActiveTab('app'); }}
                        className={`flex-1 py-3 rounded-xl items-center flex-row justify-center gap-2 ${activeTab === 'app' ? 'bg-white dark:bg-zinc-800 shadow-sm' : ''}`}
                    >
                        <SettingsIcon size={16} color={activeTab === 'app' ? '#6366f1' : '#9ca3af'} />
                        <Text className={`font-bold ${activeTab === 'app' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>App</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                        onPress={() => { Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light); setActiveTab('account'); }}
                        className={`flex-1 py-3 rounded-xl items-center flex-row justify-center gap-2 ${activeTab === 'account' ? 'bg-white dark:bg-zinc-800 shadow-sm' : ''}`}
                    >
                        <Server size={16} color={activeTab === 'account' ? '#6366f1' : '#9ca3af'} />
                        <Text className={`font-bold ${activeTab === 'account' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>Providers</Text>
                    </TouchableOpacity>
                </View>

                {activeTab === 'app' ? (
                    <Animated.View entering={FadeIn.duration(150)}>
                        <SettingsGroup title="General">
                            <SettingsItem
                                icon={<Globe />}
                                label="Language"
                                subtitle="System Default (English)"
                                onPress={() => showToast("Language Selector", 'info')}
                            />
                            <SettingsItem
                                icon={<Bell />}
                                label="Notifications"
                                subtitle="Manage alerts and sounds"
                                isLast
                                onPress={() => showToast("Notifications Settings", 'info')}
                            />
                        </SettingsGroup>

                        <SettingsGroup title="Notification Test">
                            <View className="p-2 flex-row gap-2">
                                <TouchableOpacity
                                    onPress={() => showToast("操作执行成功", 'success')}
                                    className="flex-1 bg-white dark:bg-zinc-800 py-3 rounded-xl border border-gray-100 dark:border-zinc-700 items-center justify-center shadow-sm"
                                >
                                    <Typography className="text-indigo-600 font-bold text-[13px]">成功</Typography>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={() => showToast("检测到异常情况", 'error')}
                                    className="flex-1 bg-white dark:bg-zinc-800 py-3 rounded-xl border border-gray-100 dark:border-zinc-700 items-center justify-center shadow-sm"
                                >
                                    <Typography className="text-red-500 font-bold text-[13px]">错误</Typography>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={() => showToast("这是一条系统信息", 'info')}
                                    className="flex-1 bg-white dark:bg-zinc-800 py-3 rounded-xl border border-gray-100 dark:border-zinc-700 items-center justify-center shadow-sm"
                                >
                                    <Typography className="text-gray-500 font-bold text-[13px]">信息</Typography>
                                </TouchableOpacity>
                            </View>
                        </SettingsGroup>

                        <SettingsGroup title="Appearance">
                            <SettingsItem
                                icon={<Moon />}
                                label="Dark Mode"
                                subtitle="Adaptive theme switching"
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
                        <SettingsGroup title="Providers">
                            <SettingsItem
                                icon={<Database />}
                                label="Data & Storage"
                                subtitle="Clear cache and downloads"
                                onPress={() => showToast("Storage Management", 'info')}
                            />
                            <SettingsItem
                                icon={<Lock />}
                                label="Privacy & Security"
                                subtitle="Biometric lock and keys"
                                onPress={() => showToast("Security Settings", 'info')}
                            />
                            <SettingsItem
                                icon={<Info />}
                                label="About"
                                subtitle="v1.0.0 (Build 42)"
                                isLast
                                onPress={() => showToast("About NeuralFlow", 'info')}
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

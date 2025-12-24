import React from 'react';
import { View, ScrollView, Switch, TouchableOpacity } from 'react-native';
import { PageLayout, Typography, Header } from '../../src/components/ui';
import { Moon, Database, Shield, ChevronRight } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { clsx } from 'clsx';

function SettingsGroup({ title, children }: { title?: string, children: React.ReactNode }) {
    return (
        <View className="mb-6">
            {title && <Typography variant="caption" className="uppercase ml-4 mb-2 text-text-tertiary font-semibold">{title}</Typography>}
            <View className="bg-surface-primary dark:bg-slate-900 rounded-xl overflow-hidden mx-4 border border-transparent dark:border-slate-800">
                {children}
            </View>
        </View>
    );
}

function SettingsItem({ icon, label, value, onPress, isLast, showChevron = true }: { icon: React.ReactNode, label: string, value?: React.ReactNode, onPress?: () => void, isLast?: boolean, showChevron?: boolean }) {
    const Wrapper = onPress ? TouchableOpacity : View;
    return (
        <Wrapper onPress={onPress} activeOpacity={0.7} className={clsx("flex-row items-center justify-between p-4 bg-white dark:bg-slate-900", !isLast && "border-b border-gray-100 dark:border-slate-800")}>
            <View className="flex-row items-center">
                <View className="w-8 h-8 rounded-lg bg-gray-50 dark:bg-slate-800 items-center justify-center mr-3">
                    {icon}
                </View>
                <Typography variant="body" className="font-medium text-slate-900 dark:text-slate-100">{label}</Typography>
            </View>
            <View className="flex-row items-center">
                {value}
                {showChevron && <ChevronRight size={18} color="#94a3b8" className="ml-2" />}
            </View>
        </Wrapper>
    )
}

export default function SettingsScreen() {
    const { theme, setTheme } = useTheme();

    const handleApi = () => alert("API Configuration\n\nConfigure your LLM endpoints here. (Coming Soon)");
    const handleStorage = () => alert("Storage Management\n\nManage your local database using op-sqlite. (Coming Soon)");

    return (
        <PageLayout className="bg-gray-50 dark:bg-black" safeArea={false}>
            <View style={{ flex: 1 }}>
                <Header title="Settings" />

                <ScrollView contentContainerStyle={{ paddingVertical: 16 }}>
                    {/* Appearance */}
                    <SettingsGroup title="General">
                        <SettingsItem
                            icon={<Moon size={18} color="#6366f1" />}
                            label="Dark Mode"
                            showChevron={false}
                            isLast
                            value={
                                <Switch
                                    value={theme === 'dark'}
                                    onValueChange={(v) => setTheme(v ? 'dark' : 'light')}
                                    trackColor={{ false: '#e2e8f0', true: '#6366f1' }}
                                />
                            }
                        />
                    </SettingsGroup>

                    {/* Data */}
                    <SettingsGroup title="Storage & API">
                        <SettingsItem
                            icon={<Database size={18} color="#10b981" />}
                            label="Manage Storage"
                            onPress={handleStorage}
                        />
                        <SettingsItem
                            icon={<Shield size={18} color="#f59e0b" />}
                            label="API Keys"
                            isLast
                            onPress={handleApi}
                        />
                    </SettingsGroup>

                    <Typography variant="caption" className="text-center mt-8 text-text-tertiary">
                        NeuralFlow v1.0.0 (Build 42)
                    </Typography>
                </ScrollView>
            </View>
        </PageLayout>
    );
}

import React from 'react';
import { View, ScrollView, TouchableOpacity, TextInput } from 'react-native';
import { PageLayout, GlassHeader, Typography, Card } from '../../../components/ui';
import { ChevronLeft, Check } from 'lucide-react-native';
import { useTheme } from '../../../theme/ThemeProvider';
import { useRouter } from 'expo-router';
import { useSettingsStore } from '../../../store/settings-store';
import { ColorPickerPanel } from '../../../components/ui/ColorPickerPanel';
import { useI18n } from '../../../lib/i18n';

export default function ThemeSettingsScreen() {
    const { isDark, colors: currentColors } = useTheme();
    const router = useRouter();
    const { accentColor, setAccentColor } = useSettingsStore();
    const { t } = useI18n();

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <GlassHeader
                title={t.settings.personalization}
                subtitle={t.settings.personalizationDesc}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                }}
            />

            <ScrollView
                className="flex-1 px-5"
                contentContainerStyle={{ paddingTop: 110, paddingBottom: 40 }}
                showsVerticalScrollIndicator={false}
            >
                <View className="mb-8">
                    <ColorPickerPanel
                        color={accentColor}
                        onColorChange={setAccentColor}
                        title={t.common.color.title}
                    />
                </View>

                <Card variant="glass" className="p-6">
                    <Typography className="text-sm font-bold text-gray-900 dark:text-white mb-2 text-center">
                        {t.settings.livePreview}
                    </Typography>
                    <Typography className="text-xs text-secondary mb-6 text-center">
                        {t.settings.livePreviewDesc}
                    </Typography>

                    <TouchableOpacity
                        activeOpacity={0.8}
                        style={{ backgroundColor: currentColors[500] }}
                        className="h-14 rounded-[16px] items-center justify-center mb-6 shadow-sm shadow-indigo-500/20"
                    >
                        <Typography className="font-bold text-white text-base">{t.common.color.primaryPreview}</Typography>
                    </TouchableOpacity>

                    <View className="flex-row justify-between h-12">
                        <View className="flex-1 rounded-xl mr-2 items-center justify-center" style={{ backgroundColor: currentColors[100] }}>
                            <Typography className="text-[8px] font-bold" style={{ color: currentColors[700] }}>100</Typography>
                        </View>
                        <View className="flex-1 rounded-xl mr-2 items-center justify-center" style={{ backgroundColor: currentColors[300] }}>
                            <Typography className="text-[8px] font-bold" style={{ color: currentColors[800] }}>300</Typography>
                        </View>
                        <View className="flex-1 rounded-xl mr-2 items-center justify-center" style={{ backgroundColor: currentColors[500] }}>
                            <Typography className="text-[8px] font-bold text-white">500</Typography>
                        </View>
                        <View className="flex-1 rounded-xl mr-2 items-center justify-center" style={{ backgroundColor: currentColors[700] }}>
                            <Typography className="text-[8px] font-bold text-white">700</Typography>
                        </View>
                        <View className="flex-1 rounded-xl items-center justify-center" style={{ backgroundColor: currentColors[900] }}>
                            <Typography className="text-[8px] font-bold text-white">900</Typography>
                        </View>
                    </View>
                </Card>
            </ScrollView>
        </PageLayout>
    );
}

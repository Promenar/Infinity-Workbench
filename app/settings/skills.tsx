
import React from 'react';
import { View, ScrollView } from 'react-native';
import { PageLayout } from '../../src/components/ui';
import { GlassHeader } from '../../src/components/ui/GlassHeader';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { SkillsSettingsPanel } from '../../src/components/settings/SkillsSettingsPanel';
import { Stack, useRouter } from 'expo-router';
import { useI18n } from '../../src/lib/i18n';
import { ChevronLeft } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';

export default function SkillsSettingsScreen() {
    const { t } = useI18n();
    const router = useRouter();
    const { colors, isDark } = useTheme();
    const insets = useSafeAreaInsets();

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />
            <GlassHeader
                title={t.settings.agentSkills.title}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                }}
                intensity={isDark ? 40 : 60}
            />
            <ScrollView
                className="flex-1 px-4"
                contentContainerStyle={{
                    paddingTop: 64 + insets.top + 20,
                    paddingBottom: 40
                }}
                showsVerticalScrollIndicator={false}
            >
                <SkillsSettingsPanel />
            </ScrollView>
        </PageLayout>
    );
}

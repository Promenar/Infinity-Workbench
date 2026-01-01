import { View, ScrollView } from 'react-native';
import { PageLayout, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { AdvancedRetrievalPanel } from '../../src/features/settings/components/AdvancedRetrievalPanel';
import { useTheme } from '../../src/theme/ThemeProvider';
import { ChevronLeft } from 'lucide-react-native';
import { useI18n } from '../../src/lib/i18n';

export default function AdvancedRetrievalScreen() {
    const router = useRouter();
    const { isDark } = useTheme();
    const { t } = useI18n();

    return (
        <PageLayout>
            <Stack.Screen options={{ headerShown: false }} />
            <GlassHeader
                title={t.rag.advancedSettings}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                    label: t.common.back,
                }}
                showBorder
            />
            <ScrollView
                className="flex-1"
                showsVerticalScrollIndicator={false}
                contentContainerStyle={{
                    paddingTop: 110,
                    paddingBottom: 40,
                    paddingHorizontal: 16
                }}
            >
                <AdvancedRetrievalPanel />
            </ScrollView>
        </PageLayout>
    );
}

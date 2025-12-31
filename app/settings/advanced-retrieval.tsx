import { View, ScrollView } from 'react-native';
import { PageLayout, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { AdvancedRetrievalPanel } from '../../src/features/settings/components/AdvancedRetrievalPanel';
import { useTheme } from '../../src/theme/ThemeProvider';
import { ChevronLeft } from 'lucide-react-native';

export default function AdvancedRetrievalScreen() {
    const router = useRouter();
    const { isDark } = useTheme();

    return (
        <PageLayout>
            <Stack.Screen options={{ headerShown: false }} />
            <GlassHeader
                title="高级检索配置"
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                    label: '返回',
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

import React from 'react';
import { PageLayout, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RagDebugPanel } from '../../src/features/settings/components/RagDebugPanel';

export default function RagDebugScreen() {
    const router = useRouter();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />

            <GlassHeader
                title="向量库统计"
                subtitle="存储占用与性能分析"
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                    label: '返回',
                }}
            />

            <RagDebugPanel />
        </PageLayout>
    );
}

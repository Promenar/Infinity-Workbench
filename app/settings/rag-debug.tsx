import React from 'react';
import { PageLayout, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RagDebugPanel } from '../../src/features/settings/components/RagDebugPanel';
import { useI18n } from '../../src/lib/i18n';

export default function RagDebugScreen() {
  const router = useRouter();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.settings.vectorStats.title}
        subtitle={t.settings.vectorStats.subtitle}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
      />

      <RagDebugPanel />
    </PageLayout>
  );
}

import { View, ScrollView } from 'react-native';
import { PageLayout, GlassHeader, Typography } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { AdvancedRetrievalPanel } from '../../src/features/settings/components/AdvancedRetrievalPanel';
import { useTheme } from '../../src/theme/ThemeProvider';
import { ChevronLeft } from 'lucide-react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useI18n } from '../../src/lib/i18n';

export default function AdvancedRetrievalScreen() {
  const router = useRouter();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />
      <GlassHeader
        title={t.rag.advancedSettings}
        subtitle={t.rag.advancedSettingsDesc}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
        showBorder
      />
      <ScrollView
        className="flex-1 px-6"
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{
          paddingTop: 74 + insets.top,
          paddingBottom: 40,
        }}
      >

        <AdvancedRetrievalPanel />
      </ScrollView>
    </PageLayout>
  );
}

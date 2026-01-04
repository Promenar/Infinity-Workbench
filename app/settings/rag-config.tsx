import React from 'react';
import { View, ScrollView, KeyboardAvoidingView, Platform } from 'react-native';
import { PageLayout, GlassHeader } from '../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useI18n } from '../../src/lib/i18n';
import { GlobalRagConfigPanel } from '../../src/features/settings/components/GlobalRagConfigPanel';

export default function RagConfigScreen() {
  const router = useRouter();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.settings.ragSection}
        subtitle={t.settings.ragSettingsDesc}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: t.common.back,
        }}
      />

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        className="flex-1"
      >
        <ScrollView
          className="flex-1 px-6"
          contentContainerStyle={{
            paddingTop: 74 + insets.top,
            paddingBottom: 40,
          }}
          showsVerticalScrollIndicator={false}
        >
          <GlobalRagConfigPanel />
        </ScrollView>
      </KeyboardAvoidingView>
    </PageLayout>
  );
}

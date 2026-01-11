import React from 'react';
import { ScrollView } from 'react-native';
import { PageLayout, GlassHeader } from '../../../src/components/ui';
import { Stack, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { useTheme } from '../../../src/theme/ThemeProvider';
import { useAgentStore } from '../../../src/store/agent-store';
import { Agent } from '../../../src/types/chat';
import { AgentRagConfigPanel } from '../../../src/features/settings/components/AgentRagConfigPanel';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const SPA_AGENT_ID = 'super_assistant';

// Fallback agent definition (cached outside component to avoid infinite loop)
const FALLBACK_AGENT: Agent = {
  id: SPA_AGENT_ID,
  name: 'Super Assistant',
  description: 'Global personal assistant with access to all knowledge and history.',
  avatar: 'Sparkles',
  color: '#8b5cf6',
  systemPrompt: '',
  defaultModel: 'gpt-4o',
  params: { temperature: 0.7 },
  created: Date.now(),
};

export default function SuperAssistantRagConfigScreen() {
  const router = useRouter();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();
  const { updateAgent } = useAgentStore();

  // Use selector with stable fallback reference
  const agent = useAgentStore((state) => {
    const found = state.agents.find((a) => a.id === SPA_AGENT_ID);
    return found || FALLBACK_AGENT;
  });

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />

      <GlassHeader
        title={t.settings.ragSettings}
        subtitle="Super Personal Assistant"
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
          onPress: () => router.back(),
          label: '返回',
        }}
      />

      <ScrollView
        className="flex-1 px-6"
        contentContainerStyle={{
          paddingTop: 74 + insets.top,
          paddingBottom: 40,
        }}
        showsVerticalScrollIndicator={false}
      >
        <AgentRagConfigPanel
          agent={agent}
          onUpdate={(updates) => updateAgent(SPA_AGENT_ID, updates)}
        />
      </ScrollView>
    </PageLayout>
  );
}

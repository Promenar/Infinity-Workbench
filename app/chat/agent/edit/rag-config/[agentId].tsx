import React from 'react';
import { ScrollView } from 'react-native';
import { PageLayout, GlassHeader } from '../../../../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { useTheme } from '../../../../../src/theme/ThemeProvider';
import { useAgentStore } from '../../../../../src/store/agent-store';
import { AgentRagConfigPanel } from '../../../../../src/features/settings/components/AgentRagConfigPanel';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export default function AgentRagConfigScreen() {
    const { agentId } = useLocalSearchParams<{ agentId: string }>();
    const router = useRouter();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const { getAgent, updateAgent } = useAgentStore();
    const agent = getAgent(agentId);

    if (!agent) return null;

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />

            <GlassHeader
                title="向量库与RAG设置"
                subtitle={agent.name}
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
                    paddingBottom: 40
                }}
                showsVerticalScrollIndicator={false}
            >
                <AgentRagConfigPanel
                    agent={agent}
                    onUpdate={(updates) => updateAgent(agentId, updates)}
                />
            </ScrollView>
        </PageLayout>
    );
}

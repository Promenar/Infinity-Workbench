import React from 'react';
import { View } from 'react-native';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { PageLayout, GlassHeader } from '../src/components/ui';
import { KnowledgeGraphView } from '../src/components/rag/KnowledgeGraphView';
import { useTheme } from '../src/theme/ThemeProvider';
import { useChatStore } from '../src/store/chat-store';
import { useRagStore } from '../src/store/rag-store';
import { useAgentStore } from '../src/store/agent-store'; // Import agent store

export default function KnowledgeGraphScreen() {
  const { isDark } = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams();
  const docId = params.docId as string | undefined;
  const sessionId = params.sessionId as string | undefined;
  const agentId = params.agentId as string | undefined;

  let title = '全量知识图谱 (Beta)';
  let subtitle = '知识库全网可视化';

  // State filtering logic
  let activeDocIds: string[] | undefined = undefined;

  if (docId) {
    title = '文档图谱';
    subtitle = '当前文档的实体关系网';
    activeDocIds = [docId];
  } else if (sessionId) {
    title = '会话图谱';
    subtitle = '当前对话上下文的知识网络';

    // Resolve session active docs + global docs
    const session = useChatStore.getState().getSession(sessionId);
    const ragState = useRagStore.getState();
    const globalDocs = ragState.documents.filter(d => d.isGlobal).map(d => d.id);

    // Get agent active folders/docs
    const agentState = useAgentStore.getState(); // Assuming we can access agent store
    const agent = session ? agentState.getAgent(session.agentId) : undefined;

    // Check session overrides, then agent defaults
    // Note: This logic duplicates MemoryManager partly, strictly we should use MemoryManager helper
    // But for UI, let's just grab explicit valid IDs.

    const sessionDocIds = session?.ragOptions?.activeDocIds || [];
    // const agentDocIds = agent?.ragConfig?.activeDocIds || []; // Removed invalid access

    // Note: Ideally we recursively resolve folders too. 
    // For now, let's include global + session specific.
    activeDocIds = [...new Set([...globalDocs, ...sessionDocIds])];

  } else if (agentId) {
    title = '助手图谱';
    subtitle = '该助手的专属知识库';
    // Get agent valid docs + global
    const ragState = useRagStore.getState();
    const globalDocs = ragState.documents.filter(d => d.isGlobal).map(d => d.id);
    // Agent docs? 
    activeDocIds = globalDocs; // Simplified for now
  }

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />
      <GlassHeader
        title={title}
        subtitle={subtitle}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#FFF' : '#000'} />,
          onPress: () => router.back(),
        }}
      />

      <View style={{ flex: 1 }}>
        <KnowledgeGraphView
          docIds={activeDocIds}
          sessionId={sessionId}
          agentId={agentId}
          onNodeSelect={(id) => console.log('Selected Node:', id)}
        />
      </View>
    </PageLayout>
  );
}

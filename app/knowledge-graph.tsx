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

  // 🔑 加固：防御性解构路由参数
  let params: any = {};
  try {
    params = useLocalSearchParams() || {};
  } catch (e) {
    console.warn('[KnowledgeGraph] Failed to get search params', e);
  }

  const docId = params.docId as string | undefined;
  const folderId = params.folderId as string | undefined;
  const sessionId = params.sessionId as string | undefined;
  const agentId = params.agentId as string | undefined;

  let title = '全量知识图谱 (Beta)';
  let subtitle = '知识库全网可视化';

  // 🛡️ 环境守卫：如果没有关键上下文且未处于加载中，提供基本回退
  if (!params && !docId && !folderId && !sessionId && !agentId) {
    return <PageLayout safeArea={false} className="bg-white dark:bg-black"><View /></PageLayout>;
  }

  // State filtering logic
  let activeDocIds: string[] | undefined = undefined;

  // Use reactive hook to ensure updates when docs load
  const ragState = useRagStore();

  // Helper: Recursive Document Resolution
  const getRecursiveFolderDocs = (startFolderId: string): string[] => {
    const getAllChildFolderIds = (parentId: string): string[] => {
      const children = ragState.folders.filter(f => f.parentId === parentId).map(f => f.id);
      let grandChildren: string[] = [];
      children.forEach(childId => {
        grandChildren = [...grandChildren, ...getAllChildFolderIds(childId)];
      });
      return [...children, ...grandChildren];
    };

    const targetFolderIds = [startFolderId, ...getAllChildFolderIds(startFolderId)];

    return ragState.documents
      .filter(d => d.folderId && targetFolderIds.includes(d.folderId))
      .map(d => d.id);
  };

  if (docId) {
    title = '文档图谱';
    subtitle = '当前文档的实体关系网';
    activeDocIds = [docId];
  } else if (folderId) {
    const folder = ragState.folders.find(f => f.id === folderId);
    title = folder ? `${folder.name}` : '文件夹图谱';
    subtitle = '文件夹及其子目录的知识网络';

    activeDocIds = getRecursiveFolderDocs(folderId);
  } else if (sessionId) {
    // 1. Super Assistant -> Global View (No filters)
    if (sessionId === 'super_assistant') {
      title = '全域知识图谱';
      subtitle = '全量会话与知识库网络';
      activeDocIds = undefined; // Undefined means ALL
    } else {
      // 2. Ordinary Session -> Scoped View
      title = '会话图谱';
      subtitle = '当前对话上下文的知识网络';

      // Resolve session active docs + global docs
      const session = useChatStore.getState().getSession(sessionId);
      const globalDocs = ragState.documents.filter(d => d.isGlobal).map(d => d.id);

      const sessionDocIds = session?.ragOptions?.activeDocIds || [];
      const sessionFolderIds = session?.ragOptions?.activeFolderIds || [];

      // Recursive Folder Resolution
      let allFolderDocIds: string[] = [];
      sessionFolderIds.forEach(fid => {
        allFolderDocIds = [...allFolderDocIds, ...getRecursiveFolderDocs(fid)];
      });

      activeDocIds = [...new Set([...globalDocs, ...sessionDocIds, ...allFolderDocIds])];
    }

  } else if (agentId) {
    title = '助手图谱';
    subtitle = '该助手的专属知识库';
    // Get agent valid docs + global
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
          sessionId={sessionId === 'super_assistant' ? undefined : sessionId}
          agentId={agentId}
          onNodeSelect={(id) => console.log('Selected Node:', id)}
        />
      </View>
    </PageLayout>
  );
}

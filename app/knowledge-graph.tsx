import React from 'react';
import { View } from 'react-native';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { PageLayout, GlassHeader } from '../src/components/ui';
import { KnowledgeGraphView } from '../src/components/rag/KnowledgeGraphView';
import { useTheme } from '../src/theme/ThemeProvider';

export default function KnowledgeGraphScreen() {
  const { isDark } = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams();
  const docId = params.docId as string | undefined;

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      <Stack.Screen options={{ headerShown: false }} />
      <GlassHeader
        title={docId ? '文档图谱' : '全量知识图谱 (Beta)'}
        subtitle={docId ? '当前文档的实体关系网' : '知识库全网可视化'}
        leftAction={{
          icon: <ChevronLeft size={24} color={isDark ? '#FFF' : '#000'} />,
          onPress: () => router.back(),
        }}
      />

      <View style={{ flex: 1 }}>
        <KnowledgeGraphView
          docId={docId}
          onNodeSelect={(id) => console.log('Selected Node:', id)}
        />
      </View>
    </PageLayout>
  );
}

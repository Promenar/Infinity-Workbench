import React, { useState, useEffect, useCallback } from 'react';
import { View, TouchableOpacity, StyleSheet, FlatList, RefreshControl } from 'react-native';
import { Code, Database, PieChart, FileText, Image, ChevronRight } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import * as FileSystem from 'expo-file-system/legacy';

interface ArtifactMetadata {
  id: string;
  type: 'code' | 'data' | 'chart' | 'document' | 'image';
  name: string;
  path: string;
  size: number;
  updatedAt: number;
}

interface ArtifactListProps {
  workspacePath: string;
  onSelectArtifact: (artifact: ArtifactMetadata) => void;
}

const TYPE_ICONS: Record<string, React.ElementType> = {
  code: Code,
  data: Database,
  chart: PieChart,
  document: FileText,
  image: Image,
};

const TYPE_COLORS: Record<string, string> = {
  code: '#6366f1',
  data: '#22c55e',
  chart: '#f59e0b',
  document: '#3b82f6',
  image: '#ec4899',
};

const TYPE_LABELS: Record<string, string> = {
  code: '代码',
  data: '数据',
  chart: '图表',
  document: '文档',
  image: '图片',
};

export const ArtifactList: React.FC<ArtifactListProps> = ({ workspacePath, onSelectArtifact }) => {
  const { isDark } = useTheme();
  const [artifacts, setArtifacts] = useState<ArtifactMetadata[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const loadArtifacts = useCallback(async () => {
    try {
      const indexPath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}/.artifacts/index.json`;
      const loadedArtifacts: ArtifactMetadata[] = [];

      try {
        const content = await FileSystem.readAsStringAsync(indexPath);
        const index = JSON.parse(content);

        for (const [id, meta] of Object.entries(index)) {
          const m = meta as any;
          loadedArtifacts.push({
            id,
            type: m.type,
            name: m.name,
            path: m.path,
            size: m.size || 0,
            updatedAt: m.updatedAt || m.createdAt || Date.now(),
          });
        }
      } catch {}

      loadedArtifacts.sort((a, b) => b.updatedAt - a.updatedAt);
      setArtifacts(loadedArtifacts);
    } catch (e) {
      console.error('[ArtifactList] Load error:', e);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [workspacePath]);

  useEffect(() => {
    loadArtifacts();
  }, [loadArtifacts]);

  const handleRefresh = () => {
    setRefreshing(true);
    loadArtifacts();
  };

  const formatSize = (bytes: number): string => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const formatDate = (timestamp: number): string => {
    const date = new Date(timestamp);
    return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
  };

  const renderArtifact = ({ item }: { item: ArtifactMetadata }) => {
    const TypeIcon = TYPE_ICONS[item.type] || FileText;
    const typeColor = TYPE_COLORS[item.type] || '#6366f1';
    const typeLabel = TYPE_LABELS[item.type] || '文件';

    return (
      <TouchableOpacity
        style={[
          styles.artifactItem,
          {
            backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fafafa',
            borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
          },
        ]}
        onPress={() => onSelectArtifact(item)}
      >
        <View style={[styles.typeIcon, { backgroundColor: `${typeColor}12` }]}>
          <TypeIcon size={18} color={typeColor} />
        </View>
        <View style={styles.artifactInfo}>
          <Typography style={{ fontSize: 14, fontWeight: '500', color: isDark ? '#fff' : '#111' }} numberOfLines={1}>
            {item.name}
          </Typography>
          <View style={styles.artifactMeta}>
            <View style={[styles.typeTag, { backgroundColor: `${typeColor}12` }]}>
              <Typography style={{ fontSize: 10, color: typeColor, fontWeight: '500' }}>
                {typeLabel}
              </Typography>
            </View>
            <Typography style={{ fontSize: 11, color: isDark ? '#52525b' : '#9ca3af' }}>
              {formatSize(item.size)}
            </Typography>
            <Typography style={{ fontSize: 11, color: isDark ? '#52525b' : '#9ca3af' }}>
              {formatDate(item.updatedAt)}
            </Typography>
          </View>
        </View>
        <ChevronRight size={16} color={isDark ? '#3f3f46' : '#d1d5db'} />
      </TouchableOpacity>
    );
  };

  if (loading) {
    return (
      <View style={styles.emptyContainer}>
        <Typography style={{ color: isDark ? '#71717a' : '#6b7280', fontSize: 13 }}>加载中...</Typography>
      </View>
    );
  }

  if (artifacts.length === 0) {
    return (
      <View style={styles.emptyContainer}>
        <View style={[styles.emptyIcon, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f3f4f6' }]}>
          <Database size={32} color={isDark ? '#3f3f46' : '#d1d5db'} />
        </View>
        <Typography style={{ color: isDark ? '#71717a' : '#6b7280', marginTop: 16, fontSize: 14, textAlign: 'center' }}>
          暂无工件
        </Typography>
        <Typography style={{ color: isDark ? '#52525b' : '#9ca3af', marginTop: 6, fontSize: 12, textAlign: 'center' }}>
          使用 save_artifact 工具保存工件
        </Typography>
      </View>
    );
  }

  return (
    <FlatList
      data={artifacts}
      keyExtractor={(item) => item.id}
      renderItem={renderArtifact}
      contentContainerStyle={styles.list}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} tintColor={isDark ? '#6366f1' : '#4f46e5'} />}
      showsVerticalScrollIndicator={false}
    />
  );
};

const styles = StyleSheet.create({
  list: {
    padding: Spacing[4],
  },
  artifactItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: Spacing[4],
    borderRadius: 16,
    marginBottom: Spacing[3],
    borderWidth: 1,
  },
  typeIcon: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  artifactInfo: {
    flex: 1,
    marginLeft: Spacing[3],
  },
  artifactMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 4,
    gap: 8,
  },
  typeTag: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  emptyContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: Spacing[8],
  },
  emptyIcon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

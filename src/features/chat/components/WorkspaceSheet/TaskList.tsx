import React, { useState, useEffect, useCallback } from 'react';
import { View, TouchableOpacity, StyleSheet, FlatList, RefreshControl } from 'react-native';
import { FileText, ChevronRight, CheckCircle, Clock, PauseCircle, XCircle, AlertCircle } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import * as FileSystem from 'expo-file-system/legacy';

interface TaskMetadata {
  id: string;
  title: string;
  status: 'active' | 'completed' | 'paused' | 'cancelled' | 'failed';
  progress: number;
  updatedAt: number;
  path: string;
}

interface TaskListProps {
  workspacePath: string;
  onSelectTask: (taskId: string, path: string) => void;
}

const STATUS_ICONS: Record<string, React.ElementType> = {
  active: Clock,
  completed: CheckCircle,
  paused: PauseCircle,
  cancelled: XCircle,
  failed: AlertCircle,
};

const STATUS_COLORS: Record<string, string> = {
  active: '#6366f1',
  completed: '#22c55e',
  paused: '#eab308',
  cancelled: '#ef4444',
  failed: '#ef4444',
};

const STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  completed: '已完成',
  paused: '已暂停',
  cancelled: '已取消',
  failed: '已失败',
};

export const TaskList: React.FC<TaskListProps> = ({ workspacePath, onSelectTask }) => {
  const { isDark } = useTheme();
  const [tasks, setTasks] = useState<TaskMetadata[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const loadTasks = useCallback(async () => {
    try {
      const basePath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}/.tasks`;
      const loadedTasks: TaskMetadata[] = [];

      const loadFromDir = async (dir: 'active' | 'archive') => {
        const dirPath = `${basePath}/${dir}`;
        try {
          const info = await FileSystem.getInfoAsync(dirPath);
          if (!info.exists) return;

          const files = await FileSystem.readDirectoryAsync(dirPath);
          for (const file of files) {
            if (!file.endsWith('.md')) continue;

            const taskId = file.replace('.md', '');
            const filePath = `${dirPath}/${file}`;
            const content = await FileSystem.readAsStringAsync(filePath);

            const titleMatch = content.match(/# Task: (.+)/);
            const statusMatch = content.match(/\*\*Status\*\*:\s*(\w+)/);
            const progressMatch = content.match(/\*\*Progress\*\*:\s*(\d+)/);
            const updatedMatch = content.match(/\*\*Updated\*\*:\s*(.+)/);

            loadedTasks.push({
              id: taskId,
              title: titleMatch?.[1] || '未命名任务',
              status: (statusMatch?.[1] as TaskMetadata['status']) || 'active',
              progress: parseInt(progressMatch?.[1] || '0', 10),
              updatedAt: updatedMatch ? new Date(updatedMatch[1]).getTime() : Date.now(),
              path: `.tasks/${dir}/${file}`,
            });
          }
        } catch {}
      };

      await loadFromDir('active');
      await loadFromDir('archive');

      loadedTasks.sort((a, b) => b.updatedAt - a.updatedAt);
      setTasks(loadedTasks);
    } catch (e) {
      console.error('[TaskList] Load error:', e);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [workspacePath]);

  useEffect(() => {
    loadTasks();
  }, [loadTasks]);

  const handleRefresh = () => {
    setRefreshing(true);
    loadTasks();
  };

  const formatDate = (timestamp: number): string => {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return '刚刚';
    if (diffMins < 60) return `${diffMins} 分钟前`;
    if (diffHours < 24) return `${diffHours} 小时前`;
    if (diffDays < 7) return `${diffDays} 天前`;
    return date.toLocaleDateString('zh-CN');
  };

  const renderTask = ({ item }: { item: TaskMetadata }) => {
    const StatusIcon = STATUS_ICONS[item.status] || Clock;
    const statusColor = STATUS_COLORS[item.status] || '#6366f1';
    const statusLabel = STATUS_LABELS[item.status] || '进行中';

    return (
      <TouchableOpacity
        style={[
          styles.taskItem,
          {
            backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fafafa',
            borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
          },
        ]}
        onPress={() => onSelectTask(item.id, item.path)}
      >
        <View style={styles.taskHeader}>
          <View style={[styles.statusIcon, { backgroundColor: `${statusColor}12` }]}>
            <StatusIcon size={14} color={statusColor} />
          </View>
          <View style={styles.taskTitleContainer}>
            <Typography style={{ flex: 1, fontSize: 14, fontWeight: '500', color: isDark ? '#fff' : '#111' }} numberOfLines={1}>
              {item.title}
            </Typography>
            <View style={[styles.statusBadge, { backgroundColor: `${statusColor}12` }]}>
              <Typography style={{ fontSize: 10, color: statusColor, fontWeight: '500' }}>
                {statusLabel}
              </Typography>
            </View>
          </View>
          <ChevronRight size={16} color={isDark ? '#3f3f46' : '#d1d5db'} />
        </View>

        <View style={styles.taskMeta}>
          <View style={styles.progressContainer}>
            <View style={[styles.progressBar, { backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : '#e5e7eb' }]}>
              <View style={[styles.progressFill, { width: `${item.progress}%`, backgroundColor: statusColor }]} />
            </View>
            <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginLeft: 10, fontWeight: '500' }}>
              {item.progress}%
            </Typography>
          </View>
          <Typography style={{ fontSize: 11, color: isDark ? '#52525b' : '#9ca3af' }}>
            {formatDate(item.updatedAt)}
          </Typography>
        </View>
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

  if (tasks.length === 0) {
    return (
      <View style={styles.emptyContainer}>
        <View style={[styles.emptyIcon, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f3f4f6' }]}>
          <FileText size={32} color={isDark ? '#3f3f46' : '#d1d5db'} />
        </View>
        <Typography style={{ color: isDark ? '#71717a' : '#6b7280', marginTop: 16, fontSize: 14, textAlign: 'center' }}>
          暂无任务
        </Typography>
        <Typography style={{ color: isDark ? '#52525b' : '#9ca3af', marginTop: 6, fontSize: 12, textAlign: 'center' }}>
          使用 manage_task 工具创建任务
        </Typography>
      </View>
    );
  }

  return (
    <FlatList
      data={tasks}
      keyExtractor={(item) => item.id}
      renderItem={renderTask}
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
  taskItem: {
    padding: Spacing[4],
    borderRadius: 16,
    marginBottom: Spacing[3],
    borderWidth: 1,
  },
  taskHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  statusIcon: {
    width: 28,
    height: 28,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  taskTitleContainer: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: Spacing[3],
    gap: 8,
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
  },
  taskMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: Spacing[3],
    marginLeft: 40,
  },
  progressContainer: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  progressBar: {
    flex: 1,
    height: 4,
    borderRadius: 2,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 2,
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

import React, { useState, useEffect, useCallback } from 'react';
import { View, TouchableOpacity, StyleSheet, ScrollView, RefreshControl } from 'react-native';
import { FileText, Folder, ChevronRight, File } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import * as FileSystem from 'expo-file-system/legacy';

interface FileItem {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
  modifiedTime?: number;
}

interface FileBrowserProps {
  workspacePath: string;
  onSelectFile: (path: string) => void;
}

export const FileBrowser: React.FC<FileBrowserProps> = ({ workspacePath, onSelectFile }) => {
  const { isDark } = useTheme();
  const [currentPath, setCurrentPath] = useState('');
  const [files, setFiles] = useState<FileItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const loadFiles = useCallback(async (path: string = '') => {
    try {
      const basePath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}`;
      const targetPath = path ? `${basePath}/${path}` : basePath;

      const info = await FileSystem.getInfoAsync(targetPath);
      if (!info.exists || !(info as any).isDirectory) {
        setFiles([]);
        return;
      }

      const entries = await FileSystem.readDirectoryAsync(targetPath);
      const items: FileItem[] = [];

      for (const entry of entries) {
        if (entry.startsWith('.')) continue;

        const entryPath = `${targetPath}/${entry}`;
        const entryInfo = await FileSystem.getInfoAsync(entryPath);

        items.push({
          name: entry,
          path: path ? `${path}/${entry}` : entry,
          isDirectory: (entryInfo as any).isDirectory || false,
          size: (entryInfo as any).size,
          modifiedTime: (entryInfo as any).modificationTime,
        });
      }

      items.sort((a, b) => {
        if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
        return a.name.localeCompare(b.name);
      });

      setFiles(items);
    } catch (e) {
      console.error('[FileBrowser] Load error:', e);
      setFiles([]);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [workspacePath]);

  useEffect(() => {
    setCurrentPath('');
    loadFiles('');
  }, [loadFiles]);

  const handleRefresh = () => {
    setRefreshing(true);
    loadFiles(currentPath);
  };

  const handleNavigate = (item: FileItem) => {
    if (item.isDirectory) {
      setCurrentPath(item.path);
      loadFiles(item.path);
    } else {
      onSelectFile(item.path);
    }
  };

  const handleGoUp = () => {
    const parts = currentPath.split('/');
    parts.pop();
    const parentPath = parts.join('/');
    setCurrentPath(parentPath);
    loadFiles(parentPath);
  };

  const formatSize = (bytes?: number): string => {
    if (!bytes) return '-';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const pathParts = currentPath.split('/').filter(Boolean);

  if (loading) {
    return (
      <View style={styles.emptyContainer}>
        <Typography style={{ color: isDark ? '#71717a' : '#6b7280' }}>加载中...</Typography>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={[styles.breadcrumb, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f9fafb' }]}>
        <TouchableOpacity onPress={() => { setCurrentPath(''); loadFiles(''); }}>
          <Typography style={{ color: '#6366f1', fontSize: 12 }}>根目录</Typography>
        </TouchableOpacity>
        {pathParts.map((part, index) => (
          <View key={index} style={{ flexDirection: 'row', alignItems: 'center' }}>
            <Typography style={{ color: isDark ? '#52525b' : '#9ca3af', marginHorizontal: 4 }}>/</Typography>
            <TouchableOpacity
              onPress={() => {
                const newPath = pathParts.slice(0, index + 1).join('/');
                setCurrentPath(newPath);
                loadFiles(newPath);
              }}
            >
              <Typography style={{ color: '#6366f1', fontSize: 12 }} numberOfLines={1}>
                {part}
              </Typography>
            </TouchableOpacity>
          </View>
        ))}
      </View>

      <ScrollView
        style={styles.list}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
      >
        {currentPath && (
          <TouchableOpacity style={styles.fileItem} onPress={handleGoUp}>
            <Folder size={18} color={isDark ? '#a1a1aa' : '#6b7280'} />
            <Typography style={{ marginLeft: 10, fontSize: 14, color: isDark ? '#a1a1aa' : '#6b7280' }}>
              ..
            </Typography>
          </TouchableOpacity>
        )}

        {files.length === 0 && !currentPath && (
          <View style={styles.emptyContainer}>
            <FileText size={48} color={isDark ? '#27272a' : '#e5e7eb'} />
            <Typography style={{ color: isDark ? '#71717a' : '#6b7280', marginTop: 12, textAlign: 'center' }}>
              工作区为空
            </Typography>
          </View>
        )}

        {files.map((item) => (
          <TouchableOpacity
            key={item.path}
            style={[styles.fileItem, { backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : 'transparent' }]}
            onPress={() => handleNavigate(item)}
          >
            {item.isDirectory ? (
              <Folder size={18} color="#f59e0b" />
            ) : (
              <File size={18} color={isDark ? '#a1a1aa' : '#6b7280'} />
            )}
            <View style={styles.fileInfo}>
              <Typography style={{ fontSize: 14, color: isDark ? '#fff' : '#111' }} numberOfLines={1}>
                {item.name}
              </Typography>
              {!item.isDirectory && (
                <Typography style={{ fontSize: 11, color: isDark ? '#52525b' : '#9ca3af' }}>
                  {formatSize(item.size)}
                </Typography>
              )}
            </View>
            <ChevronRight size={16} color={isDark ? '#52525b' : '#9ca3af'} />
          </TouchableOpacity>
        ))}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  breadcrumb: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[3],
    paddingVertical: Spacing[2],
    marginHorizontal: Spacing[3],
    marginTop: Spacing[2],
    borderRadius: 8,
  },
  list: {
    flex: 1,
    padding: Spacing[3],
  },
  fileItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: Spacing[3],
    paddingHorizontal: Spacing[2],
    borderRadius: 8,
    marginBottom: 2,
  },
  fileInfo: {
    flex: 1,
    marginLeft: 10,
  },
  emptyContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    padding: Spacing[6],
  },
});

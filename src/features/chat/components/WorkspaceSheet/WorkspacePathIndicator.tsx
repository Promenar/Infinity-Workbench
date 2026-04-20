import React, { useState, useEffect } from 'react';
import { View, TouchableOpacity, StyleSheet, Modal, FlatList, TextInput, Alert } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { FolderOpen, ChevronDown, Check, Plus, X, Info } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { useChatStore } from '../../../../store/chat-store';
import { Spacing } from '../../../../theme/glass';
import { impactAsync, ImpactFeedbackStyle } from 'expo-haptics';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system/legacy';

const CUSTOM_WORKSPACES_KEY = 'nexara_custom_workspaces';

interface WorkspacePathIndicatorProps {
  sessionId: string;
  onWorkspaceChange?: (path: string) => void;
}

const DEFAULT_WORKSPACES = [
  { 
    path: 'workspace', 
    name: '默认工作区',
    description: '未绑定特定项目时使用的默认存储位置'
  },
  { 
    path: 'projects', 
    name: '项目目录',
    description: '用于存放各个独立项目的父目录'
  },
];

/**
 * 确保工作区物理目录存在
 */
const ensureWorkspaceDir = async (workspacePath: string) => {
  try {
    const fullPath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}`;
    const info = await FileSystem.getInfoAsync(fullPath);
    if (!info.exists) {
      await FileSystem.makeDirectoryAsync(fullPath, { intermediates: true });
    }
  } catch (e) {
    console.warn('[WorkspacePathIndicator] Failed to ensure workspace dir:', e);
  }
};

export const WorkspacePathIndicator: React.FC<WorkspacePathIndicatorProps> = ({
  sessionId,
  onWorkspaceChange,
}) => {
  const { isDark } = useTheme();
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const updateSession = useChatStore((s) => s.updateSession);

  const [modalVisible, setModalVisible] = useState(false);
  const [newPath, setNewPath] = useState('');
  const [customWorkspaces, setCustomWorkspaces] = useState<string[]>([]);

  // ✅ 从 AsyncStorage 加载自定义工作区列表
  useEffect(() => {
    const loadCustomWorkspaces = async () => {
      try {
        const stored = await AsyncStorage.getItem(CUSTOM_WORKSPACES_KEY);
        if (stored) {
          setCustomWorkspaces(JSON.parse(stored));
        }
      } catch (e) {
        console.warn('[WorkspacePathIndicator] Failed to load custom workspaces:', e);
      }
    };
    loadCustomWorkspaces();
  }, []);

  const currentPath = session?.workspacePath || 'workspace';

  const allWorkspaces = [...DEFAULT_WORKSPACES, ...customWorkspaces.map(p => ({
    path: p,
    name: p.split('/').pop() || p,
    description: '自定义工作区'
  }))];

  const handleSelectWorkspace = async (path: string) => {
    impactAsync(ImpactFeedbackStyle.Light);
    await ensureWorkspaceDir(path);
    updateSession(sessionId, { workspacePath: path });
    setModalVisible(false);
    onWorkspaceChange?.(path);
  };

  const handleCreateWorkspace = async () => {
    if (!newPath.trim()) return;
    impactAsync(ImpactFeedbackStyle.Light);
    const sanitized = newPath.trim().replace(/[^a-zA-Z0-9_/-]/g, '_');

    // 确保物理目录存在
    await ensureWorkspaceDir(sanitized);

    // 持久化自定义工作区列表
    const updated = [...customWorkspaces, sanitized];
    setCustomWorkspaces(updated);
    try {
      await AsyncStorage.setItem(CUSTOM_WORKSPACES_KEY, JSON.stringify(updated));
    } catch (e) {
      console.warn('[WorkspacePathIndicator] Failed to persist custom workspaces:', e);
    }

    updateSession(sessionId, { workspacePath: sanitized });
    setNewPath('');
    setModalVisible(false);
    onWorkspaceChange?.(sanitized);
  };

  const getCurrentWorkspaceInfo = () => {
    const found = allWorkspaces.find(w => w.path === currentPath);
    return found || { name: currentPath, description: '自定义工作区' };
  };

  const currentInfo = getCurrentWorkspaceInfo();

  return (
    <View style={styles.container}>
      <TouchableOpacity
        style={[styles.indicator, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f4f4f5' }]}
        onPress={() => setModalVisible(true)}
      >
        <FolderOpen size={14} color={isDark ? '#a1a1aa' : '#6b7280'} />
        <View style={styles.indicatorText}>
          <Typography style={{ fontSize: 12, fontWeight: '500', color: isDark ? '#fff' : '#111' }} numberOfLines={1}>
            {currentInfo.name}
          </Typography>
          <Typography style={{ fontSize: 10, color: isDark ? '#71717a' : '#6b7280' }} numberOfLines={1}>
            {currentPath}
          </Typography>
        </View>
        <ChevronDown size={14} color={isDark ? '#71717a' : '#9ca3af'} />
      </TouchableOpacity>

      <Modal visible={modalVisible} transparent animationType="fade" onRequestClose={() => setModalVisible(false)}>
        <KeyboardAvoidingView behavior="padding" style={styles.modalOverlay}>
        <View style={styles.modalOverlay}>
          <View style={[styles.modalContent, { backgroundColor: isDark ? '#18181b' : '#ffffff' }]}>
            <View style={styles.modalHeader}>
              <Typography style={{ fontSize: 16, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                选择工作区
              </Typography>
              <TouchableOpacity onPress={() => setModalVisible(false)}>
                <X size={20} color={isDark ? '#a1a1aa' : '#6b7280'} />
              </TouchableOpacity>
            </View>

            <View style={[styles.infoBanner, { backgroundColor: isDark ? 'rgba(99, 102, 241, 0.1)' : '#EEF2FF' }]}>
              <Info size={14} color="#6366f1" />
              <Typography style={{ fontSize: 11, color: isDark ? '#a1a1aa' : '#6b7280', marginLeft: 8, flex: 1 }}>
                工作区用于隔离不同项目的任务和工件。每个工作区有独立的任务列表和文件存储。
              </Typography>
            </View>

            <FlatList
              data={allWorkspaces}
              keyExtractor={(item) => item.path}
              style={styles.list}
              renderItem={({ item }) => {
                const isSelected = item.path === currentPath;
                return (
                  <TouchableOpacity
                    style={[styles.listItem, isSelected && { backgroundColor: isDark ? 'rgba(99, 102, 241, 0.1)' : '#EEF2FF' }]}
                    onPress={() => handleSelectWorkspace(item.path)}
                  >
                    <FolderOpen size={18} color={isSelected ? '#6366f1' : isDark ? '#a1a1aa' : '#6b7280'} />
                    <View style={styles.listItemContent}>
                      <Typography style={{ fontWeight: isSelected ? '600' : '500', fontSize: 14, color: isDark ? '#fff' : '#111' }}>
                        {item.name}
                      </Typography>
                      <Typography style={{ fontSize: 11, color: isDark ? '#71717a' : '#6b7280', marginTop: 2 }}>
                        {item.description}
                      </Typography>
                    </View>
                    {isSelected && <Check size={16} color="#6366f1" />}
                  </TouchableOpacity>
                );
              }}
            />

            <View style={[styles.divider, { backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }]} />

            <View style={styles.newWorkspaceSection}>
              <Typography style={{ fontSize: 12, fontWeight: '500', color: isDark ? '#a1a1aa' : '#6b7280', marginBottom: 8 }}>
                创建新工作区
              </Typography>
              <View style={styles.inputRow}>
                <TextInput
                  style={[styles.input, { backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#f3f4f6', color: isDark ? '#fff' : '#111' }]}
                  placeholder="projects/my_project"
                  placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
                  value={newPath}
                  onChangeText={setNewPath}
                />
                <TouchableOpacity style={styles.createButton} onPress={handleCreateWorkspace}>
                  <Plus size={18} color="#fff" />
                </TouchableOpacity>
              </View>
            </View>
          </View>
        </View>
        </KeyboardAvoidingView>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
  },
  indicator: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[3],
    paddingVertical: Spacing[2],
    borderRadius: 12,
  },
  indicatorText: {
    flex: 1,
    marginLeft: 8,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalContent: {
    width: '85%',
    maxWidth: 360,
    borderRadius: 20,
    overflow: 'hidden',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: Spacing[4],
  },
  infoBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: Spacing[4],
    padding: Spacing[3],
    borderRadius: 12,
  },
  list: {
    maxHeight: 280,
  },
  listItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
    marginHorizontal: Spacing[3],
    marginVertical: 2,
    borderRadius: 12,
  },
  listItemContent: {
    flex: 1,
    marginLeft: Spacing[3],
  },
  divider: {
    height: 1,
  },
  newWorkspaceSection: {
    padding: Spacing[4],
  },
  inputRow: {
    flexDirection: 'row',
    gap: 8,
  },
  input: {
    flex: 1,
    height: 40,
    paddingHorizontal: Spacing[3],
    borderRadius: 12,
    fontSize: 14,
  },
  createButton: {
    width: 40,
    height: 40,
    borderRadius: 12,
    backgroundColor: '#6366f1',
    alignItems: 'center',
    justifyContent: 'center',
  },
});

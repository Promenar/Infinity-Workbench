import React, { useState, useCallback } from 'react';
import { View, StyleSheet, Modal, ScrollView, TextInput, TouchableOpacity, Dimensions } from 'react-native';
import { X, Save, FileText } from 'lucide-react-native';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown, Layout } from 'react-native-reanimated';
import { useTheme } from '../../../../theme/ThemeProvider';
import { GlassBottomSheet } from '../../../../components/ui/GlassBottomSheet';
import { Typography } from '../../../../components/ui/Typography';
import { WorkspaceTabBar } from './WorkspaceTabBar';
import { WorkspacePathIndicator } from './WorkspacePathIndicator';
import { TaskList } from './TaskList';
import { ArtifactList } from './ArtifactList';
import { FileBrowser } from './FileBrowser';
import { useChatStore } from '../../../../store/chat-store';
import { Spacing } from '../../../../theme/glass';
import * as FileSystem from 'expo-file-system/legacy';

const { height: SCREEN_HEIGHT } = Dimensions.get('window');

interface WorkspaceSheetProps {
  visible: boolean;
  onClose: () => void;
  sessionId: string;
  onSelectTask?: (taskId: string, path: string) => void;
}

interface PreviewFile {
  path: string;
  content: string;
  name: string;
}

export const WorkspaceSheet: React.FC<WorkspaceSheetProps> = ({
  visible,
  onClose,
  sessionId,
  onSelectTask,
}) => {
  const { isDark, colors } = useTheme();
  const [activeTab, setActiveTab] = useState('tasks');
  const [previewFile, setPreviewFile] = useState<PreviewFile | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editedContent, setEditedContent] = useState('');
  
  const session = useChatStore((s) => s.sessions.find((sk) => sk.id === sessionId));
  const workspacePath = (session as any)?.workspacePath || 'workspace';

  const handleSelectTask = useCallback((taskId: string, path: string) => {
    onSelectTask?.(taskId, path);
  }, [onSelectTask]);

  const handleSelectArtifact = useCallback(async (artifact: any) => {
    try {
      const fullPath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}/${artifact.path}`;
      const content = await FileSystem.readAsStringAsync(fullPath);
      setPreviewFile({ path: artifact.path, content, name: artifact.name });
      setIsEditing(false);
      setEditedContent(content);
    } catch (e) {
      console.error('[WorkspaceSheet] Failed to load artifact:', e);
    }
  }, [workspacePath]);

  const handleSelectFile = useCallback(async (path: string) => {
    try {
      const fullPath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}/${path}`;
      const info = await FileSystem.getInfoAsync(fullPath);
      
      if ((info as any).isDirectory) return;
      
      const content = await FileSystem.readAsStringAsync(fullPath);
      const name = path.split('/').pop() || path;
      setPreviewFile({ path, content, name });
      setIsEditing(false);
      setEditedContent(content);
    } catch (e) {
      console.error('[WorkspaceSheet] Failed to load file:', e);
    }
  }, [workspacePath]);

  const handleSaveFile = useCallback(async () => {
    if (!previewFile) return;
    try {
      const fullPath = `${FileSystem.documentDirectory}agent_sandbox/${workspacePath}/${previewFile.path}`;
      await FileSystem.writeAsStringAsync(fullPath, editedContent);
      setPreviewFile({ ...previewFile, content: editedContent });
      setIsEditing(false);
    } catch (e) {
      console.error('[WorkspaceSheet] Failed to save file:', e);
    }
  }, [previewFile, workspacePath, editedContent]);

  const handleClosePreview = useCallback(() => {
    setPreviewFile(null);
    setIsEditing(false);
  }, []);

  const handleToggleEdit = useCallback(() => {
    if (isEditing) {
      handleSaveFile();
    } else {
      setIsEditing(true);
    }
  }, [isEditing, handleSaveFile]);

  const renderContent = () => {
    switch (activeTab) {
      case 'tasks':
        return <TaskList workspacePath={workspacePath} onSelectTask={handleSelectTask} />;
      case 'artifacts':
        return <ArtifactList workspacePath={workspacePath} onSelectArtifact={handleSelectArtifact} />;
      case 'files':
        return <FileBrowser workspacePath={workspacePath} onSelectFile={handleSelectFile} />;
      default:
        return null;
    }
  };

  return (
    <>
      <GlassBottomSheet visible={visible} onClose={onClose} title="工作区" height="85%">
        <WorkspacePathIndicator sessionId={sessionId} />
        <WorkspaceTabBar activeTab={activeTab} onTabChange={setActiveTab} />
        <View style={styles.content}>
          <Animated.View
            key={activeTab}
            entering={FadeIn.duration(200)}
            exiting={FadeOut.duration(150)}
            style={styles.contentInner}
          >
            {renderContent()}
          </Animated.View>
        </View>
      </GlassBottomSheet>

      {/* 文件预览/编辑模态框 */}
      <Modal 
        visible={!!previewFile} 
        transparent 
        animationType="fade"
        onRequestClose={handleClosePreview}
        statusBarTranslucent
      >
        <View style={styles.modalOverlay}>
          <Animated.View 
            entering={SlideInDown.duration(250)}
            exiting={SlideOutDown.duration(200)}
            style={[styles.previewModal, { backgroundColor: isDark ? '#18181b' : '#ffffff' }]}
          >
            {/* 头部 */}
            <View style={[styles.previewHeader, { borderBottomColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }]}>
              <View style={styles.previewTitleContainer}>
                <View style={[styles.fileIconBg, { backgroundColor: colors.opacity20 }]}>
                  <FileText size={16} color={colors[500]} />
                </View>
                <View style={styles.titleText}>
                  <Typography style={{ fontSize: 14, fontWeight: '600', color: isDark ? '#fff' : '#111' }}>
                    {previewFile?.name}
                  </Typography>
                  <Typography style={{ fontSize: 10, color: isDark ? '#71717a' : '#9ca3af', marginTop: 2 }}>
                    {previewFile?.path}
                  </Typography>
                </View>
              </View>
              <View style={styles.previewActions}>
                <TouchableOpacity 
                  onPress={handleToggleEdit} 
                  style={[styles.actionButton, { backgroundColor: isEditing ? 'rgba(34, 197, 94, 0.15)' : isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)' }]}
                  activeOpacity={0.7}
                >
                  {isEditing ? (
                    <>
                      <Save size={14} color="#22c55e" />
                      <Typography style={{ fontSize: 12, color: '#22c55e', marginLeft: 4, fontWeight: '500' }}>保存</Typography>
                    </>
                  ) : (
                    <Typography style={{ fontSize: 12, color: colors[500], fontWeight: '500' }}>编辑</Typography>
                  )}
                </TouchableOpacity>
                <TouchableOpacity onPress={handleClosePreview} style={styles.closeButton} activeOpacity={0.7}>
                  <X size={18} color={isDark ? '#a1a1aa' : '#6b7280'} />
                </TouchableOpacity>
              </View>
            </View>
            
            {/* 内容区 - 使用 Layout 动画实现平滑切换 */}
            <Animated.View layout={Layout.duration(150)} style={styles.contentArea}>
              {isEditing ? (
                <Animated.View 
                  key="editor"
                  entering={FadeIn.duration(150)}
                  exiting={FadeOut.duration(100)}
                  style={styles.editorContainer}
                >
                  <TextInput
                    style={[styles.editor, { backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : '#fafafa', color: isDark ? '#e4e4e7' : '#27272a' }]}
                    value={editedContent}
                    onChangeText={setEditedContent}
                    multiline
                    autoCapitalize="none"
                    autoCorrect={false}
                    placeholder="输入内容..."
                    placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                  />
                </Animated.View>
              ) : (
                <Animated.View 
                  key="preview"
                  entering={FadeIn.duration(150)}
                  exiting={FadeOut.duration(100)}
                  style={styles.previewContainer}
                >
                  <ScrollView 
                    style={styles.previewContent} 
                    contentContainerStyle={styles.previewContentContainer}
                    showsVerticalScrollIndicator={false}
                  >
                    <Typography style={[styles.previewText, { color: isDark ? '#d4d4d8' : '#3f3f46' }]}>
                      {previewFile?.content || '(空文件)'}
                    </Typography>
                  </ScrollView>
                </Animated.View>
              )}
            </Animated.View>
          </Animated.View>
        </View>
      </Modal>
    </>
  );
};

const styles = StyleSheet.create({
  content: {
    flex: 1,
    overflow: 'hidden',
  },
  contentInner: {
    flex: 1,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
    justifyContent: 'flex-end',
  },
  previewModal: {
    height: SCREEN_HEIGHT * 0.75,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    overflow: 'hidden',
  },
  previewHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing[4],
    paddingVertical: Spacing[3],
    borderBottomWidth: 1,
  },
  previewTitleContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  fileIconBg: {
    width: 32,
    height: 32,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  titleText: {
    marginLeft: 10,
    flex: 1,
  },
  previewActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  actionButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 10,
  },
  closeButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  contentArea: {
    flex: 1,
  },
  editorContainer: {
    flex: 1,
  },
  previewContainer: {
    flex: 1,
  },
  previewContent: {
    flex: 1,
  },
  previewContentContainer: {
    padding: Spacing[4],
  },
  previewText: {
    fontSize: 13,
    fontFamily: 'monospace',
    lineHeight: 20,
  },
  editor: {
    flex: 1,
    padding: Spacing[4],
    fontSize: 13,
    fontFamily: 'monospace',
    lineHeight: 20,
    textAlignVertical: 'top',
  },
});

export { WorkspaceTabBar } from './WorkspaceTabBar';
export { WorkspacePathIndicator } from './WorkspacePathIndicator';
export { TaskList } from './TaskList';
export { ArtifactList } from './ArtifactList';
export { FileBrowser } from './FileBrowser';

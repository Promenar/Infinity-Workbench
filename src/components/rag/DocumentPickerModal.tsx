import React, { useState, useMemo, useEffect } from 'react';
import { View, TouchableOpacity, Modal, BackHandler, ScrollView } from 'react-native';
import { Typography, GlassHeader, PageLayout } from '../ui';
import { X, Check, ChevronRight, Folder, FileText } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RagDocument, RagFolder } from '../../types/rag';
import * as Haptics from 'expo-haptics';

interface DocumentPickerModalProps {
  visible: boolean;
  onClose: () => void;
  folders: RagFolder[];
  documents: RagDocument[];
  selectedDocIds: string[];
  selectedFolderIds?: string[];
  onConfirm: (docIds: string[], folderIds: string[]) => void;
}

export const DocumentPickerModal: React.FC<DocumentPickerModalProps> = ({
  visible,
  onClose,
  folders,
  documents,
  selectedDocIds,
  selectedFolderIds = [],
  onConfirm,
}) => {
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const [currentFolderId, setCurrentFolderId] = useState<string | undefined>(undefined);
  const [tempSelectedDocIds, setTempSelectedDocIds] = useState<Set<string>>(
    new Set(selectedDocIds),
  );
  const [tempSelectedFolderIds, setTempSelectedFolderIds] = useState<Set<string>>(
    new Set(selectedFolderIds),
  );

  // Calculate header height (GlassHeader default height is 64)
  const headerHeight = 64 + insets.top;

  // 面包屑导航
  const breadcrumbs = useMemo(() => {
    const crumbs: Array<{ id: string | undefined; name: string }> = [
      { id: undefined, name: '全部' },
    ];

    if (currentFolderId) {
      let folder = folders.find((f) => f.id === currentFolderId);
      const stack: RagFolder[] = [];

      while (folder) {
        stack.unshift(folder);
        folder = folders.find((f) => f.id === folder!.parentId);
      }

      crumbs.push(...stack.map((f) => ({ id: f.id, name: f.name })));
    }

    return crumbs;
  }, [currentFolderId, folders]);

  // 当前文件夹的子文件夹和文档
  const currentItems = useMemo(() => {
    const childFolders = folders.filter((f) => f.parentId === currentFolderId);
    const childDocs = documents.filter((d) => d.folderId === currentFolderId);

    return { folders: childFolders, documents: childDocs };
  }, [currentFolderId, folders, documents]);

  const handleToggleDoc = (docId: string) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      const newSet = new Set(tempSelectedDocIds);
      if (newSet.has(docId)) {
        newSet.delete(docId);
      } else {
        newSet.add(docId);
      }
      setTempSelectedDocIds(newSet);
    }, 10);
  };

  const handleToggleFolder = (folderId: string) => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      const newSet = new Set(tempSelectedFolderIds);
      if (newSet.has(folderId)) {
        newSet.delete(folderId);
      } else {
        newSet.add(folderId);
      }
      setTempSelectedFolderIds(newSet);
    }, 10);
  };

  const handleConfirm = () => {
    setTimeout(() => {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      onConfirm(Array.from(tempSelectedDocIds), Array.from(tempSelectedFolderIds));
      onClose();
    }, 10);
  };

  const handleClose = () => {
    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      setTempSelectedDocIds(new Set(selectedDocIds));
      setTempSelectedFolderIds(new Set(selectedFolderIds));
      setCurrentFolderId(undefined);
      onClose();
    }, 10);
  };

  // Android返回键支持
  const handleBack = () => {
    if (currentFolderId) {
      // 如果在子文件夹中，返回上一级
      const currentFolder = folders.find((f) => f.id === currentFolderId);
      setCurrentFolderId(currentFolder?.parentId);
      return true;
    } else {
      // 如果在根目录，关闭Modal
      handleClose();
      return true;
    }
  };

  useEffect(() => {
    if (!visible) return;

    const backHandler = BackHandler.addEventListener('hardwareBackPress', handleBack);

    return () => backHandler.remove();
  }, [visible, currentFolderId, folders]);

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={handleBack}
    >
      <PageLayout safeArea={false} className="bg-white dark:bg-black">
        {/* GlassHeader */}
        <GlassHeader
          title="选择文档"
          leftAction={{
            icon: <X size={24} color={isDark ? '#fff' : '#111'} strokeWidth={2.5} />,
            onPress: handleClose,
          }}
          rightAction={{
            icon: <Check size={24} color="#6366f1" strokeWidth={2.5} />,
            onPress: handleConfirm,
            label: `${tempSelectedDocIds.size + tempSelectedFolderIds.size}`,
          }}
        />

        {/* 面包屑 */}
        <View style={{ marginTop: headerHeight }} className="px-6 py-3 flex-row items-center">
          {breadcrumbs.map((crumb, index) => (
            <View key={crumb.id || 'root'} className="flex-row items-center">
              <TouchableOpacity
                onPress={() => setCurrentFolderId(crumb.id)}
                className="py-1 px-2 rounded-lg active:bg-gray-100 dark:active:bg-zinc-800"
              >
                <Typography
                  className={`text-sm font-semibold ${
                    index === breadcrumbs.length - 1
                      ? 'text-gray-900 dark:text-white'
                      : 'text-gray-400'
                  }`}
                >
                  {crumb.name}
                </Typography>
              </TouchableOpacity>
              {index < breadcrumbs.length - 1 && (
                <ChevronRight size={14} color="#94a3b8" className="mx-1" />
              )}
            </View>
          ))}
        </View>

        {/* 列表 */}
        <ScrollView
          className="flex-1 px-6"
          contentContainerStyle={{ paddingTop: 8, paddingBottom: 20 }}
        >
          {/* 文件夹 */}
          {currentItems.folders.map((folder) => {
            const isSelected = tempSelectedFolderIds.has(folder.id);

            return (
              <TouchableOpacity
                key={folder.id}
                onPress={() => setCurrentFolderId(folder.id)}
                onLongPress={() => handleToggleFolder(folder.id)}
                className={`mb-2 rounded-xl p-4 flex-row items-center border ${
                  isSelected
                    ? 'bg-amber-50 dark:bg-amber-900/20 border-amber-500'
                    : 'bg-gray-50 dark:bg-zinc-900/50 border-gray-100 dark:border-zinc-800'
                }`}
              >
                <View className="w-10 h-10 rounded-lg bg-amber-50 dark:bg-amber-900/20 items-center justify-center mr-3">
                  <Folder size={20} color="#f59e0b" strokeWidth={2} />
                </View>

                <View className="flex-1">
                  <Typography className="font-bold text-gray-900 dark:text-white">
                    {folder.name}
                  </Typography>
                  <Typography className="text-xs text-gray-400 mt-1">
                    {folder.childCount} 个项目
                  </Typography>
                </View>

                {isSelected ? (
                  <View className="w-6 h-6 rounded-full bg-amber-500 items-center justify-center mr-2">
                    <Check size={16} color="#fff" strokeWidth={3} />
                  </View>
                ) : (
                  <ChevronRight size={20} color="#94a3b8" />
                )}
              </TouchableOpacity>
            );
          })}

          {/* 文档 */}
          {currentItems.documents.map((doc) => {
            const isSelected = tempSelectedDocIds.has(doc.id);

            return (
              <TouchableOpacity
                key={doc.id}
                onPress={() => handleToggleDoc(doc.id)}
                className={`mb-2 rounded-xl p-4 flex-row items-center border ${
                  isSelected
                    ? 'bg-indigo-50 dark:bg-indigo-900/20 border-indigo-500'
                    : 'bg-gray-50 dark:bg-zinc-900/50 border-gray-100 dark:border-zinc-800'
                }`}
              >
                <View
                  className={`w-10 h-10 rounded-lg items-center justify-center mr-3 ${
                    isSelected ? 'bg-indigo-100 dark:bg-indigo-900/40' : 'bg-white dark:bg-zinc-800'
                  }`}
                >
                  <FileText size={20} color="#6366f1" strokeWidth={2} />
                </View>

                <View className="flex-1">
                  <Typography className="font-bold text-gray-900 dark:text-white" numberOfLines={1}>
                    {doc.title}
                  </Typography>
                  <Typography className="text-xs text-gray-400 mt-1">
                    {doc.vectorCount} chunks · {(doc.fileSize / 1024).toFixed(1)}KB
                  </Typography>
                </View>

                {isSelected && (
                  <View className="w-6 h-6 rounded-full bg-indigo-500 items-center justify-center">
                    <Check size={16} color="#fff" strokeWidth={3} />
                  </View>
                )}
              </TouchableOpacity>
            );
          })}

          {currentItems.folders.length === 0 && currentItems.documents.length === 0 && (
            <View className="py-20 items-center">
              <Typography className="text-gray-400 text-sm">此文件夹为空</Typography>
            </View>
          )}
        </ScrollView>
      </PageLayout>
    </Modal>
  );
};

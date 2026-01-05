import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { View, TouchableOpacity, Text, Modal, TextInput, ActivityIndicator } from 'react-native';
import { PageLayout, Typography, useToast, ConfirmDialog } from '../../src/components/ui';
import { Search, X, FolderInput, Folder } from 'lucide-react-native';
import { Stack, useRouter, useNavigation } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import * as Haptics from 'expo-haptics';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system';
import { useI18n } from '../../src/lib/i18n';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useRagStore } from '../../src/store/rag-store';
import { useTheme } from '../../src/theme/ThemeProvider';
import { ControlBar } from '../../src/components/rag/ControlBar';
import { Breadcrumbs } from '../../src/components/rag/Breadcrumbs';
import { FolderItem } from '../../src/components/rag/FolderItem';
import { CompactDocItem } from '../../src/components/rag/CompactDocItem';
import { ScrollView } from 'react-native-gesture-handler';
import { DragDropContentView } from 'expo-drag-drop-content-view';
import { PdfExtractor, PdfExtractorRef } from '../../src/components/rag/PdfExtractor';
import { RagStatusIndicator } from '../../src/components/rag/RagStatusIndicator';

export default function RagScreen() {
  const router = useRouter();
  const navigation = useNavigation();
  const { showToast } = useToast();
  const { isDark } = useTheme();
  const insets = useSafeAreaInsets();
  const { t } = useI18n();
  const pdfExtractorRef = React.useRef<PdfExtractorRef>(null);

  // Dynamic imports for modals and components
  const [PdfExtractorComponent, setPdfExtractorComponent] = useState<any>(null);
  const [TagCapsuleComponent, setTagCapsuleComponent] = useState<any>(null);
  const [TagManagerSheetComponent, setTagManagerSheetComponent] = useState<any>(null);
  const [TagAssignmentSheetComponent, setTagAssignmentSheetComponent] = useState<any>(null);
  const [ImagePreviewModalComponent, setImagePreviewModalComponent] = useState<any>(null);

  useEffect(() => {
    const loadComponents = async () => {
      const { PdfExtractor } = await import('../../src/components/rag/PdfExtractor');
      const { TagCapsule } = await import('../../src/components/rag/TagCapsule');
      const { TagManagerSheet } = await import('../../src/components/rag/TagManagerSheet');
      const { TagAssignmentSheet } = await import('../../src/components/rag/TagAssignmentSheet');
      const { ImagePreviewModal } = await import('../../src/components/rag/ImagePreviewModal');
      const { documentProcessor } = await import('../../src/lib/rag/document-processor'); // Import processor
      setPdfExtractorComponent(() => PdfExtractor);
      setTagCapsuleComponent(() => TagCapsule);
      setTagManagerSheetComponent(() => TagManagerSheet);
      setTagAssignmentSheetComponent(() => TagAssignmentSheet);
      setImagePreviewModalComponent(() => ImagePreviewModal);

      // Initialize processor with ref is done in effect below or render?
      // Better to do it in a separate effect when ref changes or component mounts.
    };
    loadComponents();
  }, []);

  // Sync PDF Ref
  useEffect(() => {
    if (pdfExtractorRef.current) {
      // Need to dynamic import here or ensure it's loaded? 
      // We can just import at top level if not lazy, but we want lazy.
      // Let's use the same async import pattern inside the handler to be safe,
      // or just import globally if document-processor is small.
      // For now, let's keep it lazy inside handlers or use a layout effect.
      import('../../src/lib/rag/document-processor').then(({ documentProcessor }) => {
        documentProcessor.setPdfExtractor(pdfExtractorRef.current);
      });
    }
  }, [pdfExtractorRef.current]);

  // RagStore
  const {
    documents,
    folders,
    loadDocuments,
    loadFolders,
    addDocument,
    deleteDocument,
    vectorizeDocument,
    vectorizationQueue,
    currentTask,
    addFolder,
    deleteFolder,
    renameFolder,
    moveDocument,
    expandedFolders,
    toggleFolder,
    moveFolder,
    setSelectedFolder,
    selectedFolder,
  } = useRagStore();

  // 当前路径逻辑
  const currentFolderId = selectedFolder;

  // 导航处理
  const handleNavigate = useCallback(
    (folderId: string | null) => {
      setSearchQuery(''); // Clear search on nav
      setSelectedFolder(folderId);
    },
    [setSelectedFolder],
  );

  // 搜索状态
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchFocused, setIsSearchFocused] = useState(false);

  // 搜索过滤
  const filteredDocuments = useMemo(() => {
    if (!searchQuery.trim()) return documents;
    const query = searchQuery.toLowerCase();
    return documents.filter((doc) => doc.title.toLowerCase().includes(query));
  }, [documents, searchQuery]);

  // 获取当前视图内容
  const currentViewContent = useMemo(() => {
    if (searchQuery) return { folders: [], docs: filteredDocuments };

    return {
      folders: folders.filter(
        (f) =>
          f.parentId === (currentFolderId || undefined) ||
          (currentFolderId === null && !f.parentId),
      ),
      docs: documents.filter(
        (d) =>
          d.folderId === (currentFolderId || undefined) ||
          (currentFolderId === null && !d.folderId),
      ),
    };
  }, [folders, documents, currentFolderId, searchQuery, filteredDocuments]);

  // 文件夹Modal状态
  const [showFolderModal, setShowFolderModal] = useState(false);
  const [folderModalMode, setFolderModalMode] = useState<'create' | 'rename'>('create');
  const [editingFolderId, setEditingFolderId] = useState<string | null>(null);
  const [newFolderName, setNewFolderName] = useState('');

  // 移动操作状态
  const [showMoveModal, setShowMoveModal] = useState(false);
  const [movingDocId, setMovingDocId] = useState<string | null>(null);
  const [movingFolderId, setMovingFolderId] = useState<string | null>(null);

  // Tag & Image Preview State
  const [showTagManager, setShowTagManager] = useState(false);
  const [assignmentDocId, setAssignmentDocId] = useState<string | null>(null);
  const [previewImage, setPreviewImage] = useState<string | null>(null);

  // 确认弹窗状态
  const [confirmState, setConfirmState] = useState<{
    visible: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
    isDestructive?: boolean;
  }>({
    visible: false,
    title: '',
    message: '',
    onConfirm: () => { },
    isDestructive: false,
  });

  // 多选模式状态
  const [isSelectionMode, setIsSelectionMode] = useState(false);
  const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());

  // 切换文档选择
  const handleToggleDocSelection = useCallback((docId: string) => {
    setSelectedDocIds((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(docId)) {
        newSet.delete(docId);
      } else {
        newSet.add(docId);
      }
      return newSet;
    });
  }, []);

  // 退出多选模式
  const exitSelectionMode = useCallback(() => {
    setIsSelectionMode(false);
    setSelectedDocIds(new Set());
  }, []);

  // 批量删除
  const handleBatchDelete = useCallback(() => {
    const count = selectedDocIds.size;
    if (count === 0) return;

    setConfirmState({
      visible: true,
      title: '批量删除',
      message: `确定要删除选中的 ${count} 个文档吗? 此操作不可撤销。`,
      isDestructive: true,
      onConfirm: async () => {
        try {
          const { deleteBatch } = useRagStore.getState();
          await deleteBatch(Array.from(selectedDocIds));
          showToast(`已删除 ${count} 个文档`, 'success');
          exitSelectionMode();
          setConfirmState((prev) => ({ ...prev, visible: false }));
        } catch (e) {
          showToast('批量删除失败', 'error');
        }
      },
    });
  }, [selectedDocIds, showToast, exitSelectionMode]);

  // 批量重新向量化
  const handleBatchVectorize = useCallback(async () => {
    const count = selectedDocIds.size;
    if (count === 0) return;

    try {
      const { vectorizeBatch } = useRagStore.getState();
      await vectorizeBatch(Array.from(selectedDocIds));
      showToast(`已将 ${count} 个文档加入向量化队列`, 'success');
      exitSelectionMode();
    } catch (e) {
      showToast('批量操作失败', 'error');
    }
  }, [selectedDocIds, showToast, exitSelectionMode]);

  useEffect(() => {
    loadDocuments();
    loadFolders();
  }, []);

  // 动态控制 TabBar 显示/隐藏
  useEffect(() => {
    navigation.setOptions({
      tabBarStyle: {
        display: isSelectionMode ? 'none' : 'flex',
        // 复用 _layout.tsx 中的样式定义
        backgroundColor: isDark ? '#000000' : '#FFFFFF',
        borderTopColor: isDark ? '#1e1e1e' : '#f1f1f1',
        elevation: 0,
        shadowOpacity: 0,
        borderTopWidth: 0,
        position: 'absolute',
        bottom: 0,
        height: 65,
        paddingBottom: 12,
        paddingTop: 4,
      },
    });
  }, [isSelectionMode, navigation, isDark]);

  // 文件导入 (Document Picker)
  const handleFileImport = useCallback(async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: [
          'text/plain',
          'text/markdown',
          'application/json',
          'application/pdf',
          'image/*',
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document', // docx
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', // xlsx
          'application/vnd.ms-excel' // xls
        ],
        copyToCacheDirectory: true,
        multiple: true, // 启用多选
      });

      if (result.canceled || !result.assets) return;

      showToast(`准备导入 ${result.assets.length} 个文件...`, 'info');

      const { processBatchWithProgress } = await import('../../src/lib/queue-utils');
      const { readFileContent, readFileAsBase64 } = await import('../../src/lib/file-utils');
      const { imageDescriptionService } = await import('../../src/lib/rag/image-service');

      // Ensure images directory exists
      const docDir = (FileSystem as any).documentDirectory || '';
      const imagesDir = docDir + 'rag_images/';
      await FileSystem.makeDirectoryAsync(imagesDir, { intermediates: true }).catch(() => { });

      const { documentProcessor } = await import('../../src/lib/rag/document-processor');
      // Ensure ref is set just in case
      if (pdfExtractorRef.current) documentProcessor.setPdfExtractor(pdfExtractorRef.current);

      const processor = async (file: any) => {
        const fileName = file.name;
        const mimeType = file.mimeType;
        let thumbnailPath: string | undefined;

        // Process file via centralized processor
        const processResult = await documentProcessor.processFile(file.uri, fileName, mimeType);

        let content = processResult.content;
        const type = processResult.type as any; // 'text' | 'image' matched to store

        // Special handling for image thumbnails (still needed locally for UI)
        if (type === 'image') {
          // Save image locally for thumbnail
          const ext = fileName.split('.').pop();
          const localPath = imagesDir + `${Date.now()}_${Math.random().toString(36).slice(2)}.${ext}`;
          await FileSystem.copyAsync({ from: file.uri, to: localPath });
          thumbnailPath = localPath;
        }

        if (!content.trim()) {
          throw new Error(`Empty content: ${fileName}`);
        }

        await addDocument(
          fileName,
          content,
          content.length,
          type,
          currentFolderId ?? undefined,
          thumbnailPath,
        );
      };

      const resultStats = await processBatchWithProgress(
        result.assets,
        processor,
        undefined,
        1, // Batch size 1
      );

      if (resultStats.failed > 0) {
        showToast(`导入完成: ${resultStats.success} 成功, ${resultStats.failed} 失败`, 'warning');
      } else {
        showToast(`成功导入 ${resultStats.success} 个文件`, 'success');
      }
    } catch (e) {
      console.error(e);
      showToast('导入失败: ' + (e as Error).message, 'error');
    }
  }, [addDocument, showToast, currentFolderId]);

  // 删除文档
  const handleDeleteDocument = useCallback(
    (id: string, title: string) => {
      setConfirmState({
        visible: true,
        title: '删除文档',
        message: `确定删除 "${title}"? 此操作不可撤销。`,
        isDestructive: true,
        onConfirm: async () => {
          await deleteDocument(id);
          showToast('已删除', 'success');
          setConfirmState((prev) => ({ ...prev, visible: false }));
        },
      });
    },
    [deleteDocument, showToast],
  );

  // 文件夹操作
  const handleNewFolder = useCallback(() => {
    setFolderModalMode('create');
    setEditingFolderId(null);
    setNewFolderName('');
    setShowFolderModal(true);
  }, []);

  const handleRenameFolder = useCallback((id: string, name: string) => {
    setFolderModalMode('rename');
    setEditingFolderId(id);
    setNewFolderName(name);
    setShowFolderModal(true);
  }, []);

  const handleFolderSubmit = useCallback(async () => {
    if (!newFolderName.trim()) {
      showToast('请输入名称', 'error');
      return;
    }

    try {
      if (folderModalMode === 'create') {
        const parentId = currentFolderId ?? undefined;
        await addFolder(newFolderName.trim(), parentId);
        showToast('文件夹已创建', 'success');
      } else if (editingFolderId) {
        await renameFolder(editingFolderId, newFolderName.trim());
        showToast('已重命名', 'success');
      }
      setShowFolderModal(false);
      setNewFolderName('');
      setEditingFolderId(null);
    } catch (e) {
      showToast('操作失败: ' + (e as Error).message, 'error');
    }
  }, [newFolderName, folderModalMode, editingFolderId, addFolder, renameFolder, showToast, currentFolderId]);

  const handleDeleteFolder = useCallback(
    (id: string, name: string) => {
      setConfirmState({
        visible: true,
        title: '删除文件夹',
        message: `确定删除 "${name}"? 文档将移至根目录，子文件夹将被级联删除。`,
        isDestructive: true,
        onConfirm: async () => {
          await deleteFolder(id);
          showToast('已删除', 'success');
          setConfirmState((prev) => ({ ...prev, visible: false }));
        },
      });
    },
    [deleteFolder, showToast],
  );

  // 移动操作
  const handleStartMoveDoc = useCallback((docId: string) => {
    setMovingDocId(docId);
    setMovingFolderId(null);
    setShowMoveModal(true);
  }, []);

  const handleStartMoveFolder = useCallback((folderId: string) => {
    setMovingFolderId(folderId);
    setMovingDocId(null);
    setShowMoveModal(true);
  }, []);

  const handleConfirmMove = useCallback(
    async (targetFolderId: string | null) => {
      try {
        if (movingDocId) {
          await moveDocument(movingDocId, targetFolderId);
          showToast('文档已移动', 'success');
        } else if (movingFolderId) {
          // Prevent moving folder into itself or its children (simple check: validation logic needed ideally)
          if (targetFolderId === movingFolderId) {
            showToast('不能移动到自己', 'error');
            return;
          }
          await moveFolder(movingFolderId, targetFolderId);
          showToast('文件夹已移动', 'success');
        }
        setShowMoveModal(false);
        setMovingDocId(null);
        setMovingFolderId(null);
      } catch (e) {
        showToast('移动失败: ' + (e as Error).message, 'error');
      }
    },
    [movingDocId, movingFolderId, moveDocument, moveFolder, showToast],
  );

  // KG Extraction Handlers
  const handleExtractDoc = useCallback(
    async (docId: string, strategy: 'full' | 'summary-first') => {
      const { extractDocumentGraph } = useRagStore.getState();
      showToast('已加入图谱提取队列: ' + (strategy === 'full' ? '全量' : '摘要'), 'success');
      await extractDocumentGraph(docId, strategy);
    },
    [showToast],
  );

  const handleExtractFolder = useCallback(
    async (folderId: string, strategy: 'full' | 'summary-first') => {
      const { extractBatch, getDocumentsByFolder, folders } = useRagStore.getState();

      // Recursive get docs? Or just direct children?
      // Usually folder action applies to subtree.
      // For simplicity/safety, let's start with direct children or flat list if easy.
      // But store has `getDocumentsByFolder` which is direct.
      // If we want recursive, we need to traverse.
      // Let's implement a simple recursive collector here or assume direct.
      // As "Batch for folder", implies contents.

      // Let's do recursive collection helper
      const collectDocs = (fId: string): string[] => {
        const directDocs = getDocumentsByFolder(fId).map(d => d.id);
        const subFolders = folders.filter(f => f.parentId === fId);
        let all = [...directDocs];
        subFolders.forEach(sub => {
          all = all.concat(collectDocs(sub.id));
        });
        return all;
      };

      const docIds = collectDocs(folderId);
      if (docIds.length === 0) {
        showToast('文件夹为空', 'info');
        return;
      }

      showToast(`已将 ${docIds.length} 个文档加入图谱队列`, 'success');
      await extractBatch(docIds, strategy);
    },
    [showToast],
  );

  const handleViewFolderGraph = useCallback((folderId: string) => {
    router.push({
      pathname: '/knowledge-graph',
      params: { folderId }
    });
  }, [router]);

  // 渲染标题栏
  const renderHeader = () => (
    <View className="mb-0">
      {/* 搜索栏 */}
      <View className="px-4 pb-2">
        <View
          className={`h-12 ${isSearchFocused ? 'bg-indigo-50 dark:bg-indigo-900/20 border-indigo-500' : 'bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800'} 
                               border rounded-2xl flex-row items-center px-4 transition-all`}
        >
          <Search size={18} color={isSearchFocused ? '#6366f1' : '#94a3b8'} strokeWidth={2} />
          <TextInput
            value={searchQuery}
            onChangeText={setSearchQuery}
            onFocus={() => setIsSearchFocused(true)}
            onBlur={() => setIsSearchFocused(false)}
            placeholder={t.library.searchPlaceholder}
            placeholderTextColor="#94a3b8"
            className="flex-1 ml-3 text-gray-900 dark:text-white font-semibold text-base"
          />
          {searchQuery.length > 0 && (
            <TouchableOpacity onPress={() => setSearchQuery('')}>
              <X size={18} color="#94a3b8" />
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* 控制栏 */}
      <ControlBar
        onNewFolder={handleNewFolder}
        onViewGraph={() => router.push('/knowledge-graph')}
        currentTask={
          currentTask
            ? {
              docTitle: currentTask.docTitle,
              progress: currentTask.progress,
            }
            : null
        }
        queueLength={vectorizationQueue.length}
      />


    </View>
  );

  // 拖拽处理
  const handleDrop = useCallback(
    async (event: any) => {
      const assets = event.assets;
      if (!assets || assets.length === 0) return;

      showToast(`开始导入 ${assets.length} 个文件...`, 'info');

      try {
        // 动态导入工具函数
        const { processBatchWithProgress } = await import('../../src/lib/queue-utils');
        const { readFileContent, readFileAsBase64 } = await import('../../src/lib/file-utils');

        const { documentProcessor } = await import('../../src/lib/rag/document-processor');
        if (pdfExtractorRef.current) documentProcessor.setPdfExtractor(pdfExtractorRef.current);

        const processor = async (asset: any) => {
          // Note: asset from drag-drop might have different fields
          const fileName = asset.fileName || asset.uri.split('/').pop() || 'unknown';
          const mimeType = asset.mimeType;

          const processResult = await documentProcessor.processFile(asset.uri, fileName, mimeType);

          if (!processResult.content.trim()) {
            throw new Error(`Empty content: ${fileName}`);
          }

          await addDocument(
            fileName,
            processResult.content,
            processResult.content.length,
            processResult.type as any,
            currentFolderId ?? undefined,
          );
        };

        const result = await processBatchWithProgress(
          assets,
          processor,
          (completed, total) => {
            // 进度回调 (可选)
          },
          1, // Batch size 1 确保 PDF 顺序处理
          50, // Delay ms
        );

        if (result.failed > 0) {
          if (result.success === 0) {
            showToast(`导入失败 (${result.failed} 个文件出错)`, 'error');
          } else {
            showToast(`导入完成: ${result.success} 成功, ${result.failed} 失败`, 'warning');
          }
        } else {
          showToast(`成功导入 ${result.success} 个文件`, 'success');
        }
      } catch (e) {
        console.error('Drag drop batch import failed:', e);
        showToast('批量导入发生错误: ' + (e as Error).message, 'error');
      }
    },
    [addDocument, showToast, currentFolderId],
  );

  return (
    <PageLayout safeArea={false} className="bg-white dark:bg-black">
      {/* 隐藏的 PDF 提取器 */}
      <PdfExtractor ref={pdfExtractorRef} />

      {/* RAG 状态指示器 */}
      <RagStatusIndicator />

      <DragDropContentView style={{ flex: 1 }} onDrop={handleDrop}>
        <View style={{ flex: 1 }}>
          {/* 标题 */}
          <View style={{ paddingTop: 52, paddingBottom: 4, paddingHorizontal: 16 }}>
            <View
              style={{
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
                height: 48,
                marginBottom: 12,
              }}
            >
              <View>
                <Text
                  style={{
                    fontSize: 32,
                    fontWeight: '900',
                    color: isDark ? '#fff' : '#111',
                    letterSpacing: -1.5,
                    lineHeight: 38,
                  }}
                >
                  {t.library.title}
                </Text>
                <Text
                  style={{
                    fontSize: 11,
                    fontWeight: 'bold',
                    color: '#9ca3af',
                    textTransform: 'uppercase',
                    letterSpacing: 2,
                    marginTop: 4,
                    lineHeight: 11,
                  }}
                >
                  知识库管理
                </Text>
              </View>
              <TouchableOpacity
                onPress={handleFileImport}
                style={{
                  width: 48,
                  height: 48,
                  backgroundColor: isDark ? '#18181b' : '#eef2ff',
                  borderWidth: 1,
                  borderColor: isDark ? '#27272a' : '#e0e7ff',
                  borderRadius: 16,
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <FolderInput size={24} color="#6366f1" strokeWidth={2.5} />
              </TouchableOpacity>
            </View>
          </View>

          {renderHeader()}

          {/* 面包屑导航 (非搜索模式显示) */}
          {!searchQuery && (
            <View className="mb-1.5">
              <Breadcrumbs
                currentFolderId={currentFolderId}
                allFolders={folders}
                onNavigate={handleNavigate}
              />
            </View>
          )}

          {/* 内容列表 */}
          <ScrollView
            style={{ flex: 1 }}
            contentContainerStyle={{ paddingBottom: 100 }}
            showsVerticalScrollIndicator={false}
          >
            {/* 文件夹 (搜索模式隐藏) */}
            {!searchQuery &&
              currentViewContent.folders.map((folder) => (
                <FolderItem
                  key={folder.id}
                  id={folder.id}
                  name={folder.name}
                  childCount={folder.childCount}
                  isExpanded={false}
                  level={0}
                  onToggle={() => handleNavigate(folder.id)}
                  onPress={() => handleNavigate(folder.id)}
                  onLongPress={() => { }}
                  onDelete={() => handleDeleteFolder(folder.id, folder.name)}
                  onRename={() => handleRenameFolder(folder.id, folder.name)}
                  onMove={() => handleStartMoveFolder(folder.id)}
                  onViewGraph={() => handleViewFolderGraph(folder.id)}
                  onExtractGraph={(s) => handleExtractFolder(folder.id, s)}
                />
              ))}

            {/* 文档 */}
            {currentViewContent.docs.map((doc) => (
              <CompactDocItem
                key={doc.id}
                id={doc.id}
                title={doc.title}
                vectorized={doc.vectorized}
                vectorCount={doc.vectorCount}
                fileSize={doc.fileSize}
                onLongPress={() => {
                  if (!isSelectionMode) {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                    setIsSelectionMode(true);
                    handleToggleDocSelection(doc.id);
                  }
                }}
                onDelete={() => handleDeleteDocument(doc.id, doc.title)}
                onVectorize={() => vectorizeDocument(doc.id)}
                onMove={() => handleStartMoveDoc(doc.id)}
                isSelectionMode={isSelectionMode}
                isSelected={selectedDocIds.has(doc.id)}
                tags={doc.tags}
                thumbnailPath={doc.thumbnailPath}
                onAssignTag={() => setAssignmentDocId(doc.id)}
                onViewGraph={() =>
                  router.push({ pathname: '/knowledge-graph', params: { docId: doc.id } })
                }
                onExtractGraph={(s) => handleExtractDoc(doc.id, s)}
                onPress={() => {
                  if (isSelectionMode) {
                    handleToggleDocSelection(doc.id);
                  } else if (doc.thumbnailPath) {
                    setPreviewImage(doc.thumbnailPath);
                  } else {
                    // TODO: Open text detail
                    showToast('Open Doc: ' + doc.title, 'info');
                  }
                }}
              />
            ))}

            {/* Modals */}
            {assignmentDocId && TagAssignmentSheetComponent && (
              <TagAssignmentSheetComponent
                visible={!!assignmentDocId}
                docId={assignmentDocId}
                onClose={() => setAssignmentDocId(null)}
                onManageTags={() => {
                  setAssignmentDocId(null);
                  setShowTagManager(true);
                }}
              />
            )}

            {TagManagerSheetComponent && (
              <TagManagerSheetComponent
                visible={showTagManager}
                onClose={() => setShowTagManager(false)}
              />
            )}

            {ImagePreviewModalComponent && (
              <ImagePreviewModalComponent
                visible={!!previewImage}
                imageUri={previewImage}
                onClose={() => setPreviewImage(null)}
              />
            )}

            {/* 空状态提示 */}
            {!searchQuery &&
              currentViewContent.folders.length === 0 &&
              currentViewContent.docs.length === 0 && (
                <View className="items-center justify-center py-20 opacity-50">
                  <Typography className="text-gray-400 font-medium">此文件夹为空</Typography>
                </View>
              )}
          </ScrollView>
        </View>
      </DragDropContentView>

      {/* 批量操作工具栏 */}
      {isSelectionMode && (
        <View
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            paddingBottom: insets.bottom + 16,
            paddingTop: 16,
          }}
          className="bg-white dark:bg-zinc-900 border-t border-gray-100 dark:border-zinc-800 shadow-xl px-6 flex-row justify-between items-center"
        >
          <View className="flex-row items-center gap-4">
            <TouchableOpacity
              onPress={exitSelectionMode}
              className="bg-gray-100 dark:bg-zinc-800 p-3 rounded-full"
            >
              <X size={20} color="#94a3b8" />
            </TouchableOpacity>
            <Text className="text-lg font-bold text-gray-900 dark:text-white">
              已选 {selectedDocIds.size} 项
            </Text>
          </View>

          <View className="flex-row gap-3">
            {/* Re-Vectorize */}
            <TouchableOpacity
              onPress={handleBatchVectorize}
              disabled={selectedDocIds.size === 0}
              className={`flex-row items-center px-4 py-3 rounded-xl gap-2 ${selectedDocIds.size > 0 ? 'bg-indigo-50 dark:bg-indigo-900/20' : 'bg-gray-50 dark:bg-zinc-800 opacity-50'}`}
            >
              <ActivityIndicator size="small" color="#6366f1" style={{ display: 'none' }} />
              {/* Just reusing an icon or text. Let's use text for clarity */}
              <Text
                className={`font-bold ${selectedDocIds.size > 0 ? 'text-indigo-600 dark:text-indigo-400' : 'text-gray-400'}`}
              >
                重向量化
              </Text>
            </TouchableOpacity>

            {/* Delete */}
            <TouchableOpacity
              onPress={handleBatchDelete}
              disabled={selectedDocIds.size === 0}
              className={`flex-row items-center px-4 py-3 rounded-xl gap-2 ${selectedDocIds.size > 0 ? 'bg-red-50 dark:bg-red-900/20' : 'bg-gray-50 dark:bg-zinc-800 opacity-50'}`}
            >
              <Text
                className={`font-bold ${selectedDocIds.size > 0 ? 'text-red-600 dark:text-red-400' : 'text-gray-400'}`}
              >
                删除
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {/* 新建文件夹Modal */}
      <Modal transparent visible={showFolderModal} animationType="fade">
        <View className="flex-1 bg-black/40 items-center justify-center px-6">
          <View className="bg-white dark:bg-zinc-900 rounded-2xl p-6 w-full shadow-xl">
            <Typography className="text-lg font-bold text-gray-900 dark:text-white mb-4">
              {folderModalMode === 'create' ? '新建文件夹' : '重命名文件夹'}
            </Typography>

            <TextInput
              value={newFolderName}
              onChangeText={setNewFolderName}
              placeholder="输入名称"
              placeholderTextColor="#94a3b8"
              className="bg-gray-50 dark:bg-zinc-800 rounded-xl px-4 py-3 text-gray-900 dark:text-white font-semibold mb-6"
              autoFocus
            />

            <View className="flex-row gap-3">
              <TouchableOpacity
                onPress={() => {
                  setShowFolderModal(false);
                  setNewFolderName('');
                  setEditingFolderId(null);
                }}
                className="flex-1 h-12 bg-gray-100 dark:bg-zinc-800 rounded-xl items-center justify-center"
              >
                <Typography className="font-bold text-gray-600 dark:text-gray-400">取消</Typography>
              </TouchableOpacity>

              <TouchableOpacity
                onPress={handleFolderSubmit}
                className="flex-1 h-12 bg-indigo-500 rounded-xl items-center justify-center"
              >
                <Typography className="font-bold text-white">
                  {folderModalMode === 'create' ? '创建' : '确定'}
                </Typography>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* 移动文档Modal */}
      <Modal transparent visible={showMoveModal} animationType="slide">
        <View className="flex-1 bg-black/40 justify-end">
          <View className="bg-white dark:bg-zinc-900 rounded-t-3xl p-6 h-[60%]">
            <View className="flex-row justify-between items-center mb-6">
              <Typography className="text-xl font-bold text-gray-900 dark:text-white">
                {movingDocId ? '移动文档到...' : '移动文件夹到...'}
              </Typography>
              <TouchableOpacity onPress={() => setShowMoveModal(false)}>
                <X size={24} color="#94a3b8" />
              </TouchableOpacity>
            </View>

            <ScrollView className="flex-1">
              {/* 根目录项 */}
              <TouchableOpacity
                onPress={() => handleConfirmMove(null)}
                className="flex-row items-center py-4 border-b border-gray-50 dark:border-zinc-800/50"
              >
                <View className="w-10 h-10 rounded-full bg-indigo-50 dark:bg-indigo-900/20 items-center justify-center mr-4">
                  <Folder size={20} color="#6366f1" />
                </View>
                <Typography className="flex-1 font-bold text-gray-900 dark:text-white">
                  根目录
                </Typography>
              </TouchableOpacity>

              {/* 文件夹列表 (排除自己) */}
              {folders
                .filter((f) => f.id !== movingFolderId)
                .map((folder) => (
                  <TouchableOpacity
                    key={folder.id}
                    onPress={() => handleConfirmMove(folder.id)}
                    className="flex-row items-center py-4 border-b border-gray-50 dark:border-zinc-800/50"
                  >
                    <View className="w-10 h-10 rounded-full bg-amber-50 dark:bg-amber-900/20 items-center justify-center mr-4">
                      <Folder size={20} color="#f59e0b" />
                    </View>
                    <Typography className="flex-1 font-bold text-gray-900 dark:text-white">
                      {folder.name}
                    </Typography>
                  </TouchableOpacity>
                ))}
            </ScrollView>
          </View>
        </View>
      </Modal>

      <ConfirmDialog
        visible={confirmState.visible}
        title={confirmState.title}
        message={confirmState.message}
        isDestructive={confirmState.isDestructive}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
      />
    </PageLayout>
  );
}

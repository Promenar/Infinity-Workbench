import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { View, TouchableOpacity, Text, Modal, TextInput, ActivityIndicator, BackHandler, StyleSheet, RefreshControl } from 'react-native';
import { PageLayout, Typography, useToast, ConfirmDialog, LargeTitleHeader, GlassHeader } from '../../src/components/ui';
import { Search, X, FolderInput, Folder, BookOpen, Clock, ChevronRight, Brain, ChevronLeft, HardDrive, Check } from 'lucide-react-native';
import { Stack, useRouter, useNavigation } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import * as Haptics from 'expo-haptics';
import { BlurView } from 'expo-blur';
import { SilkyGlow } from '../../src/components/ui/SilkyGlow';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
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
import { MemoryItem } from '../../src/components/rag/MemoryItem';

// 使用 any 绕过 FlashList 的类型属性冲突问题
const TypedFlashList = FlashList as any;

import Animated, { FadeIn, FadeOut, LinearTransition } from 'react-native-reanimated';

export default function RagScreen() {
  const router = useRouter();
  const navigation = useNavigation();
  const { showToast } = useToast();
  const { isDark, colors } = useTheme();
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

  const documents = useRagStore(state => state.documents);
  const folders = useRagStore(state => state.folders);
  const loadDocuments = useRagStore(state => state.loadDocuments);
  const loadFolders = useRagStore(state => state.loadFolders);
  const addDocument = useRagStore(state => state.addDocument);
  const deleteDocument = useRagStore(state => state.deleteDocument);
  const vectorizeDocument = useRagStore(state => state.vectorizeDocument);
  const vectorizationQueue = useRagStore(state => state.vectorizationQueue);
  const currentTask = useRagStore(state => state.currentTask);
  const addFolder = useRagStore(state => state.addFolder);
  const deleteFolder = useRagStore(state => state.deleteFolder);
  const renameFolder = useRagStore(state => state.renameFolder);
  const moveDocument = useRagStore(state => state.moveDocument);
  const expandedFolders = useRagStore(state => state.expandedFolders);
  const toggleFolder = useRagStore(state => state.toggleFolder);
  const moveFolder = useRagStore(state => state.moveFolder);
  const setSelectedFolder = useRagStore(state => state.setSelectedFolder);
  const selectedFolder = useRagStore(state => state.selectedFolder);
  const memories = useRagStore(state => state.memories);
  const deleteMemory = useRagStore(state => state.deleteMemory);
  const loadMemories = useRagStore(state => state.loadMemories);
  const getDocumentContent = useRagStore(state => state.getDocumentContent);
  const updateDocumentContent = useRagStore(state => state.updateDocumentContent);
  const _getPhysicalPath = useRagStore(state => state._getPhysicalPath);

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

  const handleRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.all([loadDocuments(), loadFolders(), loadMemories()]);
    } catch (e) {
      console.error('Refresh failed:', e);
    } finally {
      setRefreshing(false);
    }
  }, [loadDocuments, loadFolders, loadMemories]);

  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchFocused, setIsSearchFocused] = useState(false);
  // viewMode: portal (概览), docs (文档列表), memories (记忆列表)
  const [viewMode, setViewMode] = useState<'portal' | 'docs' | 'memories'>('portal');

  // 当进入搜索或点击某个分类时切换 mode
  useEffect(() => {
    if (searchQuery.trim() && viewMode === 'portal') {
      setViewMode('docs');
    }
  }, [searchQuery]);

  const handleBackToPortal = useCallback(() => {
    setViewMode('portal');
    setSelectedFolder(null);
    setSearchQuery('');
    return true; // 返回 true 表示已处理返回事件
  }, [setSelectedFolder]);


  // 搜索过滤
  const filteredDocuments = useMemo(() => {
    if (!searchQuery.trim()) return documents;
    const query = searchQuery.toLowerCase();
    return documents.filter((doc) => doc.title.toLowerCase().includes(query));
  }, [documents, searchQuery]);

  // 记忆搜索过滤
  const filteredMemories = useMemo(() => {
    if (!searchQuery.trim()) return memories;
    const query = searchQuery.toLowerCase();
    return memories.filter((m) => m.content.toLowerCase().includes(query));
  }, [memories, searchQuery]);

  const currentViewContent = useMemo(() => {
    if (searchQuery) return { folders: [], docs: filteredDocuments };

    const filteredFolders = folders.filter(
      (f) =>
        f.parentId === (currentFolderId || undefined) ||
        (currentFolderId === null && !f.parentId),
    );
    const filteredDocs = documents.filter(
      (d) =>
        d.folderId === (currentFolderId || undefined) ||
        (currentFolderId === null && !d.folderId),
    );

    return {
      folders: filteredFolders,
      docs: filteredDocs,
    };
  }, [folders, documents, currentFolderId, searchQuery, filteredDocuments]);

  // 关键：构建列表数据并进行 memoization，防止 FlashList 重复渲染
  const listData = useMemo(() => [
    ...currentViewContent.folders.map(f => ({ type: 'folder' as const, item: f })),
    ...currentViewContent.docs.map(d => ({ type: 'doc' as const, item: d }))
  ], [currentViewContent]);

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

  // Editor State
  const [showEditor, setShowEditor] = useState(false);
  const [editingDocId, setEditingDocId] = useState<string>('');
  const [editingDocTitle, setEditingDocTitle] = useState<string>('');
  const [refreshing, setRefreshing] = useState(false);

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

  // 🛡️ 修复：将依赖状态变量的 useEffect 移至此处，防止变量提升错误
  // 1. 处理系统返回键 (Hardware Back / Swipe Back)
  useEffect(() => {
    const onBackPress = () => {
      // 优先级 1: 如果在多选模式，优先退出多选
      if (isSelectionMode) {
        exitSelectionMode();
        return true;
      }

      // 优先级 2: 如果在搜索且有输入，清空搜索 (保持在当前 viewMode)
      if (searchQuery) {
        setSearchQuery('');
        return true;
      }

      // 优先级 3: 如果在子目录，回推到父目录
      if (selectedFolder) {
        const folder = folders.find(f => f.id === selectedFolder);
        setSelectedFolder(folder?.parentId || null);
        return true;
      }

      // 优先级 4: 如果在非 portal 视图，回到 portal
      if (viewMode !== 'portal') {
        setViewMode('portal');
        return true;
      }

      return false;
    };

    const subscription = BackHandler.addEventListener('hardwareBackPress', onBackPress);
    return () => subscription.remove();
  }, [isSelectionMode, exitSelectionMode, searchQuery, selectedFolder, folders, setSelectedFolder, viewMode]);

  // 2. 页面失焦自动退出多选模式 (防止 Tab 切换导致状态残留)
  useEffect(() => {
    const unsubscribe = navigation.addListener('blur', () => {
      if (isSelectionMode) {
        exitSelectionMode();
      }
    });
    return unsubscribe;
  }, [navigation, isSelectionMode, exitSelectionMode]);

  // 批量删除
  const handleBatchDelete = useCallback(() => {
    const count = selectedDocIds.size;
    if (count === 0) return;

    setConfirmState({
      visible: true,
      title: t.library.batchDelete,
      message: t.library.batchDeleteConfirm.replace('{count}', count.toString()),
      isDestructive: true,
      onConfirm: async () => {
        try {
          const { deleteBatch } = useRagStore.getState();
          await deleteBatch(Array.from(selectedDocIds));
          showToast(t.library.batchDeleteSuccess.replace('{count}', count.toString()), 'success');
          exitSelectionMode();
          setConfirmState((prev) => ({ ...prev, visible: false }));
        } catch (e) {
          showToast(t.library.batchDeleteFail, 'error');
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
      showToast(t.library.batchVectorizeSuccess.replace('{count}', count.toString()), 'success');
      exitSelectionMode();
    } catch (e) {
      showToast(t.library.batchVectorizeFail, 'error');
    }
  }, [selectedDocIds, showToast, exitSelectionMode]);

  useEffect(() => {
    loadDocuments();
    loadFolders();
    loadMemories();
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

      showToast(t.library.importPreparing.replace('{count}', result.assets.length.toString()), 'info');

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
        showToast(t.library.importStats.replace('{success}', resultStats.success.toString()).replace('{failed}', resultStats.failed.toString()), 'warning');
      } else {
        showToast(t.library.importSuccess.replace('{count}', resultStats.success.toString()), 'success');
      }
    } catch (e) {
      console.error(e);
      showToast(t.library.importFail.replace('{error}', (e as Error).message), 'error');
    }
  }, [addDocument, showToast, currentFolderId]);

  // 删除文档
  const handleDeleteDocument = useCallback(
    (id: string, title: string) => {
      setConfirmState({
        visible: true,
        title: t.library.deleteDoc,
        message: t.library.deleteDocConfirm.replace('{title}', title),
        isDestructive: true,
        onConfirm: async () => {
          await deleteDocument(id);
          showToast(t.common.delete + t.common.success, 'success');
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
      showToast(t.library.enterName, 'error');
      return;
    }

    try {
      if (folderModalMode === 'create') {
        const parentId = currentFolderId ?? undefined;
        await addFolder(newFolderName.trim(), parentId);
        showToast(t.library.folderCreated, 'success');
      } else if (editingFolderId) {
        await renameFolder(editingFolderId, newFolderName.trim());
        showToast(t.library.renamed, 'success');
      }
      setShowFolderModal(false);
      setNewFolderName('');
      setEditingFolderId(null);
    } catch (e) {
      showToast(t.common.error + ': ' + (e as Error).message, 'error');
    }
  }, [newFolderName, folderModalMode, editingFolderId, addFolder, renameFolder, showToast, currentFolderId]);

  const handleDeleteFolder = useCallback(
    (id: string, name: string) => {
      setConfirmState({
        visible: true,
        title: t.library.deleteFolder,
        message: t.library.deleteFolderConfirm.replace('{name}', name),
        isDestructive: true,
        onConfirm: async () => {
          await deleteFolder(id);
          showToast(t.common.delete + t.common.success, 'success');
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
          showToast(t.library.moveDocSuccess, 'success');
        } else if (movingFolderId) {
          // Prevent moving folder into itself or its children (simple check: validation logic needed ideally)
          if (targetFolderId === movingFolderId) {
            showToast(t.library.moveSelfError, 'error');
            return;
          }
          await moveFolder(movingFolderId, targetFolderId);
          showToast(t.library.moveFolderSuccess, 'success');
        }
        setShowMoveModal(false);
        setMovingDocId(null);
        setMovingFolderId(null);
      } catch (e) {
        showToast(t.library.moveFail.replace('{error}', (e as Error).message), 'error');
      }
    },
    [movingDocId, movingFolderId, moveDocument, moveFolder, showToast],
  );

  // KG Extraction Handlers
  const handleExtractDoc = useCallback(
    async (docId: string, strategy: 'full' | 'summary-first') => {
      const { extractDocumentGraph } = useRagStore.getState();
      showToast(t.library.extractQueue.replace('{strategy}', (strategy === 'full' ? t.rag.kg.fullScan : t.rag.kg.summaryFirst)), 'success');
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
        showToast(t.library.folderEmpty, 'info');
        return;
      }

      showToast(t.library.extractBatchQueue.replace('{count}', docIds.length.toString()), 'success');
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

  // 分享/导出文档
  const handleExportDoc = useCallback(async (docId: string) => {
    try {
      const doc = documents.find(d => d.id === docId);
      if (!doc) return;

      const physicalPath = await _getPhysicalPath(doc.folderId) + doc.title;
      const info = await FileSystem.getInfoAsync(physicalPath);

      if (!info.exists) {
        // If physical file missing, try to restore from DB if content exists there
        const content = await getDocumentContent(docId);
        if (content) {
          await FileSystem.writeAsStringAsync(physicalPath, content, { encoding: (FileSystem as any).EncodingType.UTF8 });
        } else {
          showToast(t.library.fileMissing, 'error');
          return;
        }
      }

      if (!(await Sharing.isAvailableAsync())) {
        showToast(t.common.sharingUnavailable, 'error');
        return;
      }

      await Sharing.shareAsync(physicalPath);
    } catch (e) {
      showToast(t.library.exportFail.replace('{error}', (e as Error).message), 'error');
    }
  }, [documents, _getPhysicalPath, getDocumentContent, showToast]);

  // 门户卡片组件
  const PortalCards = () => {
    const { isDark, colors } = useTheme();
    const { documents, folders, memories } = useRagStore();

    return (
      <View style={{ paddingHorizontal: 24, marginTop: 0 }}>
        <View style={{ flexDirection: 'row', gap: 16, marginBottom: 20 }}>
          {/* 文档中心卡片 */}
          <TouchableOpacity
            activeOpacity={0.8}
            onPress={() => setViewMode('docs')}
            className="flex-1 overflow-hidden rounded-2xl border"
            style={{
              borderColor: isDark ? 'rgba(99, 102, 241, 0.2)' : 'rgba(0, 0, 0, 0.05)',
            }}
          >
            <BlurView
              intensity={isDark ? 30 : 60}
              tint={isDark ? 'dark' : 'light'}
              style={{
                padding: 20,
                backgroundColor: isDark ? 'rgba(15, 17, 26, 0.7)' : 'rgba(255, 255, 255, 0.8)',
              }}
            >
              <View className="relative">
                {/* 底部装饰光晕 */}
                <View style={{ position: 'absolute', top: -10, left: -10 }}>
                  <SilkyGlow color={colors[500]} size={60} />
                </View>
                <View
                  style={{
                    width: 48,
                    height: 48,
                    borderRadius: 14,
                    backgroundColor: isDark ? 'rgba(99, 102, 241, 0.15)' : colors.opacity10,
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 16,
                  }}
                >
                  <BookOpen size={24} color={colors[500]} />
                </View>
              </View>
              <Typography className="text-lg font-black text-gray-900 dark:text-white mb-1">
                {t.library.tabDocuments}
              </Typography>
              <Typography className="text-xs text-gray-400 font-bold uppercase tracking-wider">
                {documents.length} {t.library.documents}
              </Typography>
            </BlurView>
          </TouchableOpacity>

          {/* 记忆库卡片 */}
          <TouchableOpacity
            activeOpacity={0.8}
            onPress={() => setViewMode('memories')}
            className="flex-1 overflow-hidden rounded-2xl border"
            style={{
              borderColor: isDark ? 'rgba(16, 185, 129, 0.2)' : 'rgba(0, 0, 0, 0.05)',
            }}
          >
            <BlurView
              intensity={isDark ? 30 : 60}
              tint={isDark ? 'dark' : 'light'}
              style={{
                padding: 20,
                backgroundColor: isDark ? 'rgba(15, 17, 26, 0.7)' : 'rgba(255, 255, 255, 0.8)',
              }}
            >
              <View className="relative">
                {/* 底部装饰光晕 */}
                <View style={{ position: 'absolute', top: -10, left: -10 }}>
                  <SilkyGlow color="#10b981" size={60} />
                </View>
                <View
                  style={{
                    width: 48,
                    height: 48,
                    borderRadius: 14,
                    backgroundColor: isDark ? 'rgba(16, 185, 129, 0.15)' : 'rgba(16, 185, 129, 0.1)',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 16,
                  }}
                >
                  <Clock size={24} color="#10b981" />
                </View>
              </View>
              <Typography className="text-lg font-black text-gray-900 dark:text-white mb-1">
                {t.library.tabMemories}
              </Typography>
              <Typography className="text-xs text-gray-400 font-bold uppercase tracking-wider">
                {memories.length} {t.library.itemsCount}
              </Typography>
            </BlurView>
          </TouchableOpacity>
        </View>

        {/* 其它功能入口 */}
        <View style={{ marginTop: 8 }}>
          <Typography className="text-xs font-black text-gray-400 uppercase tracking-widest mb-4 ml-1">
            其他功能
          </Typography>
          <TouchableOpacity
            onPress={() => router.push('/knowledge-graph')}
            activeOpacity={0.7}
            className="flex-row items-center p-4 rounded-2xl border overflow-hidden"
            style={{
              backgroundColor: isDark ? 'rgba(26, 28, 46, 0.4)' : '#f9fafb',
              borderColor: isDark ? 'rgba(99, 102, 241, 0.15)' : 'rgba(0, 0, 0, 0.03)',
            }}
          >
            <View className="w-10 h-10 rounded-xl bg-indigo-500/10 items-center justify-center mr-4">
              <Brain size={20} color={colors[500]} />
            </View>
            <View className="flex-1">
              <Typography className="font-bold text-gray-900 dark:text-white">全局知识图谱</Typography>
              <Typography className="text-xs text-gray-400 mt-0.5">查看所有文档的关联关系</Typography>
            </View>
            <ChevronRight size={18} color="#94a3b8" />
          </TouchableOpacity>

        </View>
      </View>
    );
  };

  // 渲染标题栏
  const renderHeader = () => {
    const isPortal = viewMode === 'portal';
    const isMemories = viewMode === 'memories';

    return (
      <View className="mb-0">
        <Animated.View
          key={viewMode}
          entering={FadeIn.duration(200)}
          exiting={FadeOut.duration(150)}
        >
          <LargeTitleHeader
            title={isPortal ? t.library.title : (isMemories ? t.library.tabMemories : t.library.tabDocuments)}
            subtitle={isPortal ? t.library.subtitle : (isMemories ? t.library.memoriesSubtitle : (currentFolderId ? folders.find(f => f.id === currentFolderId)?.name : t.library.subtitle))}
            leftAction={!isPortal ? {
              icon: <ChevronLeft size={28} color={isDark ? '#fff' : '#000'} />,
              onPress: handleBackToPortal,
            } : undefined}
            rightElement={!isMemories ? (
              <TouchableOpacity
                onPress={handleFileImport}
                style={{
                  width: 48,
                  height: 48,
                  backgroundColor: isDark ? 'rgba(15, 17, 26, 0.4)' : colors[50],
                  borderWidth: 1,
                  borderColor: isDark ? 'rgba(99, 102, 241, 0.15)' : colors[200],
                  borderRadius: 16,
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <FolderInput size={24} color={colors[500]} strokeWidth={2.5} />
              </TouchableOpacity>
            ) : undefined}
          />
        </Animated.View>

        {/* 搜索栏 */}
        <View className="px-6 pb-4">
          <View
            className="h-12 border rounded-2xl flex-row items-center px-4 transition-all overflow-hidden"
            style={{
              backgroundColor: isSearchFocused
                ? (isDark ? 'rgba(99, 102, 241, 0.15)' : colors[50])
                : (isDark ? 'rgba(15, 17, 26, 0.4)' : '#f9fafb'),
              borderColor: isSearchFocused
                ? colors[500]
                : (isDark ? 'rgba(99, 102, 241, 0.1)' : '#f3f4f6')
            }}
          >
            {isDark && (
              <BlurView
                intensity={20}
                tint="dark"
                style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
              />
            )}
            <Search size={18} color={isSearchFocused ? colors[500] : '#94a3b8'} strokeWidth={2} />
            <TextInput
              value={searchQuery}
              onChangeText={setSearchQuery}
              onFocus={() => setIsSearchFocused(true)}
              onBlur={() => setIsSearchFocused(false)}
              placeholder={isMemories ? "搜索会话记忆..." : t.library.searchPlaceholder}
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

        {/* 控制栏 (仅在文档列表显示) */}
        {viewMode === 'docs' && (
          <ControlBar
            onNewFolder={handleNewFolder}
            onViewGraph={() => router.push('/knowledge-graph')}
            currentTask={
              currentTask
                ? {
                  docTitle: currentTask.docTitle || '处理中...',
                  progress: currentTask.progress,
                }
                : null
            }
            queueLength={vectorizationQueue.length}
          />
        )}
      </View>
    );
  };

  // 拖拽处理
  const handleDrop = useCallback(
    async (event: any) => {
      const assets = event.assets;
      if (!assets || assets.length === 0) return;

      showToast(t.library.importPreparing.replace('{count}', assets.length.toString()), 'info');

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
            showToast(t.library.importFail.replace('{error}', result.failed.toString()), 'error');
          } else {
            showToast(t.library.importStats.replace('{success}', result.success.toString()).replace('{failed}', result.failed.toString()), 'warning');
          }
        } else {
          showToast(t.library.importSuccess.replace('{count}', result.success.toString()), 'success');
        }
      } catch (e) {
        console.error('Drag drop batch import failed:', e);
        showToast(t.common.error + ': ' + (e as Error).message, 'error');
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
          {renderHeader()}

          {/* 面包屑导航 (仅在文档页显示) */}
          {viewMode === 'docs' && !searchQuery && (
            <View className="mb-1.5">
              <Breadcrumbs
                currentFolderId={currentFolderId}
                allFolders={folders}
                onNavigate={handleNavigate}
              />
            </View>
          )}

          {/* 内容展示区 */}
          <Animated.View
            key={viewMode}
            entering={FadeIn.duration(200)}
            exiting={FadeOut.duration(150)}
            style={{ flex: 1, minHeight: 200 }}
          >
            {viewMode === 'portal' && <PortalCards />}

            {viewMode === 'docs' && (
              <TypedFlashList
                data={listData}
                keyExtractor={(item: any) => item.item.id}
                renderItem={({ item }: any) => {
                  if (item.type === 'folder') {
                    const folder = item.item;
                    return (
                      <FolderItem
                        id={folder.id}
                        name={folder.name === 'workspace' ? t.library.workspace : folder.name}
                        childCount={folder.childCount}
                        isExpanded={false}
                        level={0}
                        onToggle={() => handleNavigate(folder.id)}
                        onPress={() => handleNavigate(folder.id)}
                        onDelete={() => handleDeleteFolder(folder.id, folder.name)}
                        onRename={() => handleRenameFolder(folder.id, folder.name)}
                        onMove={() => handleStartMoveFolder(folder.id)}
                        onViewGraph={() => handleViewFolderGraph(folder.id)}
                        onExtractGraph={(s) => handleExtractFolder(folder.id, s)}
                      />
                    );
                  } else {
                    const doc = item.item;
                    return (
                      <CompactDocItem
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
                        onEdit={() => {
                          setEditingDocId(doc.id);
                          setEditingDocTitle(doc.title);
                          setShowEditor(true);
                        }}
                        onShare={() => handleExportDoc(doc.id)}
                        onPress={() => {
                          if (isSelectionMode) {
                            handleToggleDocSelection(doc.id);
                          } else if (doc.type === 'image' && doc.thumbnailPath) {
                            setPreviewImage(doc.thumbnailPath);
                          } else {
                            // Default action for docs: Open Editor
                            setEditingDocId(doc.id);
                            setEditingDocTitle(doc.title);
                            setShowEditor(true);
                          }
                        }}
                      />
                    );
                  }
                }}
                estimatedItemSize={80}
                contentContainerStyle={{ paddingBottom: 100 }}
                refreshControl={
                  <RefreshControl
                    refreshing={refreshing}
                    onRefresh={handleRefresh}
                    tintColor={colors[500]}
                    colors={[colors[500]]}
                  />
                }
                ListEmptyComponent={() => (
                  <View className="items-center justify-center py-20 opacity-50">
                    <Typography className="text-gray-400 font-medium">{t.library.emptyState}</Typography>
                  </View>
                )}
              />
            )}

            {viewMode === 'memories' && (
              <TypedFlashList
                data={filteredMemories}
                keyExtractor={(item: any) => item.id}
                renderItem={({ item }: any) => (
                  <MemoryItem
                    id={item.id}
                    content={item.content}
                    createdAt={item.createdAt}
                    onDelete={() => {
                      setConfirmState({
                        visible: true,
                        title: t.library.deleteMemory,
                        message: t.library.deleteMemoryConfirm,
                        isDestructive: true,
                        onConfirm: async () => {
                          await deleteMemory(item.id);
                          showToast(t.common.delete + t.common.success, 'success');
                          setConfirmState((prev) => ({ ...prev, visible: false }));
                        }
                      });
                    }}
                    onPress={() => {
                      showToast(item.content.substring(0, 30) + '...', 'info');
                    }}
                  />
                )}
                estimatedItemSize={120}
                contentContainerStyle={{ paddingBottom: 100 }}
                ListEmptyComponent={() => (
                  <View className="items-center justify-center py-20 opacity-50">
                    <Typography className="text-gray-400 font-medium">{t.library.memoryEmptyState}</Typography>
                  </View>
                )}
              />
            )}

            {/* Modals - Keep them here so they work in all view modes if needed */}
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

            {showEditor && (
              <FileEditorModal
                visible={showEditor}
                docId={editingDocId}
                docTitle={editingDocTitle}
                onClose={() => setShowEditor(false)}
                onSave={async (content) => {
                  try {
                    await updateDocumentContent(editingDocId, content);
                    showToast(t.common.save + t.common.success, 'success');
                    setShowEditor(false);
                    loadDocuments(); // Refresh to show potential status change (if re-vectorized)
                  } catch (e) {
                    showToast(t.common.save + t.common.error, 'error');
                  }
                }}
              />
            )}
          </Animated.View>
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
              {t.library.selected.replace('{count}', selectedDocIds.size.toString())}
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

// 文档编辑器分量
interface FileEditorModalProps {
  visible: boolean;
  docId: string;
  docTitle: string;
  onClose: () => void;
  onSave: (content: string) => Promise<void>;
}

const FileEditorModal = ({ visible, docId, docTitle, onClose, onSave }: FileEditorModalProps) => {
  const { isDark, colors } = useTheme();
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(true);
  const insets = useSafeAreaInsets();
  const { t } = useI18n();

  const { getDocumentContent } = useRagStore.getState();

  useEffect(() => {
    if (visible && docId) {
      loadFile();
    }
  }, [visible, docId]);

  const loadFile = async () => {
    setLoading(true);
    try {
      const text = await getDocumentContent(docId);
      setContent(text);
    } catch (e) {
      console.error('Failed to load doc content:', e);
    } finally {
      setLoading(false);
    }
  };

  if (!visible) return null;

  return (
    <Modal visible={visible} animationType="slide">
      <View className="flex-1 bg-white dark:bg-black">
        <GlassHeader
          title={docTitle}
          subtitle="编辑文档内容"
          leftAction={{
            icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
            onPress: onClose
          }}
          rightAction={{
            icon: loading ? <ActivityIndicator size="small" /> : (
              <Check size={24} color={isDark ? '#fff' : '#000'} />
            ),
            onPress: () => onSave(content)
          }}
        />
        <View style={{ paddingTop: 100 + insets.top, paddingHorizontal: 20 }} className="flex-1">
          {loading ? (
            <View className="flex-1 items-center justify-center">
              <ActivityIndicator size="large" color={colors[500]} />
            </View>
          ) : (
            <TextInput
              value={content}
              onChangeText={setContent}
              multiline
              textAlignVertical="top"
              autoFocus
              className="flex-1 text-gray-900 dark:text-gray-100 text-base"
              placeholder="开始输入内容..."
              placeholderTextColor="#94a3b8"
              style={{ lineHeight: 24 }}
            />
          )}
        </View>
      </View>
    </Modal>
  );
};
